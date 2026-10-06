package com.localtg.render

import com.localtg.AppLog

/** 一组数值的 平均 / 最小 / 最大(毫秒)。 */
class Acc {
    var n = 0; private set
    private var sum = 0.0
    private var min = Double.MAX_VALUE
    private var max = -Double.MAX_VALUE

    fun add(v: Double) { n++; sum += v; if (v < min) min = v; if (v > max) max = v }
    fun reset() { n = 0; sum = 0.0; min = Double.MAX_VALUE; max = -Double.MAX_VALUE }
    val avg: Double get() = if (n == 0) 0.0 else sum / n
    val maxV: Double get() = if (n == 0) 0.0 else max
    fun fmt(): String = if (n == 0) "-" else "%.1f/%.1f/%.1f".format(avg, min, max)
}

/**
 * 补帧的详细统计(设置里「补帧详细日志」打开时才记录):每 2 秒写 4 行汇总到日志(标签 frc),
 * 另外在出现异常(丢 vsync、上屏阻塞、LSFG 生成超时…)时立即写一行事件。所有数字都是 平均/最小/最大。
 * 排查"卡顿"时看这几类:
 *   1. 源帧间隔 / 前瞻:视频帧本身来得匀不匀、有没有提前到;
 *   2. vsync 间隔 / 丢 vsync、swap 间隔 / 阻塞:屏幕刷新和上屏有没有掉;
 *   3. 相位 正常 / 重复 / 跳过:上屏的画面序列是不是均匀(重复和跳过就是肉眼的卡顿);
 *   4. LSFG 生成耗时、生成帧晚于区间开始的次数、覆盖率:生成跟不跟得上。
 */
class FrcTrace {
    @Volatile var enabled = false

    // 源帧
    val srcGap = Acc(); val lead = Acc(); val ingestCpu = Acc()
    private var lastSrcTs = 0L

    // vsync / 上屏
    val vsyncGap = Acc(); var vsyncCalls = 0; var vsyncMiss = 0
    val drawCpu = Acc(); val swapBlock = Acc(); val swapGap = Acc(); var swaps = 0; var skipped = 0; var underrun = 0
    private var lastSwapNs = 0L

    // 相位序列(LSFG)
    var selOk = 0; var selDup = 0; var selSkip = 0   // 相邻 vsync 选中的源帧:前进 1 帧 / 重复 / 跳过
    var phOk = 0; var phDup = 0; var phSkip = 0; var phBack = 0; var noGen = 0; var phMissing = 0
    private var lastPh = Long.MIN_VALUE

    // LSFG 管线
    val writeIn = Acc(); val present = Acc(); val doneCost = Acc(); val genAge = Acc(); val genLate = Acc()
    var genLateN = 0; var busyDrops = 0; var presentFail = 0; var sessions = 0

    private var lastFlush = 0L
    private var evCount = 0
    private var evWindow = 0L

    fun src(tsNs: Long, nowNs: Long) {
        if (!enabled) return
        if (lastSrcTs != 0L) srcGap.add((tsNs - lastSrcTs) / 1e6)
        lastSrcTs = tsNs
        lead.add((tsNs - nowNs) / 1e6)
    }

    fun vsync(gapNs: Long, periodNs: Long) {
        if (!enabled) return
        vsyncCalls++
        vsyncGap.add(gapNs / 1e6)
        if (gapNs > periodNs * 18 / 10 && gapNs < 300_000_000L) {
            vsyncMiss++
            event("vsync 间隔异常 %.1fms(期望 %.1fms)".format(gapNs / 1e6, periodNs / 1e6))
        }
    }

    fun swap(drawStartNs: Long, swapStartNs: Long, swapEndNs: Long, periodNs: Long) {
        if (!enabled) return
        swaps++
        drawCpu.add((swapStartNs - drawStartNs) / 1e6)
        val blocked = (swapEndNs - swapStartNs) / 1e6
        swapBlock.add(blocked)
        if (lastSwapNs != 0L) swapGap.add((swapEndNs - lastSwapNs) / 1e6)
        lastSwapNs = swapEndNs
        if (blocked > periodNs / 1e6 * 1.2) event("eglSwapBuffers 阻塞 %.1fms(周期 %.1fms)".format(blocked, periodNs / 1e6))
        if ((swapStartNs - drawStartNs) / 1e6 > periodNs / 1e6 * 0.8) event("draw 本身耗时 %.1fms(周期 %.1fms)".format((swapStartNs - drawStartNs) / 1e6, periodNs / 1e6))
    }

