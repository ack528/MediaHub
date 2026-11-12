package com.localtg.data

import android.content.Context
import android.os.StatFs
import com.localtg.AppLog
import java.io.File
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * 本地缓存的统一预算和智能分配(设置 → 存储与缓存 → 本地缓存上限,默认也是最大 3GB)。
 *
 * 以前四块缓存各管各的(网页缩略图 512MB、查看器大图 512MB、视频 1GB、接口数据 24MB),互相不知道对方占了多少,
 * 加起来可能超过 2GB,也不管手机还剩多少空间。现在由这里统一给每一块定上限:
 *
 *  1. 总额 = 设置的上限(≤ 3GB),但手机剩余空间不够时收缩:至少给系统留 max(1GB, 总容量 10%),最少 256MB。
 *  2. 接口数据固定 32MB;缩略图 / 大图 / 视频各有保底,剩下的按"需求"分:
 *     需求 = 最近从网络下载了多少这一类的数据(半衰期 14 天,不常用的一类会慢慢让出空间),取平方根
 *     (视频动辄几百 MB,不开根号的话看几个视频就把缩略图的空间全挤掉了,而缩略图才是反复翻看最常命中的)。
 *  3. 每一类有封顶,超过封顶的部分分给其它类。
 *
 * 各缓存的大小在创建时就定了(Coil / OkHttp / ExoPlayer 的缓存都不能运行中改上限),所以分配在应用启动时算一次;
 * 新的上限比现有占用小时,各缓存打开时会自己按 LRU 删到上限以内。
 */
object CacheBudget {
    enum class Kind(val key: String, val label: String) {
        Thumb("thumb", "缩略图"), Image("image", "查看器图片"), Video("video", "视频"), Api("api", "列表数据"),
    }

    const val MAX_MB = 3072
    private const val MB = 1024L * 1024
    private const val HALF_LIFE_MS = 14L * 24 * 3600 * 1000
    private const val API_BYTES = 32 * MB

    /** 保底 / 封顶 / 没有历史时的先验需求(字节) */
    private val floor = mapOf(Kind.Thumb to 192 * MB, Kind.Image to 128 * MB, Kind.Video to 256 * MB)
    private val cap = mapOf(Kind.Thumb to 1024 * MB, Kind.Image to 1024 * MB, Kind.Video to 2304 * MB)
    private val prior = mapOf(Kind.Thumb to 120.0 * MB, Kind.Image to 80.0 * MB, Kind.Video to 160.0 * MB)

    class Plan(
        val limit: Long,            // 设置的上限
        val total: Long,            // 实际总额(剩余空间不够时比 limit 小)
        val quota: Map<Kind, Long>, // 每一类的上限
        val demand: Map<Kind, Double>,
        val free: Long, val storage: Long,
    ) {
        operator fun get(k: Kind): Long = quota[k] ?: 0L
    }

    @Volatile var current: Plan = Plan(MAX_MB * MB, MAX_MB * MB,
        mapOf(Kind.Thumb to 512 * MB, Kind.Image to 512 * MB, Kind.Video to 1024 * MB, Kind.Api to API_BYTES), emptyMap(), 0, 0)
        private set

    // ---------------------------------------------------------------- 需求统计

    private val pending = AtomicLongArray(Kind.entries.size)

