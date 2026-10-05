package com.localtg.render

import android.opengl.GLES20
import android.opengl.GLES30
import com.localtg.AppLog

/**
 * 补帧(运动估计 + 运动补偿插帧),全部在 GPU 上用片元着色器完成。
 *
 * 做法(块匹配 + 三层金字塔,常见的轻量方案):
 *  1. 每个源帧到达时,把亮度缩成 1/2、1/4、1/8 三层金字塔;
 *  2. 与上一帧做运动估计:最粗层(1/8)对每个 4×4 块在 ±8 像素内全搜索(SAD + 小的零运动偏好),
 *     中层、细层在上一层结果(及相邻块的结果)附近 ±1 精修,得到 1/8 分辨率的运动场(单位:1/2 分辨率的像素);
 *  3. 每次屏幕刷新(vsync)按时间插值系数 t 合成中间帧:A 沿运动场前移、B 沿运动场后移后加权;
 *     匹配不可信(遮挡 / 场景切换)的像素退回到不补偿的混合,差异再大就直接保持最近的一帧,避免重影。
 */
object FrcShaders {
    private const val HEAD = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nin vec2 vPos;\nout vec4 outColor;\n"

    /** 亮度金字塔:从 RGBA 纹理取亮度并缩小一半(硬件双线性做 2×2 平均);首层用 uFirst=1 时直接取 RGB 亮度。 */
    val DOWN = HEAD + """
uniform sampler2D uTex;
uniform float uFirst;
void main() {
    vec4 c = texture(uTex, vPos);
    outColor = vec4(uFirst > 0.5 ? dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)) : c.r, 0.0, 0.0, 1.0);
}
"""

    /** 最粗层:每个 4×4 块 ±8 全搜索。outColor = (vx, vy, sad, 1),向量是 A→B 的位移(像素)。 */
    val ME_COARSE = HEAD + """
uniform sampler2D uA;
uniform sampler2D uB;
const int R = 8;
float fa(ivec2 p, ivec2 sz) { return texelFetch(uA, clamp(p, ivec2(0), sz - 1), 0).r; }
void main() {
    ivec2 sz = textureSize(uB, 0);
    ivec2 base = ivec2(gl_FragCoord.xy) * 4;
    float b[16];
    for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++)
        b[j * 4 + i] = texelFetch(uB, clamp(base + ivec2(i, j), ivec2(0), sz - 1), 0).r;
    float best = 1e9; ivec2 bv = ivec2(0);
    for (int dy = -R; dy <= R; dy++) {
        for (int dx = -R; dx <= R; dx++) {
            float sad = 0.0;
            for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++)
                sad += abs(b[j * 4 + i] - fa(base + ivec2(i - dx, j - dy), sz));
            sad += 0.01 * float(abs(dx) + abs(dy));
            if (sad < best) { best = sad; bv = ivec2(dx, dy); }
        }
    }
    outColor = vec4(vec2(bv), best, 1.0);
}
"""

    /** 亚像素精修:在整数结果附近 ±step 的 3×3 个位置上用双线性采样重新匹配(先 0.5 再 0.25 像素)。 */
    val ME_SUBPEL = HEAD + """
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uPrev;
uniform float uStep;
void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);
    ivec2 base = cell * 4;
    vec2 sz = vec2(textureSize(uA, 0));
    ivec2 isz = textureSize(uB, 0);
    vec2 v0 = texelFetch(uPrev, cell, 0).xy;
    float b[16];
    for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++)
        b[j * 4 + i] = texelFetch(uB, clamp(base + ivec2(i, j), ivec2(0), isz - 1), 0).r;
    float best = 1e9; vec2 bv = v0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            vec2 v = v0 + vec2(float(dx), float(dy)) * uStep;
            float sad = 0.0;
            for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++)
                sad += abs(b[j * 4 + i] - texture(uA, (vec2(base + ivec2(i, j)) + 0.5 - v) / sz).r);
            sad += 0.02 * float(abs(dx) + abs(dy)) * uStep;
            if (sad < best) { best = sad; bv = v; }
        }
    }
    outColor = vec4(bv, best, 1.0);
}
"""

