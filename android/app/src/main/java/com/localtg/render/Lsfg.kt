package com.localtg.render

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import com.localtg.AppLog
import java.io.File

/**
 * LSFG(Lossless Scaling 帧生成)的原生接口(见 cpp/lsfg/lsfg_bridge.cpp)。
 * 原生库只有运行过 tools\setup-lsfg.ps1 之后才会编进 APK;没编进去 / 设备不支持时 [load] 返回 false,调用方退回其它补帧方式。
 */
object LsfgNative {
    @Volatile private var loaded: Boolean? = null

    fun load(): Boolean {
        loaded?.let { return it }
        val ok = try { System.loadLibrary("lsfgbridge"); true } catch (e: Throwable) { AppLog.w("lsfg", "LSFG 原生库没有加载:${e.message}"); false }
        loaded = ok
        return ok
    }

    @JvmStatic external fun nativeExtract(dll: String, cache: String): Int
    @JvmStatic external fun nativeStart(cache: String, w: Int, h: Int, generated: Int, flowScale: Float, perf: Boolean, preferFp16: Boolean): Int
    @JvmStatic external fun nativeVariant(): String
    @JvmStatic external fun nativeBind(texIds: IntArray): Int
    @JvmStatic external fun nativePresent(): Int
    @JvmStatic external fun nativeStop()
    @JvmStatic external fun nativeLastError(): String
}

/**
 * Lossless.dll 的准备:安装包的 assets 里内置了你自己的 Lossless.dll(仅个人使用,不分发)。
 * 第一次用时把它复制到应用私有目录,提取里面的着色器(DXBC → SPIR-V + 现成的 FP32 SPIR-V)缓存起来,
 * 之后每次启动只读缓存。提取在后台线程做,期间补帧先用别的方式。
 */
object Lsfg {
    private const val MARKER = "extracted-v1"

    /**
     * 补帧(LSFG)在所有带 Vulkan 的 arm64 设备上启用。早先"非高通一律禁用"是误判。
     * 天玑 / Mali 上运动画面重影、扭曲的真正原因是 Mali 驱动对"显式 LOD 采样 + 常量纹素偏移"算错(静止画面正常),
     * 原生层加载着色器时已自动改写绕开(lsfg_bridge.cpp / spvpatch.cpp);2026-10-08 在天玑 9500 上和 Intel GPU 用真值片段逐级比对验证,见 docs/10。
     */
    val deviceSupported: Boolean by lazy {
        val mf = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER.orEmpty() else ""
        val model = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL.orEmpty() else ""
        val hw = android.os.Build.HARDWARE.orEmpty().lowercase()
        AppLog.i("lsfg", "处理器检测:厂商=$mf 型号=$model 硬件=$hw → 允许补帧")
        true
    }
    const val UNSUPPORTED_TEXT = "检测到非高通处理器,补帧已禁用(补帧只在骁龙 / Adreno 上验证过,天玑 / Mali 等性能跟不上)"

    /** 0 还没准备 / 1 准备中 / 2 就绪 / 3 不可用(原因见 [error]) */
    @Volatile var state = 0; private set
    @Volatile var error = ""; private set

    fun dir(ctx: Context) = File(ctx.filesDir, "lsfg")
    fun cacheDir(ctx: Context): String = File(dir(ctx), "cache").path

    /** 安装包(assets)里有没有内置 Lossless.dll */
    fun dllInAssets(ctx: Context): Boolean = runCatching { ctx.assets.list("lsfg")?.contains("Lossless.dll") == true }.getOrDefault(false)

    /**
     * 应用私有目录里保存的 Lossless.dll 副本。带 DLL 的安装包第一次启动时把它复制到这里并**一直保留**:
     * 通过「检查更新」装上发布版(发布版不内置 DLL,版权原因不公开分发)之后,应用的私有数据会保留,DLL 还在,
     * 需要重新提取着色器时(「重新提取着色器」)照样能用。卸载应用才会丢。
     */
    fun dllFile(ctx: Context) = File(dir(ctx), "Lossless.dll")

    /** 有可用的 DLL:安装包里有,或私有目录里留着副本。 */
    fun dllBundled(ctx: Context): Boolean = dllInAssets(ctx) || dllFile(ctx).let { it.isFile && it.length() > 0 }

