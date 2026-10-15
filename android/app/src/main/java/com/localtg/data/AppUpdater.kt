package com.localtg.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.localtg.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 手机端检查更新(2.5.0):设置 → 关于 → 检查更新。手动点击:查 GitHub Releases 最新版本 → 下载 APK(校验 SHA-256)→ 调起系统安装器。
 * 发布约定:Release 里有 LocalBrowse-<版本>.apk 和 SHA256SUMS.txt;版本号取自文件名,和当前 versionName 逐段按数字比较。
 * 网络(2.14.0 / 3.0.0):直连 github.com 和内置的 8 个镜像前缀同时探测,谁先响应用谁(设置里可以指定优先用哪个);
 * 域名解析并发问几个 DoH 服务器(哪个先答用哪个),系统 DNS 兜底;同一个域名解析出多个 IP 时按 TCP 连接耗时选最快的(Watt Toolkit 的思路)。
 * SHA256SUMS.txt 同样先直连;镜像只负责搬字节,APK 的 hash 必须和直连拿到的 SHA256SUMS 一致才会安装。
 * 注意:用自己的 OkHttp 客户端(系统信任的证书),不能用访问服务器的那个 —— 它只信任固定指纹的自签名证书,连不上 GitHub。
 */
object AppUpdater {
    const val REPO = "ack528/MediaHub"

    /** 设置里「优先使用」选这个 = 直连排在最前面。 */
    const val DIRECT = "direct"

    /** 内置的 GitHub 下载镜像前缀(和服务端 config.DefaultUpdateMirrors 一致;都是 2026-10 实测能返回真实文件的)。 */
    val MIRRORS = listOf(
        "https://ghfast.top/", "https://gh-proxy.com/", "https://gh-proxy.org/", "https://ghproxy.net/",
        "https://gh.llkk.cc/", "https://ghm.078465.xyz/", "https://ghfile.geekertao.top/", "https://wget.la/",
    )

    /** 设置里指定的优先下载源(空 = 自动:按最近一次探测的延迟排序)。由 AppContainer 在设置变化时更新。 */
    @Volatile var preferred: String = ""

    /** 镜像前缀 → 最近一次探测的首字节耗时(毫秒);-1 = 探测不通。 */
    val latency = ConcurrentHashMap<String, Long>()

    class Release(val tag: String, val version: String, val notes: String, val apkUrl: String, val sumUrl: String?, val size: Long)

    @Serializable private class GhAsset(val name: String = "", @SerialName("browser_download_url") val url: String = "", val size: Long = 0)
    @Serializable private class GhRelease(@SerialName("tag_name") val tag: String = "", val body: String? = null, val assets: List<GhAsset> = emptyList())

    private val client by lazy {
        OkHttpClient.Builder().dns(UpdateDns).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    }
    private val apkName = Regex("""^LocalBrowse-(\d+(?:\.\d+){1,3})\.apk$""")