    /** 精修层:以上一层(更粗)的运动向量×2 为中心,在自己与相邻块的候选附近 ±1 搜索。 */
    val ME_REFINE = HEAD + """
uniform sampler2D uA;
uniform sampler2D uB;
uniform sampler2D uPrev;     // 更粗一层的运动场
void main() {
    ivec2 sz = textureSize(uB, 0);
    ivec2 psz = textureSize(uPrev, 0);
    ivec2 cell = ivec2(gl_FragCoord.xy);
    ivec2 base = cell * 4;
    float b[16];
    for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++)
        b[j * 4 + i] = texelFetch(uB, clamp(base + ivec2(i, j), ivec2(0), sz - 1), 0).r;
    ivec2 pc = cell / 2;
    ivec2 nb = ivec2((cell.x & 1) == 0 ? -1 : 1, (cell.y & 1) == 0 ? -1 : 1); // 靠近哪个方向的相邻粗块
    ivec2 cand[4] = ivec2[4](pc, pc + ivec2(nb.x, 0), pc + ivec2(0, nb.y), pc + nb);
    float best = 1e9; ivec2 bv = ivec2(0);
    for (int n = 0; n < 5; n++) {
        ivec2 c0 = ivec2(0);
        if (n < 4) c0 = ivec2(texelFetch(uPrev, clamp(cand[n], ivec2(0), psz - 1), 0).xy * 2.0);
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (n == 4 && (dx != 0 || dy != 0)) continue;
                ivec2 v = c0 + ivec2(dx, dy);
                float sad = 0.0;
                for (int j = 0; j < 4; j++) for (int i = 0; i < 4; i++)
                    sad += abs(b[j * 4 + i] - texelFetch(uA, clamp(base + ivec2(i, j) - v, ivec2(0), sz - 1), 0).r);
                sad += 0.01 * float(abs(v.x) + abs(v.y)) + 0.02 * float(abs(dx) + abs(dy)); // 偏好零运动 / 与父块一致
                if (sad < best) { best = sad; bv = v; }
            }
        }
    }
    outColor = vec4(vec2(bv), best, 1.0);
}
"""
}

/** 一帧的运动估计所需的亮度金字塔(1/2、1/4、1/8)。 */
class LumaPyramid(val d1: Gl.Tex, val d2: Gl.Tex, val d3: Gl.Tex)

class MotionEstimator(private val pool: Gl.Pool) {
    /** 最近一次 estimate 返回的运动场,向量的单位是哪一层亮度图的像素(尺寸);插帧着色器用它把向量换算成纹理坐标。 */
    var unitW = 1; private set
    var unitH = 1; private set

    private val down = Gl.program(FrcShaders.DOWN)
    private val coarse = Gl.program(FrcShaders.ME_COARSE)
    private val refine = Gl.program(FrcShaders.ME_REFINE)
    private val subpel = Gl.program(FrcShaders.ME_SUBPEL)

    private fun pass(program: Int, dst: Gl.Tex, vararg ins: Pair<String, Gl.Tex>, setup: (() -> Unit)? = null) {
        Gl.target(dst)
        Gl.use(program)
        ins.forEachIndexed { i, (n, t) ->
            Gl.bindTex(i, t.id)
            GLES20.glUniform1i(Gl.loc(program, n), i)
        }
        setup?.invoke()
        Gl.draw()
    }

    /** 从源帧(RGBA 纹理)生成亮度金字塔。 */
    fun pyramid(src: Gl.Tex): LumaPyramid {
        val d1 = pool.acquire(maxOf(src.w / 2, 8), maxOf(src.h / 2, 8))
        pass(down, d1, "uTex" to src) { GLES20.glUniform1f(Gl.loc(down, "uFirst"), 1f) }
        val d2 = pool.acquire(maxOf(d1.w / 2, 8), maxOf(d1.h / 2, 8))
        pass(down, d2, "uTex" to d1) { GLES20.glUniform1f(Gl.loc(down, "uFirst"), 0f) }
        val d3 = pool.acquire(maxOf(d2.w / 2, 8), maxOf(d2.h / 2, 8))
        pass(down, d3, "uTex" to d2) { GLES20.glUniform1f(Gl.loc(down, "uFirst"), 0f) }
        return LumaPyramid(d1, d2, d3)
    }

