package com.localtg.data

/**
 * 网速估计(bps):播放器在"缓冲不够、全速下载"期间量到的下载速度的滑动平均,各个视频共用;测速页量到的稳定速度也会更新它。
 * 用来在开播前判断"这个视频的码率网络扛不扛得住"(经内网穿透 / 云中继时带宽常常比视频码率还小)。
 */
object NetEstimate {
    @Volatile var bps: Long = 0
        private set
    @Volatile private var at = 0L

    /** 播放中量到的一个样本:和已有估计各占一半(估计太久没更新就直接采用新样本)。 */
    fun update(sample: Long) {
        if (sample <= 0) return
        val now = System.currentTimeMillis()
        bps = if (bps == 0L || now - at > 10 * 60_000L) sample else (bps * 0.5 + sample * 0.5).toLong()
        at = now
    }

    /** 测速页量到的稳定速度:比播放中的样本可靠,直接采用。 */
    fun set(measured: Long) { if (measured > 0) { bps = measured; at = System.currentTimeMillis() } }

    /** 换了网络 / 服务器:旧估计作废。 */
    fun reset() { bps = 0; at = 0 }

    /** 估计已经很久没更新(超过 10 分钟)就不信了。 */
    val fresh: Long get() = if (System.currentTimeMillis() - at > 10 * 60_000L) 0 else bps
}

/** 一个视频的原码率(bps):优先用索引里的视频码率,其次文件大小 / 时长;都没有返回 0。 */
fun originalBps(item: Item): Long {
    val k = item.video?.bitrateKbps ?: 0L
    if (k > 0) return k * 1000
    val d = item.durationMs ?: 0L
    return if (item.size > 0 && d > 0) item.size * 8000 / d else 0L
}

/**
 * 在不超过 [limitBps] 的档位里挑最高的;所有档位都比它高返回 null(已经没有更低的了)。
 * 最低档 0.5M 即使网速更低也不会再降(再低的转码画面没法看)。
 */
fun tierAtMost(limitBps: Long, serverMax: Int): Int? {
    val tiers = QualityTiers.tiers(serverMax)
    return tiers.lastOrNull { it <= limitBps }
}

/**
 * 卡顿统计:播放中(出过首帧、不是用户拖动进度)再次进入缓冲算一次卡顿;45 秒内 2 次,或者一次卡了超过 8 秒,就该降档了。
 * 降档后 25 秒内不再触发(给新画质一点时间稳定),一个视频最多自动降 3 次。
 */
class StallTracker {
    private val stalls = ArrayDeque<Long>()
    private var lastSwitch = 0L
    private var steps = 0
    private var stallStart = 0L

    fun onStallStart(now: Long) { stallStart = now; stalls.addLast(now); while (stalls.isNotEmpty() && now - stalls.first() > 45_000) stalls.removeFirst() }

    /** 卡顿结束(恢复播放)。返回这一次卡了多久(毫秒);没有进行中的卡顿返回 0。 */
    fun onStallEnd(now: Long): Long { val d = if (stallStart > 0) now - stallStart else 0L; stallStart = 0; return d }

    /** 当前这一次卡顿已经持续了多久(没有卡顿返回 0)。 */
    fun ongoingMs(now: Long): Long = if (stallStart > 0) now - stallStart else 0L

    /** 现在该不该降档。longStall = 当前这一次卡顿已经持续的时间。 */
    fun shouldStepDown(now: Long, longStallMs: Long): Boolean {
        if (steps >= 3 || now - lastSwitch < 25_000) return false
        return stalls.size >= 2 || longStallMs > 8_000
    }

    fun switched(now: Long) { lastSwitch = now; steps++; stalls.clear() }
    val stepsDone get() = steps
}