    /** 记一笔"这一类从网络下载了 n 字节"(缓存没命中才会走网络)。很便宜,随时可调。 */
    fun record(kind: Kind, bytes: Long) { if (bytes > 0) pending.addAndGet(kind.ordinal, bytes) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cache_budget", Context.MODE_PRIVATE)

    /** 衰减后的需求(含还没写盘的部分)。 */
    @Synchronized
    fun demand(ctx: Context): Map<Kind, Double> {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val decay = 0.5.pow((now - p.getLong("at", now)).coerceAtLeast(0).toDouble() / HALF_LIFE_MS)
        return Kind.entries.associateWith { k -> java.lang.Double.longBitsToDouble(p.getLong(k.key, 0L)) * decay + pending.get(k.ordinal) }
    }

    /** 把内存里累积的需求写盘(应用退到后台 / 定时调用)。 */
    @Synchronized
    fun flush(ctx: Context) {
        val d = demand(ctx)
        for (k in Kind.entries) pending.set(k.ordinal, 0)
        prefs(ctx).edit().apply {
            for ((k, v) in d) putLong(k.key, java.lang.Double.doubleToRawLongBits(v))
            putLong("at", System.currentTimeMillis())
        }.apply()
    }

    // ---------------------------------------------------------------- 分配

    fun dir(ctx: Context, k: Kind): File = File(ctx.cacheDir, when (k) { Kind.Thumb -> "web-media"; Kind.Image -> "img"; Kind.Video -> "video"; Kind.Api -> "api" })

    fun usage(ctx: Context, k: Kind): Long = dir(ctx, k).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** 按设置和需求算出这次运行的分配,存到 [current]。应用启动时(创建各缓存之前)调用。 */
    fun plan(ctx: Context, limitMb: Int): Plan {
        val limit = limitMb.coerceIn(256, MAX_MB) * MB
        val st = runCatching { StatFs(ctx.cacheDir.path) }.getOrNull()
        val free = st?.availableBytes ?: Long.MAX_VALUE
        val storage = st?.totalBytes ?: 0L
        // 现有占用用上次后台量好的值(启动时不遍历缓存目录,视频缓存的文件很多),启动后 [measureAsync] 再量一次
        val used = prefs(ctx).getLong("used", 0L)
        val reserve = max(1024 * MB, storage / 10)
        // 现有占用 + 还能用的剩余空间(留出系统需要的部分)
        val total = min(limit, max(256 * MB, used + (free - reserve).coerceAtLeast(0)))
        val d = demand(ctx)
        val q = allocate(total, d)
        val p = Plan(limit, total, q, d, free, storage)
        current = p
        AppLog.i("cache", "本地缓存分配:总额 ${total / MB}MB(上限 ${limit / MB}MB,手机剩余 ${free / MB}MB)" +
            q.entries.joinToString("", prefix = " ·") { (k, v) -> " ${k.label} ${v / MB}MB" } +
            " | 近期需求" + d.entries.joinToString("") { (k, v) -> " ${k.label} ${(v / MB).roundToLong()}MB" })
        return p
    }

    /** 每一类现在实际占了多少(遍历目录,在后台线程调用);顺带记下总占用,下次启动分配时用。 */
    fun measure(ctx: Context): Map<Kind, Long> {
        val m = Kind.entries.associateWith { runCatching { usage(ctx, it) }.getOrDefault(0L) }
        prefs(ctx).edit().putLong("used", m.values.sum()).apply()
        return m
    }

    fun measureAsync(ctx: Context) { Thread({ runCatching { measure(ctx) } }, "cache-measure").apply { priority = Thread.MIN_PRIORITY }.start() }

    /** 纯计算(方便测试):总额 total 怎么分给四类。 */
    internal fun allocate(total: Long, demand: Map<Kind, Double>): Map<Kind, Long> {
        val api = min(API_BYTES, total / 16)
        val kinds = listOf(Kind.Thumb, Kind.Image, Kind.Video)
        val rest0 = total - api
        // 总额太小、连保底都放不下:按保底的比例缩
        val floorSum = kinds.sumOf { floor[it]!! }
        val scale = min(1.0, rest0.toDouble() / floorSum)
        val out = HashMap<Kind, Long>()
        for (k in kinds) out[k] = (floor[k]!! * scale).toLong()
        var rest = rest0 - out.values.sum()
        // 剩下的按 sqrt(需求 + 先验) 分;到封顶的不再参与,多出来的再分给其它类(最多三轮)
        val open = kinds.toMutableSet()
        repeat(3) {
            if (rest <= 0 || open.isEmpty()) return@repeat
            val w = open.associateWith { sqrt((demand[it] ?: 0.0) + prior[it]!!) }
            val sum = w.values.sum().takeIf { it > 0 } ?: return@repeat
            var given = 0L
            for (k in open.toList()) {
                val add = (rest * w[k]!! / sum).toLong()
                val room = cap[k]!! - out[k]!!
                val take = min(add, room).coerceAtLeast(0)
                out[k] = out[k]!! + take
                given += take
                if (take < add) open.remove(k)
            }
            rest -= given
        }
        out[Kind.Api] = api
        return out
    }
}