    /** 安装包里带着 DLL 时,确保私有目录里有一份一样的副本(只在没有或大小不同时复制)。后台线程调用。 */
    fun keepDll(ctx: Context) {
        runCatching {
            if (!dllInAssets(ctx)) return
            val dst = dllFile(ctx)
            // assets 里的文件是压缩存放的,openFd 打不开,流式数一遍字节数(几 MB,后台线程,毫秒级)
            var len = 0L
            ctx.assets.open("lsfg/Lossless.dll").use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; len += n } }
            if (dst.isFile && dst.length() == len) return
            dir(ctx).mkdirs()
            val tmp = File(dir(ctx), "Lossless.dll.tmp")
            ctx.assets.open("lsfg/Lossless.dll").use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(dst)
            AppLog.i("lsfg", "已把 Lossless.dll 保存到应用私有目录(更新应用后仍然保留)")
        }.onFailure { AppLog.w("lsfg", "保存 Lossless.dll 副本失败:${it.message}") }
    }

    /** 着色器缓存已经提取好了(发布版安装包不内置 Lossless.dll,但之前用带 DLL 的版本提取过的手机,更新后补帧照常可用)。 */
    fun cacheReady(ctx: Context): Boolean = File(dir(ctx), MARKER).exists() && File(dir(ctx), "cache").isDirectory

    fun prepareAsync(ctx: Context) {
        synchronized(this) {
            if (state != 0) return
            state = 1
        }
        val app = ctx.applicationContext
        Thread({
            val err = prepare(app)
            error = err.orEmpty()
            state = if (err == null) 2 else 3
            AppLog.i("lsfg", "LSFG 准备完成:${if (err == null) "就绪" else "不可用 — $err"}")
        }, "lsfg-prepare").start()
    }

    /** 清掉着色器缓存,下次重新从 DLL 提取。 */
    fun reset(ctx: Context) {
        synchronized(this) { state = 0; error = "" }
        runCatching { File(dir(ctx), MARKER).delete(); File(cacheDir(ctx)).deleteRecursively() }
    }

    private fun prepare(ctx: Context): String? {
        if (!LsfgNative.load()) return "原生库没有加载(安装包没有编进 LSFG 原生部分,或设备架构不支持)"
        val d = dir(ctx)
        val marker = File(d, MARKER)
        val cache = File(d, "cache")
        if (marker.exists() && cache.isDirectory) return null
        if (!dllBundled(ctx)) return "没有 Lossless.dll(安装包里没有,应用私有目录里也没有保存过;先用 tools\\setup-lsfg.ps1 打一个带 DLL 的安装包装一次)"
        d.mkdirs()
        keepDll(ctx)
        val dll = dllFile(ctx)
        try {
            if (!dll.isFile) return "找不到 Lossless.dll"
            cache.deleteRecursively()
            cache.mkdirs() // 原生提取不会创建最外层缓存目录
            val rc = LsfgNative.nativeExtract(dll.path, cache.path)
            if (rc != 0) return "提取着色器失败(代码 $rc):${LsfgNative.nativeLastError()}"
            marker.writeText("ok")
            return null
        } catch (e: Throwable) {
            return "提取着色器出错:${e.message}"
        }
    }
}

/**
 * 一个帧生成会话(GL 渲染线程上使用):
 *  - 两个输入纹理(AHB 绑定成 GL 纹理 + FBO):第 N 个真实帧渲染进槽 N % 2;
 *  - generated 个输出纹理(AHB 绑定成 GL 纹理):presentContext 后里面是 前一帧 → 当前帧 之间的中间帧。
 */
class LsfgSession(val w: Int, val h: Int, val generated: Int, val key: String) {
    private val inTex = IntArray(2)
    private val inFbo = IntArray(2)
    private val outTex = IntArray(generated)
    private var started = false
    var counter = 0L

    fun create(cacheDir: String, flowScale: Float, perf: Boolean, fp16: Boolean): Boolean {
        val rc = LsfgNative.nativeStart(cacheDir, w, h, generated, flowScale, perf, fp16)
        if (rc != 0) { AppLog.w("lsfg", "帧生成启动失败(代码 $rc):${LsfgNative.nativeLastError()}"); return false }
        started = true
        val all = IntArray(2 + generated)
        GLES30.glGenTextures(all.size, all, 0)
        // AHB → EGLImage → 这几张 GL 纹理
        val b = LsfgNative.nativeBind(all)
        if (b != 0) { AppLog.w("lsfg", "绑定 AHB 到 GL 纹理失败(代码 $b):${LsfgNative.nativeLastError()}"); GLES30.glDeleteTextures(all.size, all, 0); return false }
        for (t in all) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        }
        all.copyInto(inTex, 0, 0, 2)
        all.copyInto(outTex, 0, 2, 2 + generated)
        val fb = IntArray(2)
        GLES30.glGenFramebuffers(2, fb, 0)
        for (i in 0..1) {
            inFbo[i] = fb[i]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, inTex[i], 0)
            val st = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
            if (st != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                AppLog.w("lsfg", "输入 AHB 作为渲染目标不完整 0x${Integer.toHexString(st)}")
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
                return false
            }
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return true
    }

    /** 把一张纹理(RGBA16F)用 blit 程序画进当前输入槽,返回槽号。 */
    fun writeInput(blit: Int, src: Int): Int {
        val slot = (counter % 2).toInt()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, inFbo[slot])
        GLES30.glViewport(0, 0, w, h)
        Gl.use(blit)
        Gl.bindTex(0, src)
        GLES20.glUniform1i(Gl.loc(blit, "uTex"), 0)
        Gl.draw()
        GLES30.glFinish() // GL 写完,Vulkan 才能安全地读这块 AHB
        return slot
    }

    /** 第 i 张输出(AHB 绑定的 GL 纹理 id)。 */
    fun output(i: Int) = outTex[i]

    fun destroy() {
        if (started) {
            runCatching { LsfgNative.nativeStop() }
            started = false
        }
        val all = inTex + outTex
        if (all.any { it != 0 }) GLES30.glDeleteTextures(all.size, all, 0)
        if (inFbo.any { it != 0 }) GLES30.glDeleteFramebuffers(2, inFbo, 0)
        inTex.fill(0); outTex.fill(0); inFbo.fill(0)
    }
}
