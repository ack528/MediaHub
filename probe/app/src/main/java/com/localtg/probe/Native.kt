package com.localtg.probe

/** 原生检测(见 cpp/)。只在 :probe 进程里调用:原生崩溃 / GPU 挂起只会带走那个进程。 */
object Native {
    init { System.loadLibrary("probe") }

    @JvmStatic external fun vkInfo(driver: String): String
    @JvmStatic external fun vkCompute(driver: String): String
    @JvmStatic external fun glInterop(): String
    @JvmStatic external fun extract(dll: String, cache: String): String
    @JvmStatic external fun lsfgBench(
        driver: String, cache: String, frames: String, thumb: String, w: Int, h: Int,
        flow: Float, variant: Int, perf: Boolean, generated: Int, nFrames: Int, iterations: Int, paceMs: Float, dumpPath: String, dumpW: Int, dumpH: Int, flags: Int,
    ): String
}