    /** a > b 返回正数,相等 0,小于负数("2.10" > "2.9")。 */
    fun compare(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split('.')
        val pb = b.trim().removePrefix("v").split('.')
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun prefixed(mirror: String, url: String) = (if (mirror.endsWith("/")) mirror else "$mirror/") + url

    /** 镜像按最近一次探测的延迟排序:通的按耗时升序,没测过的保持内置顺序排在其后,测过不通的排最后。 */
    private fun sortedMirrors(): List<String> {
        val valid = MIRRORS.filter { !it.contains("github.com") }
        fun rank(m: String): Long = when (val v = latency[m]) { null -> 5_000L; in Long.MIN_VALUE..-1L -> Long.MAX_VALUE; else -> v }
        return valid.sortedBy { rank(it) } // sortedBy 是稳定排序
    }

    /**
     * 一个 GitHub 地址的全部候选(按尝试顺序)。设置里指定了优先源就排最前面(选「直连」= 直连最前);
     * 否则直连在前,然后是各镜像按延迟排序。
     */
    fun candidates(url: String): List<String> {
        val ms = sortedMirrors()
        val pref = preferred
        val out = mutableListOf<String>()
        if (pref.isNotEmpty() && pref != DIRECT && pref in ms) out += prefixed(pref, url)
        out += url
        ms.forEach { out += prefixed(it, url) }
        return out.distinct()
    }

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "LocalBrowse-Updater").header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body.string()
        }
    }

    /** 依次尝试候选地址,返回第一个成功的内容(用于 SHA256SUMS.txt 这类小文件)。 */
    private fun getAny(url: String): String {
        var last: Exception? = null
        for (u in candidates(url)) {
            try { return get(u) } catch (e: Exception) { last = e; AppLog.w("update", "取 $u 失败,换下一个", e) }
        }
        throw last ?: IOException("没有可用的地址")
    }

    /**
     * 不走 GitHub API(匿名每小时 60 次,手机网络共用出口 IP 时容易被限流返回 403):github.com/<repo>/releases/latest 会 302 到 .../releases/tag/<tag>,
     * 再取这个 tag 下的 SHA256SUMS.txt,里面每行 "<sha256>  <文件名>" 就是这次发布的全部资源。没有更新说明。prefix 为镜像前缀(空 = 直连)。
     */
    private fun viaRedirect(prefix: String): GhRelease {
        val noRedirect = client.newBuilder().followRedirects(false).build()
        val loc = noRedirect.newCall(Request.Builder().url("${prefix}https://github.com/$REPO/releases/latest").head().build()).execute().use { it.header("Location") }
            ?: throw IOException("没有取到最新发布的标签")
        val tag = loc.substringAfter("/releases/tag/", "")
        if (tag.isEmpty()) throw IOException("没有取到最新发布的标签")
        val base = "https://github.com/$REPO/releases/download/$tag/"
        val sums = get(prefix + base + "SHA256SUMS.txt")
        val assets = mutableListOf(GhAsset("SHA256SUMS.txt", base + "SHA256SUMS.txt", 0))
        for (line in sums.lineSequence()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size >= 2) { val n = f.last().removePrefix("*"); assets += GhAsset(n, base + n, 0) }
        }
        return GhRelease(tag, null, assets)
    }

    /** 最新发布:API → 直连跳转 → 各镜像跳转。发布里没有 APK 返回 null。 */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val r = try {
            AppJson.decodeFromString<GhRelease>(get("https://api.github.com/repos/$REPO/releases/latest"))
        } catch (e: Exception) {
            AppLog.i("update", "GitHub API 不可用(${e.message}),改用 releases/latest 跳转")
            var rel: GhRelease? = null
            for (p in listOf("") + sortedMirrors()) {
                try { rel = viaRedirect(if (p.isEmpty() || p.endsWith("/")) p else "$p/"); break } catch (ex: Exception) { AppLog.w("update", "跳转 $p 失败", ex) }
            }
            rel ?: throw e
        }
        var apk: GhAsset? = null
        var ver = ""
        for (a in r.assets) apkName.find(a.name)?.let { apk = a; ver = it.groupValues[1] }
        val a = apk ?: return@withContext null
        Release(r.tag, ver, r.body.orEmpty().trim().take(1500), a.url, r.assets.firstOrNull { it.name == "SHA256SUMS.txt" }?.url, a.size)
    }

    /**
     * 同时对所有候选地址发 1 字节的 Range 请求,谁先返回 200 / 206 就用谁;全部失败返回第一个(下面按顺序回退)。
     */
    private suspend fun race(urls: List<String>): String = coroutineScope {
        val ch = Channel<String?>(urls.size)
        val jobs = urls.map { u ->
            launch(Dispatchers.IO) {
                val ok = try {
                    withTimeoutOrNull(8_000) {
                        client.newCall(Request.Builder().url(u).header("Range", "bytes=0-0").header("User-Agent", "LocalBrowse-Updater").build())
                            .execute().use { r: Response -> r.isSuccessful || r.code == 206 }
                    } == true
                } catch (e: Exception) { false }
                ch.send(if (ok) u else null)
            }
        }
        var winner: String? = null
        repeat(urls.size) {
            if (winner == null) ch.receive()?.let { winner = it; jobs.forEach { j -> j.cancel() } }
        }
        winner ?: urls.first()
    }

    /** 下载并校验,返回本地文件。onProgress(已下载, 总大小)在 IO 线程调用。 */
    suspend fun download(ctx: Context, rel: Release, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val sumUrl = rel.sumUrl ?: throw IOException("发布里没有 SHA256SUMS.txt,无法校验,已放弃")
        val name = "LocalBrowse-${rel.version}.apk"
        val want = getAny(sumUrl).lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it.last().removePrefix("*").equals(name, true) }?.first()?.lowercase()
            ?: throw IOException("SHA256SUMS.txt 里没有 $name")
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, name)
        // 直连 + 镜像同时探测,先响应的先下(设置里指定了优先源就先用它);失败再按顺序试其余的。hash 不对就删掉、换下一个。
        val urls = candidates(rel.apkUrl)
        val first = if (preferred.isNotEmpty()) urls.first() else race(urls)
        val ordered = listOf(first) + urls
        var lastErr: Exception? = null
        for (u in ordered.distinct()) {
            try {
                AppLog.i("update", "下载 $u")
                fetchTo(u, out, want, onProgress)
                AppLog.i("update", "已下载并校验 ${out.name}(${out.length() / 1024} KB)")
                return@withContext out
            } catch (e: Exception) {
                lastErr = e
                AppLog.w("update", "下载失败,换下一个地址: $u", e)
                out.delete()
            }
        }
        throw lastErr ?: IOException("下载失败")
    }

    private fun fetchTo(url: String, out: File, want: String, onProgress: (Long, Long) -> Unit) {
        val md = MessageDigest.getInstance("SHA-256")
        client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(url).header("User-Agent", "LocalBrowse-Updater").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("下载失败 HTTP ${r.code}")
                val total = r.body.contentLength()
                var done = 0L
                r.body.byteStream().use { ins ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(128 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n); md.update(buf, 0, n)
                            done += n
                            onProgress(done, total)
                        }
                    }
                }
            }
        val got = md.digest().joinToString("") { "%02x".format(it) }
        if (got != want) throw IOException("校验和不一致(下载损坏或被篡改)")
    }

    /** 调起系统安装器。没有"安装未知应用"权限时先跳到系统设置里让用户打开,返回 false(用户开了权限后再点一次)。 */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.logs", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    // ---------------------------------------------------------------- 探测(设置 → 关于 → 下载源与 DNS)

    /** 一个下载源(直连或镜像)的探测结果。prefix 为空 = 直连。ms < 0 且 ok = false 表示不通;testing = 正在测。 */
    data class SourceStatus(val name: String, val prefix: String, val ok: Boolean, val ms: Long, val note: String = "", val testing: Boolean = false)

    /**
     * 同时探测直连和所有镜像:对最新发布的 SHA256SUMS.txt 发 Range 请求,量首字节耗时,并检查返回的是不是文件内容(镜像站的拦截页是 HTML,不算通)。
     * 每测完一个就回调一次(界面实时刷新);结果同时记下来用于排序。
     */
    suspend fun probeSources(onUpdate: (List<SourceStatus>) -> Unit): List<SourceStatus> = coroutineScope {
        val probe = "https://github.com/$REPO/releases/latest/download/SHA256SUMS.txt"
        val srcs = listOf("" to "直连 github.com") + MIRRORS.filter { !it.contains("github.com") }.map { it to it.removePrefix("https://").trimEnd('/') }
        val res = java.util.Collections.synchronizedList(srcs.map { SourceStatus(it.second, it.first, false, 0, testing = true) }.toMutableList())
        fun push() = onUpdate(synchronized(res) { res.toList() })
        push()
        srcs.mapIndexed { i, (prefix, name) ->
            async(Dispatchers.IO) {
                val t0 = System.nanoTime()
                val st = try {
                    val req = Request.Builder().url(if (prefix.isEmpty()) probe else prefixed(prefix, probe)).header("Range", "bytes=0-63").header("User-Agent", "LocalBrowse-Updater").build()
                    withTimeoutOrNull(10_000) {
                        client.newCall(req).execute().use { r ->
                            val head = r.body.source().readUtf8Line().orEmpty().trim()
                            val ms = (System.nanoTime() - t0) / 1_000_000
                            if ((r.code == 200 || r.code == 206) && head.length >= 32 && head.take(32).all { it in "0123456789abcdefABCDEF" }) SourceStatus(name, prefix, true, ms)
                            else SourceStatus(name, prefix, false, -1, "返回的不是文件内容(HTTP ${r.code})")
                        }
                    } ?: SourceStatus(name, prefix, false, -1, "超时")
                } catch (e: Exception) {
                    SourceStatus(name, prefix, false, -1, e.javaClass.simpleName + (e.message?.let { ": ${it.take(40)}" } ?: ""))
                }
                if (prefix.isNotEmpty()) latency[prefix] = if (st.ok) st.ms else -1L
                res[i] = st
                push()
            }
        }.awaitAll()
        res.toList().sortedWith(compareBy({ !it.ok }, { if (it.ok) it.ms else Long.MAX_VALUE }))
    }

    /** 一个 DNS 的探测结果:解析 github.com 的耗时和得到的地址。 */
    data class DnsStatus(val name: String, val ok: Boolean, val ms: Long, val ips: List<String> = emptyList(), val note: String = "", val testing: Boolean = false)

    /** 逐个探测内置的 DoH 服务器和系统 DNS(各自解析 github.com 并计时),再看最终会连哪个 IP(多个地址里 TCP 连接最快的)。 */
    suspend fun probeDns(onUpdate: (List<DnsStatus>) -> Unit): List<DnsStatus> = coroutineScope {
        val names = UpdateDns.serverNames() + "系统 DNS"
        val res = java.util.Collections.synchronizedList(names.map { DnsStatus(it, false, 0, testing = true) }.toMutableList())
        fun push() = onUpdate(synchronized(res) { res.toList() })
        push()
        names.mapIndexed { i, name ->
            async(Dispatchers.IO) {
                val t0 = System.nanoTime()
                val st = try {
                    val ips = if (name == "系统 DNS") Dns.SYSTEM.lookup("github.com").mapNotNull { it.hostAddress } else UpdateDns.queryServer(name, "github.com").mapNotNull { it.hostAddress }
                    DnsStatus(name, ips.isNotEmpty(), (System.nanoTime() - t0) / 1_000_000, ips)
                } catch (e: Exception) {
                    DnsStatus(name, false, -1, note = e.javaClass.simpleName)
                }
                res[i] = st
                push()
            }
        }.awaitAll()
        res.toList().sortedWith(compareBy({ !it.ok }, { if (it.ok) it.ms else Long.MAX_VALUE }))
    }
}