    fun release(p: LumaPyramid?) {
        if (p == null) return
        pool.release(p.d1); pool.release(p.d2); pool.release(p.d3)
    }

    /**
     * 估计 A→B 的运动场。返回 1/8 分辨率(相对 D1 的 4×4 块)的 RGBA16F 纹理,xy = 向量(单位:D1 像素),调用方用完要放回池。
     * 运动场纹理尺寸:(D1.w/4, D1.h/4);三层的格子大小依次是 D3/4、D2/4、D1/4。
     */
    fun estimate(a: LumaPyramid, b: LumaPyramid, grid: GridPool, quality: String = "mc"): Gl.Tex {
        val g3 = grid.acquire((b.d3.w + 3) / 4, (b.d3.h + 3) / 4)
        pass(coarse, g3, "uA" to a.d3, "uB" to b.d3)
        val g2 = grid.acquire((b.d2.w + 3) / 4, (b.d2.h + 3) / 4)
        pass(refine, g2, "uA" to a.d2, "uB" to b.d2, "uPrev" to g3)
        grid.release(g3)
        if (quality == "mc_fast") {
            // 轻量档:运动场只算到 1/4 分辨率层(格子数是细层的 1/4),只做一轮半像素精修 —— 估计耗时约为标准档的 1/3
            val s = grid.acquire(g2.w, g2.h)
            pass(subpel, s, "uA" to a.d2, "uB" to b.d2, "uPrev" to g2) { GLES20.glUniform1f(Gl.loc(subpel, "uStep"), 0.5f) }
            grid.release(g2)
            unitW = b.d2.w; unitH = b.d2.h
            return s
        }
        val g1 = grid.acquire((b.d1.w + 3) / 4, (b.d1.h + 3) / 4)
        pass(refine, g1, "uA" to a.d1, "uB" to b.d1, "uPrev" to g2)
        grid.release(g2)
        // 亚像素精修:整数向量的误差最大 0.5 像素(源图 1 像素),半像素 / 四分之一像素两轮把它压到 ~0.25 源像素
        val s1 = grid.acquire(g1.w, g1.h)
        pass(subpel, s1, "uA" to a.d1, "uB" to b.d1, "uPrev" to g1) { GLES20.glUniform1f(Gl.loc(subpel, "uStep"), 0.5f) }
        val s2 = grid.acquire(g1.w, g1.h)
        pass(subpel, s2, "uA" to a.d1, "uB" to b.d1, "uPrev" to s1) { GLES20.glUniform1f(Gl.loc(subpel, "uStep"), 0.25f) }
        grid.release(g1); grid.release(s1)
        unitW = b.d1.w; unitH = b.d1.h
        if (quality == "mc_hq") {
            // 高质量档:再来一轮 1/8 像素精修,运动更准(插值时少一点抖动)
            val s3 = grid.acquire(g1.w, g1.h)
            pass(subpel, s3, "uA" to a.d1, "uB" to b.d1, "uPrev" to s2) { GLES20.glUniform1f(Gl.loc(subpel, "uStep"), 0.125f) }
            grid.release(s2)
            return s3
        }
        return s2
    }

    fun destroy() {
        GLES30.glDeleteProgram(down); GLES30.glDeleteProgram(coarse); GLES30.glDeleteProgram(refine); GLES30.glDeleteProgram(subpel)
    }
}

/** 运动场用的纹理池(RGBA16F,最近邻采样不需要,但统一用线性以便插值时平滑)。 */
class GridPool {
    private val pool = Gl.Pool(true)
    fun acquire(w: Int, h: Int): Gl.Tex = pool.acquire(maxOf(w, 1), maxOf(h, 1))
    fun release(t: Gl.Tex?) = pool.release(t)
    fun clear() = pool.clear()
}
