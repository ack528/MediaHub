package com.localtg.render

import android.opengl.GLES20
import android.opengl.GLES30
import com.localtg.AppLog

/** GL 小工具:编译着色器、建纹理 / FBO。所有函数都必须在渲染线程(EGL 上下文当前)调用。 */
object Gl {
    const val VERTEX = """#version 300 es
out vec2 vPos;
void main() {
    // 一个覆盖整个视口的大三角形,不需要顶点缓冲
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vPos = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    fun check(tag: String) {
        val e = GLES30.glGetError()
        if (e != GLES30.GL_NO_ERROR) AppLog.w("gl", "$tag: GL 错误 0x${Integer.toHexString(e)}")
    }

    fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            GLES30.glDeleteShader(s)
            throw IllegalStateException("着色器编译失败:$log")
        }
        return s
    }

    fun program(fragment: String, vertex: String = VERTEX): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = try { compile(GLES30.GL_FRAGMENT_SHADER, fragment) } catch (e: Exception) { GLES30.glDeleteShader(vs); throw e }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(p)
            GLES30.glDeleteProgram(p)
            throw IllegalStateException("着色器链接失败:$log")
        }
        return p
    }

    /** 一张可渲染的纹理(RGBA16F 或 RGBA8),附带自己的 FBO。 */
    class Tex(val id: Int, val fbo: Int, val w: Int, val h: Int, val half: Boolean) {
        fun delete() {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(id), 0)
        }
    }

    fun newTex(w: Int, h: Int, half: Boolean, linear: Boolean = true): Tex {
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, if (half) GLES30.GL_RGBA16F else GLES30.GL_RGBA8, w, h)
        val f = if (linear) GLES30.GL_LINEAR else GLES30.GL_NEAREST
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, f)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, f)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val fb = IntArray(1)
        GLES30.glGenFramebuffers(1, fb, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fb[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
        val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteFramebuffers(1, fb, 0)
            GLES30.glDeleteTextures(1, t, 0)
            throw IllegalStateException("FBO 不完整 0x${Integer.toHexString(st)} (${w}x$h half=$half)")
        }
        return Tex(t[0], fb[0], w, h, half)
    }

    /** 纹理池:相同尺寸 / 格式的纹理循环使用,避免每帧分配。 */
    class Pool(private val half: Boolean) {
        private val free = HashMap<Long, ArrayDeque<Tex>>()
        private fun key(w: Int, h: Int) = (w.toLong() shl 32) or h.toLong()
        fun acquire(w: Int, h: Int): Tex = free[key(w, h)]?.removeLastOrNull() ?: newTex(w, h, half)
        fun release(t: Tex?) {
            if (t == null) return
            val q = free.getOrPut(key(t.w, t.h)) { ArrayDeque() }
            if (q.size >= 4) t.delete() else q.addLast(t)
        }
        fun clear() { free.values.forEach { q -> q.forEach { it.delete() } }; free.clear() }
    }

    fun use(p: Int) = GLES20.glUseProgram(p)
    fun loc(p: Int, name: String) = GLES20.glGetUniformLocation(p, name)

    fun bindTex(unit: Int, id: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
    }

    fun draw() = GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

    fun target(t: Tex) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, t.fbo)
        GLES30.glViewport(0, 0, t.w, t.h)
    }
}