    /** LSFG 上屏相位序列:每个 vsync 一次,和上一次比较。 */
    fun phase(ph: Long) {
        if (!enabled) return
        if (lastPh != Long.MIN_VALUE) {
            when (val d = ph - lastPh) {
                1L -> phOk++
                0L -> phDup++
                else -> if (d < 0) phBack++ else phSkip += (d - 1).toInt()
            }
        }
        lastPh = ph
    }

    fun phaseReset() { lastPh = Long.MIN_VALUE }

    fun sel(delta: Long) {
        if (!enabled) return
        when {
            delta == 1L -> selOk++
            delta == 0L -> selDup++
            delta > 1L -> selSkip += (delta - 1).toInt()
        }
    }

    fun event(msg: String) {
        if (!enabled) return
        val now = System.nanoTime()
        if (now - evWindow > 10_000_000_000L) { evWindow = now; evCount = 0 }
        if (++evCount <= 40) AppLog.i("frc", "! $msg")
        else if (evCount == 41) AppLog.i("frc", "! (10 秒内事件太多,后面的先不逐条写了,看汇总)")
    }

    fun flushDue(nowMs: Long) = enabled && nowMs - lastFlush >= 2000

    /** 写 4 行汇总。header = 配置 / 屏幕 / 源帧率;lsfg = LSFG 的覆盖率等(没有就空)。 */
    fun flush(nowMs: Long, header: String, lsfgExtra: String, isLsfg: Boolean) {
        val secs = if (lastFlush == 0L) 2.0 else (nowMs - lastFlush) / 1000.0
        lastFlush = nowMs
        AppLog.i("frc", "===== %.1fs %s".format(secs, header))
        AppLog.i("frc", "源帧间隔ms ${srcGap.fmt()} | 前瞻ms(帧时间戳-到达) ${lead.fmt()} | ingest CPU ms ${ingestCpu.fmt()}")
        AppLog.i(
            "frc",
            "vsync ${vsyncCalls}次 间隔ms ${vsyncGap.fmt()} 丢${vsyncMiss} | 上屏 ${swaps}次 跳过重画${skipped} swap间隔ms ${swapGap.fmt()} swap阻塞ms ${swapBlock.fmt()} draw CPU ms ${drawCpu.fmt()} | 欠载(没有下一帧)${underrun} | 选帧 前进${selOk} 重复${selDup} 跳过${selSkip}",
        )
        if (isLsfg) {
            AppLog.i(
                "frc",
                "相位 正常${phOk} 重复${phDup} 跳过${phSkip} 倒退${phBack} | 区间无生成帧(退回混合)${noGen} 相位查不到${phMissing} | 写输入ms ${writeIn.fmt()} | 生成(worker)ms ${present.fmt()} 渲染线程收尾ms ${doneCost.fmt()} | " +
                    "生成帧年龄ms ${genAge.fmt()} 晚于区间开始${genLateN}次(晚多少ms ${genLate.fmt()}) | 忙丢帧${busyDrops} 生成失败${presentFail} $lsfgExtra",
            )
        }
        for (a in listOf(srcGap, lead, ingestCpu, vsyncGap, drawCpu, swapBlock, swapGap, writeIn, present, doneCost, genAge, genLate)) a.reset()
        vsyncCalls = 0; vsyncMiss = 0; swaps = 0; skipped = 0; underrun = 0
        selOk = 0; selDup = 0; selSkip = 0
        phOk = 0; phDup = 0; phSkip = 0; phBack = 0; noGen = 0; phMissing = 0
        genLateN = 0; busyDrops = 0; presentFail = 0
    }
}
