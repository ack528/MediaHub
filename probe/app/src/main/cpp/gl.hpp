#pragma once
// EGL / GLES3 + AHardwareBuffer 辅助(离屏 pbuffer 上下文,不需要窗口)
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>

#include "common.hpp"

struct GlCtx {
    EGLDisplay dpy = EGL_NO_DISPLAY;
    EGLContext ctx = EGL_NO_CONTEXT;
    EGLSurface surf = EGL_NO_SURFACE;
    PFNEGLCREATEIMAGEKHRPROC createImage = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage = nullptr;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getClientBuffer = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTarget = nullptr;
    GLuint prog = 0;
    GLuint vao = 0;
    GLint uTexLoc = -1;

    bool init(Out &o) {
        dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGLint maj = 0, min = 0;
        if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, &maj, &min)) { o.line("EGL 初始化失败 0x%x", eglGetError()); return false; }
        const EGLint cfgAttr[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                                  EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLConfig cfg;
        EGLint n = 0;
        if (!eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n) || n < 1) { o.line("没有可用的 EGL 配置(GLES3 pbuffer) 0x%x", eglGetError()); return false; }
        const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
        if (ctx == EGL_NO_CONTEXT) { o.line("创建 GLES3 上下文失败 0x%x", eglGetError()); return false; }
        const EGLint pbAttr[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
        surf = eglCreatePbufferSurface(dpy, cfg, pbAttr);
        if (surf == EGL_NO_SURFACE || !eglMakeCurrent(dpy, surf, surf, ctx)) { o.line("eglMakeCurrent 失败 0x%x", eglGetError()); return false; }
        createImage = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        getClientBuffer = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        imageTarget = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        return true;
    }

    bool hasAhbInterop() const { return createImage && destroyImage && getClientBuffer && imageTarget; }

    void destroy() {
        if (prog) glDeleteProgram(prog);
        if (vao) glDeleteVertexArrays(1, &vao);
        prog = vao = 0;
        if (dpy != EGL_NO_DISPLAY) {
            eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (surf != EGL_NO_SURFACE) eglDestroySurface(dpy, surf);
            if (ctx != EGL_NO_CONTEXT) eglDestroyContext(dpy, ctx);
            eglTerminate(dpy);
        }
        dpy = EGL_NO_DISPLAY; ctx = EGL_NO_CONTEXT; surf = EGL_NO_SURFACE;
    }

    // 全屏三角形 + 采样 uTex 的 blit 程序
    bool initBlit(Out &o) {
        const char *vs = "#version 300 es\nout vec2 uv;\nvoid main(){ vec2 p = vec2((gl_VertexID<<1)&2, gl_VertexID&2); uv = p; gl_Position = vec4(p*2.0-1.0, 0.0, 1.0); }\n";
        const char *fs = "#version 300 es\nprecision highp float;\nin vec2 uv;\nuniform sampler2D uTex;\nout vec4 o;\nvoid main(){ o = texture(uTex, uv); }\n";
        auto mk = [&](GLenum t, const char *src) {
            GLuint s = glCreateShader(t);
            glShaderSource(s, 1, &src, nullptr);
            glCompileShader(s);
            GLint ok = 0;
            glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
            if (!ok) { char b[512]; glGetShaderInfoLog(s, sizeof b, nullptr, b); o.line("着色器编译失败: %s", b); }
            return s;
        };
        GLuint v = mk(GL_VERTEX_SHADER, vs), f = mk(GL_FRAGMENT_SHADER, fs);
        prog = glCreateProgram();
        glAttachShader(prog, v);
        glAttachShader(prog, f);
        glLinkProgram(prog);
        GLint ok = 0;
        glGetProgramiv(prog, GL_LINK_STATUS, &ok);
        glDeleteShader(v);
        glDeleteShader(f);
        if (!ok) { o.line("GL 程序链接失败"); return false; }
        uTexLoc = glGetUniformLocation(prog, "uTex");
        glGenVertexArrays(1, &vao);
        return true;
    }

    void blit(GLuint srcTex, int w, int h) {
        glViewport(0, 0, w, h);
        glUseProgram(prog);
        glBindVertexArray(vao);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, srcTex);
        glUniform1i(uTexLoc, 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }

    // 把 AHB 绑成 GL 纹理(GL_TEXTURE_2D);返回 EGLImage(用完 destroyImage)
    EGLImageKHR bindAhb(AHardwareBuffer *b, GLuint tex) {
        EGLClientBuffer cb = getClientBuffer(b);
        const EGLint attrs[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        EGLImageKHR img = createImage(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, attrs);
        if (img == EGL_NO_IMAGE_KHR) return img;
        glBindTexture(GL_TEXTURE_2D, tex);
        imageTarget(GL_TEXTURE_2D, reinterpret_cast<GLeglImageOES>(img));
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return img;
    }
};

// 与主程序一致的 AHB:R8G8B8A8,GPU 采样 + GPU 颜色输出(extra 用于需要 CPU 读写的校验)
inline AHardwareBuffer *allocAhb(uint32_t w, uint32_t h, uint64_t extra = 0, uint32_t format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM) {
    AHardwareBuffer_Desc d{};
    d.width = w;
    d.height = h;
    d.layers = 1;
    d.format = format;
    d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | extra;
    AHardwareBuffer *b = nullptr;
    if (AHardwareBuffer_allocate(&d, &b) != 0) return nullptr;
    return b;
}
