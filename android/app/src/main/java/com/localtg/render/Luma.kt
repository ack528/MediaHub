package com.localtg.render

import android.opengl.GLES20
import android.opengl.GLES30

/**
 * 亮度金字塔:SDR→HDR 时用 1/8 分辨率的亮度图判断"大面积亮区"(这类区域少扩展高光,避免整屏刺眼)。
 * 以前这里还有块匹配运动估计(给运动补偿补帧用),补帧现在只保留 LSFG,运动估计已经删掉。
 */
class LumaPyramid(val d3: Gl.Tex)

class LumaBuilder(private val pool: Gl.Pool) {
    private val down = Gl.program(DOWN)

    private fun pass(dst: Gl.Tex, src: Gl.Tex, first: Float) {
        Gl.target(dst)
        Gl.use(down)
        Gl.bindTex(0, src.id)
        GLES20.glUniform1i(Gl.loc(down, "uTex"), 0)
        GLES20.glUniform1f(Gl.loc(down, "uFirst"), first)
        Gl.draw()
    }

    /** 从源帧(RGBA 纹理)缩出 1/8 分辨率的亮度图(1/2 → 1/4 → 1/8,中间两层用完立刻放回池里)。 */
    fun build(src: Gl.Tex): LumaPyramid {
        val d1 = pool.acquire(maxOf(src.w / 2, 8), maxOf(src.h / 2, 8))
        pass(d1, src, 1f)
        val d2 = pool.acquire(maxOf(d1.w / 2, 8), maxOf(d1.h / 2, 8))
        pass(d2, d1, 0f)
        pool.release(d1)
        val d3 = pool.acquire(maxOf(d2.w / 2, 8), maxOf(d2.h / 2, 8))
        pass(d3, d2, 0f)
        pool.release(d2)
        return LumaPyramid(d3)
    }

    fun release(p: LumaPyramid?) { if (p != null) pool.release(p.d3) }

    fun destroy() { GLES30.glDeleteProgram(down) }

    private companion object {
        /** 取亮度并缩小一半(硬件双线性做 2×2 平均);首层 uFirst=1 时直接取 RGB 亮度。 */
        const val DOWN = "#version 300 es\nprecision highp float;\nin vec2 vPos;\nout vec4 outColor;\n" + """
uniform sampler2D uTex;
uniform float uFirst;
void main() {
    vec4 c = texture(uTex, vPos);
    outColor = vec4(uFirst > 0.5 ? dot(c.rgb, vec3(0.2126, 0.7152, 0.0722)) : c.r, 0.0, 0.0, 1.0);
}
"""
    }
}