/**
 * 更新专用的 DNS:
 *  - 并发问内置的几个 DoH 服务器(阿里 DNS / DNSPod / Cloudflare / Google / AdGuard / dns.sb),哪个先答用哪个;全部失败再用系统 DNS
 *    (DoH 优先是因为系统 DNS 在部分网络里会返回被污染的地址而不报错)。DoH 服务器自己的域名用固定 IP 连接(bootstrap),证书仍按域名校验;
 *  - 同一个域名解析出多个 IP 时,并发测 TCP 443 连接耗时,最快的排最前(Watt Toolkit 的思路:同一个域名选最近的地址);
 *  - 结果缓存 10 分钟;解析都失败时用上次成功的结果。
 */
private object UpdateDns : Dns {
    // 名称、bootstrap IP、JSON 接口路径(各家不同)
    private val servers = listOf(
        Triple("dns.alidns.com", "223.5.5.5", "/resolve"),
        Triple("doh.pub", "1.12.12.12", "/dns-query"),
        Triple("cloudflare-dns.com", "1.1.1.1", "/dns-query"),
        Triple("dns.google", "8.8.8.8", "/resolve"),
        Triple("dns.adguard-dns.com", "94.140.14.14", "/resolve"),
        Triple("doh.dns.sb", "185.222.222.222", "/dns-query"),
    )
    private val bootstrap = servers.associate { it.first to it.second }
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "doh").apply { isDaemon = true } }
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns { h -> bootstrap[h]?.let { listOf(InetAddress.getByName(it)) } ?: Dns.SYSTEM.lookup(h) }
            .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    }
    private val cache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()

    fun serverNames(): List<String> = servers.map { it.first }

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.takeIf { System.currentTimeMillis() - it.second < 10 * 60_000L }?.let { return it.first }
        // 并发问所有 DoH:谁先返回非空结果用谁
        var ips: List<InetAddress> = emptyList()
        try {
            val cs = ExecutorCompletionService<List<InetAddress>>(pool)
            val futures = servers.map { s -> cs.submit(java.util.concurrent.Callable { queryServer(s.first, hostname) }) }
            val deadline = System.currentTimeMillis() + 6_000
            repeat(servers.size) {
                if (ips.isNotEmpty()) return@repeat
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return@repeat
                val f = cs.poll(left, TimeUnit.MILLISECONDS) ?: return@repeat
                runCatching { f.get() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { ips = it }
            }
            futures.forEach { it.cancel(true) }
        } catch (e: Exception) {
            AppLog.w("net", "DoH 解析 $hostname 失败", e)
        }
        if (ips.isEmpty()) {
            try {
                ips = Dns.SYSTEM.lookup(hostname)
            } catch (e: UnknownHostException) {
                val old = cache[hostname]
                if (old != null && System.currentTimeMillis() - old.second < 10 * 60_000L) return old.first
                throw e
            }
        }
        ips = orderByLatency(ips)
        cache[hostname] = ips to System.currentTimeMillis()
        return ips
    }

    /** 问指定的一个 DoH 服务器(Google 风格的 JSON 接口,type=A)。 */
    fun queryServer(server: String, host: String): List<InetAddress> {
        val path = servers.firstOrNull { it.first == server }?.third ?: "/resolve"
        val req = Request.Builder().url("https://$server$path?name=$host&type=A").header("Accept", "application/dns-json").build()
        dohClient.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val j = JSONObject(r.body.string())
            if (j.optInt("Status", -1) != 0) return emptyList()
            val answers = j.optJSONArray("Answer") ?: return emptyList()
            val out = mutableListOf<InetAddress>()
            for (i in 0 until answers.length()) {
                val a = answers.getJSONObject(i)
                if (a.optInt("type") == 1) out += InetAddress.getByName(a.getString("data")) // A 记录;字面 IP,不会触发再次解析
            }
            return out
        }
    }

    /** 多个 IP 时并发测 TCP 443 的连接耗时,最快的排最前;连不上的排最后。 */
    private fun orderByLatency(ips: List<InetAddress>): List<InetAddress> {
        if (ips.size < 2) return ips
        val tasks = ips.map { ip ->
            pool.submit(java.util.concurrent.Callable<Pair<InetAddress, Long>> {
                val t0 = System.nanoTime()
                val ms = try { Socket().use { it.connect(InetSocketAddress(ip, 443), 1500) }; (System.nanoTime() - t0) / 1_000_000 } catch (e: Exception) { Long.MAX_VALUE }
                ip to ms
            })
        }
        val timed = tasks.map { runCatching { it.get(2, TimeUnit.SECONDS) }.getOrNull() ?: (ips[tasks.indexOf(it)] to Long.MAX_VALUE) }
        return timed.sortedBy { it.second }.map { it.first }
    }
}
