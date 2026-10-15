package com.localtg.render

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * 有效清晰度检测:视频标称 1080p / 4K,但画面其实是低分辨率片源放大来的(很糊),按标称分辨率判断"够清晰、不用超分"就错了。
 *
 * 方法:在源帧上取规则格点,比较"相邻 1 像素"和"相隔 4 像素"的亮度差平方和之比 R。
 * 边缘被模糊后,相隔 1 像素的差降得比相隔 4 像素的快:清晰画面 R 约 1/4(实测 0.33),糊到 3 倍以上时落到 1/16(实测 0.08)。
 * 16×9 个小块各算一次,CPU 端把各块的和相加(= 按块的能量加权,平坦的天空 / 黑边几乎不影响结果)。
 * 标定见 tools\tmp\calib_blur.py:f(有效缩小倍数)= 1 / 1.25 / 1.5 / 2 / 3 / 4 / 6 对应 R = 0.33 / 0.24 / 0.17 / 0.14 / 0.081 / 0.081 / 0.071。
 */
class SharpnessMeter {
    private val prog = Gl.program(MEASURE)
    private val target = Gl.newTex(GW, GH, true, linear = false)
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(GW * GH * 16).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** 返回 R(0 ~ 1),画面太平坦 / 太小测不出来时返回 -1。 */
    fun measure(src: Gl.Tex): Float {
        if (src.w < 480 || src.h < 270) return -1f
        Gl.target(target)
        Gl.use(prog)
        Gl.bindTex(0, src.id)
        GLES20.glUniform1i(Gl.loc(prog, "uTex"), 0)
        GLES20.glUniform2i(Gl.loc(prog, "uSize"), src.w, src.h)
        Gl.draw()
        buf.clear()
        GLES30.glReadPixels(0, 0, GW, GH, GLES30.GL_RGBA, GLES30.GL_FLOAT, buf)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) return -1f
        var e1 = 0.0; var e4 = 0.0
        for (i in 0 until GW * GH) { e1 += buf.get(i * 4); e4 += buf.get(i * 4 + 1) }
        if (e4 < 1e-3) return -1f // 几乎没有任何纹理(纯色 / 黑屏)
        return (e1 / e4).toFloat()
    }

    fun destroy() { GLES30.glDeleteProgram(prog); target.delete() }

    companion object {
        private const val GW = 16
        private const val GH = 9

        /** R → 有效缩小倍数(1 = 本来就清晰)。阈值取自标定表相邻两档的中点,清晰档留足余量(带噪点 / 颗粒的片源 R 会偏高,偏向"不处理")。 */
        fun scaleFromR(r: Float): Float = when {
            r < 0f -> 1f
            r > 0.20f -> 1f
            r > 0.152f -> 1.5f
            r > 0.105f -> 2f
            else -> 3f
        }

        private const val MEASURE = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
uniform sampler2D uTex;
uniform ivec2 uSize;
out vec4 outColor;
float Y(ivec2 p) {
    p = clamp(p, ivec2(0), uSize - 1);
    return dot(texelFetch(uTex, p, 0).rgb, vec3(0.2126, 0.7152, 0.0722));
}
void main() {
    ivec2 cell = ivec2(gl_FragCoord.xy);                       // 0..15, 0..8
    ivec2 blk = ivec2(uSize.x / $GW, uSize.y / $GH);
    ivec2 o = cell * blk;
    int sx = max(blk.x / 16, 3);
    int sy = max(blk.y / 16, 3);
    float e1 = 0.0, e4 = 0.0;
    for (int j = 0; j < 16; j++) {
        for (int i = 0; i < 16; i++) {
            ivec2 p = o + ivec2(i * sx, j * sy);
            if (p.x >= uSize.x - 5 || p.y >= uSize.y - 5) continue;
            float a = Y(p);
            float dx1 = Y(p + ivec2(1, 0)) - a, dy1 = Y(p + ivec2(0, 1)) - a;
            float dx4 = Y(p + ivec2(4, 0)) - a, dy4 = Y(p + ivec2(0, 4)) - a;
            e1 += dx1 * dx1 + dy1 * dy1;
            e4 += dx4 * dx4 + dy4 * dy4;
        }
    }
    outColor = vec4(e1 * 4.0, e4 * 4.0, 0.0, 1.0);             // 每块 256 个点,放大一点避免半精度下溢
}
"""
    }
}
