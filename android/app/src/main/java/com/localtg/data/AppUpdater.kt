package com.localtg.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.localtg.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 更新检查并行请求；下载单一写入、断点续传，校验成功后持久保存，实际安装后清理。 */
object AppUpdater {
    const val REPO = "ack528/MediaHub"

    /** 设置里「优先使用」选这个 = 直连排在最前面。 */
    const val DIRECT = "direct"

    /** 内置的 GitHub 下载镜像前缀(和服务端 config.DefaultUpdateMirrors 一致;都是 2026-10 实测能返回真实文件的)。 */
    val MIRRORS = listOf(
        "https://ghfast.top/", "https://gh-proxy.com/", "https://gh-proxy.org/", "https://ghproxy.net/",
        "https://gh.llkk.cc/", "https://ghm.078465.xyz/", "https://ghfile.geekertao.top/", "https://wget.la/",
    )

    /** 设置里指定的优先下载源(空 = 自动:按实测速度)。由 AppContainer 在设置变化时更新。 */
    @Volatile var preferred: String = ""

    /** 镜像前缀 → 最近一次探测的首字节耗时(毫秒);-1 = 探测不通。 */
    val latency = ConcurrentHashMap<String, Long>()

    private const val UA = "LocalBrowse-Updater"
    private const val PROBE = 256 * 1024L

    /** 自己的服务器上缓存好的安装包。 */
    class ServerSrc(val url: String, val version: String, val sha: String, val ready: Boolean, val progress: Int = 0)

    /**
     * 一次发布。[sha] 检查时已经拿到的 SHA-256(从 SHA256SUMS.txt);[server] 不为空 = 版本信息来自自己的服务器。
     */
    class Release(
        val tag: String, val version: String, val notes: String, val apkUrl: String, val sumUrl: String?, val size: Long,
        val sha: String? = null, val server: ServerSrc? = null,
    )

    @Serializable private class GhAsset(val name: String = "", @SerialName("browser_download_url") val url: String = "", val size: Long = 0)
    @Serializable private class GhRelease(@SerialName("tag_name") val tag: String = "", val body: String? = null, val assets: List<GhAsset> = emptyList(), val sums: String? = null)

    private val client by lazy {
        OkHttpClient.Builder().dns(UpdateDns).connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    }
    /** 检查更新、取 SHA256SUMS 这类小请求:一次最多 15 秒。 */
    private val checkClient by lazy { client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }
    /** 下载测速:一个源最多 7 秒。 */
    private val probeClient by lazy { client.newBuilder().callTimeout(7, TimeUnit.SECONDS).build() }
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

    /** OkHttp 的异步调用包成可取消的挂起函数:协程取消时连接也立刻断开(同时发很多个请求、只用最快那个时,其余的要马上停)。 */
    private suspend fun Call.awaitResp(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { if (cont.isActive) cont.resume(response) else response.close() }
        })
    }

    private suspend fun getText(url: String, accept: String? = null): String {
        val b = Request.Builder().url(url).header("User-Agent", UA)
        if (accept != null) b.header("Accept", accept)
        return checkClient.newCall(b.build()).awaitResp().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.body.string()
        }
    }

    /** SHA256SUMS.txt 的内容是不是真的(每行 64 位十六进制 + 文件名;镜像的拦截页 / 错误页是 HTML)。 */
    private fun validSums(text: String): Boolean =
        text.lineSequence().any { l -> val f = l.trim().split(Regex("\\s+")); f.size >= 2 && f[0].length == 64 && f[0].all { it in "0123456789abcdefABCDEF" } }

    /**
     * 不走 GitHub API(匿名每小时 60 次,共用出口 IP 时容易被限流):取 releases/latest/download/SHA256SUMS.txt,
     * 每行 "<sha256>  <文件名>" 就是这次发布的全部资源。prefix 为镜像前缀(空 = 直连)。没有更新说明。
     */
    private suspend fun viaSums(prefix: String): GhRelease {
        val base = "https://github.com/$REPO/releases/latest/download/"
        val sums = getText(prefix + base + "SHA256SUMS.txt")
        if (!validSums(sums)) throw IOException("返回的不是 SHA256SUMS.txt")
        val assets = mutableListOf(GhAsset("SHA256SUMS.txt", base + "SHA256SUMS.txt", 0))
        for (line in sums.lineSequence()) {
            val f = line.trim().split(Regex("\\s+"))
            if (f.size >= 2) { val n = f.last().removePrefix("*"); assets += GhAsset(n, base + n, 0) }
        }
        return GhRelease("latest", null, assets, sums)
    }

    /**
     * 最新发布。[server] 问自己的服务器(返回 null = 服务器不知道 / 不支持);它和 GitHub 同时开始,服务器知道就用服务器的。
     * 发布里没有 APK 返回 null。
     */
    suspend fun latest(server: (suspend () -> Release?)? = null, installed: String = "0"): Release? = coroutineScope {
        val results = Channel<Result<Release?>>(Channel.UNLIMITED)
        val jobs = mutableListOf(launch { results.send(runCatching { latestGithub() }) })
        if (server != null) jobs += launch {
            results.send(runCatching { withTimeoutOrNull(3500) { server() } })
        }
        var best: Release? = null
        var failure: Throwable? = null
        try {
            repeat(jobs.size) {
                val result = results.receive()
                failure = result.exceptionOrNull() ?: failure
                result.getOrNull()?.let { if (best == null || compare(it.version, best!!.version) > 0) best = it }
                if (best != null && compare(best!!.version, installed) > 0) return@coroutineScope best
            }
            if (best == null && failure != null) throw failure!!
            best
        } finally { jobs.forEach { it.cancel() } }
    }

    /** GitHub API、直连和每个镜像同时问,谁先拿到用谁;API 的结果带更新说明,跳转 / 镜像先到时再给 API 最多 2 秒。 */
    private suspend fun latestGithub(): Release? = coroutineScope {
        val tasks = buildList<Pair<Boolean, suspend () -> GhRelease>> {
            add(true to suspend { AppJson.decodeFromString<GhRelease>(getText("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json")) })
            add(false to suspend { viaSums("") })
            sortedMirrors().forEach { m -> add(false to suspend { viaSums(m) }) }
        }
        val ch = Channel<Pair<Boolean, GhRelease?>>(Channel.UNLIMITED)
        val t0 = System.nanoTime()
        val jobs = tasks.mapIndexed { i, (api, t) ->
            launch(Dispatchers.IO) {
                val r = try { t() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    AppLog.i("update", "检查更新:${if (api) "GitHub API" else if (i == 1) "直连" else sortedMirrors().getOrNull(i - 2)} 失败(${e.javaClass.simpleName}: ${e.message?.take(60)})"); null
                }
                ch.send(api to r)
            }
        }
        var got: GhRelease? = null
        var apiDone = false
        withTimeoutOrNull(25_000) {
            var left = tasks.size
            while (left > 0) {
                val x = if (got == null) ch.receive() else (if (apiDone) null else withTimeoutOrNull(2_000) { ch.receive() }) ?: break
                left--
                if (x.first) apiDone = true
                val r = x.second
                if (r != null && r.assets.any { apkName.matches(it.name) }) {
                    if (x.first) { got = r; break }
                    if (got == null) got = r
                    if (apiDone) break
                } else if (got != null && apiDone) break
            }
        }
        jobs.forEach { it.cancel() }
        val r = got ?: throw IOException("直连 GitHub 和 ${MIRRORS.size} 个镜像都连不上(可以在下面的「下载源与 DNS」里测一下)")
        AppLog.i("update", "检查更新用时 ${(System.nanoTime() - t0) / 1_000_000}ms(${if (r.body != null) "GitHub API" else "SHA256SUMS"})")
        var apk: GhAsset? = null
        var ver = ""
        for (a in r.assets) apkName.find(a.name)?.let { apk = a; ver = it.groupValues[1] }
        val a = apk ?: return@coroutineScope null
        val sha = r.sums?.let { sumFor(it, a.name) }
        Release(r.tag, ver, r.body.orEmpty().trim().take(1500), a.url, r.assets.firstOrNull { it.name == "SHA256SUMS.txt" }?.url, a.size, sha)
    }

    private fun sumFor(sums: String, name: String): String? = sums.lineSequence().map { it.trim().split(Regex("\\s+")) }
        .firstOrNull { it.size >= 2 && it.last().removePrefix("*").equals(name, true) }?.first()?.lowercase()?.takeIf { it.length == 64 }

    /** SHA256SUMS.txt 里这个文件的校验和:直连和镜像同时取,第一个有效的就用。 */
    private suspend fun wantSum(sumUrl: String, name: String): String = coroutineScope {
        val ch = Channel<String?>(Channel.UNLIMITED)
        val urls = candidates(sumUrl)
        val jobs = urls.map { u -> launch(Dispatchers.IO) { ch.send(runCatching { getText(u) }.getOrNull()?.takeIf { validSums(it) }?.let { sumFor(it, name) }) } }
        var want: String? = null
        withTimeoutOrNull(20_000) { repeat(urls.size) { if (want == null) want = ch.receive() } }
        jobs.forEach { it.cancel() }
        want ?: throw IOException("取不到 SHA256SUMS.txt 里 $name 的校验和")
    }

    /**
     * 下载并校验,返回本地文件。onProgress(已下载, 总大小)在 IO 线程调用。
     * [serverHttp] 访问自己服务器的客户端;[recheck] 再问一次服务器(检查时服务器还没下好,点下载时可能已经好了)。
     */
    private val downloadLock = kotlinx.coroutines.sync.Mutex()
    data class DownloadState(val version: String = "", val progress: Int = -1, val file: File? = null, val error: String = "")
    val downloadState = kotlinx.coroutines.flow.MutableStateFlow(DownloadState())
    private var downloadJob: kotlinx.coroutines.Job? = null
    fun store(ctx: Context) = UpdatePackageStore(File(ctx.filesDir, "updates"))
    fun startDownload(scope: kotlinx.coroutines.CoroutineScope, ctx: Context, rel: Release, http: OkHttpClient, recheck: suspend () -> ServerSrc?) {
        if (downloadJob?.isActive == true) return
        downloadState.value = DownloadState(rel.version, 0)
        downloadJob = scope.launch {
            try {
                val file = download(ctx.applicationContext, rel, http, recheck) { done, total ->
                    downloadState.value = DownloadState(rel.version, if (total > 0) (done * 100 / total).toInt().coerceIn(0, 99) else 0)
                }
                downloadState.value = DownloadState(rel.version, file = file)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                AppLog.w("update", "下载更新失败", e)
                downloadState.value = DownloadState(rel.version, error = e.message ?: "下载失败")
            }
        }
    }
    suspend fun download(
        ctx: Context, rel: Release, serverHttp: OkHttpClient? = null, recheck: (suspend () -> ServerSrc?)? = null, onProgress: (Long, Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        downloadLock.lock()
        try {
            val name = "LocalBrowse-${rel.version}.apk"
            val want = (rel.sha ?: rel.server?.sha?.takeIf { it.length == 64 }
                ?: wantSum(rel.sumUrl ?: throw IOException("发布缺少校验文件"), name)).lowercase()
            val store = store(ctx)
            store.cached(rel.version, want)?.let { return@withContext it }
            val part = store.partial(rel.version)
            var high = 0L
            val report: (Long, Long) -> Unit = { done, total ->
                if (total > 0) { high = maxOf(high, done * 10000 / total).coerceAtMost(9900); onProgress(high, 10000) }
                else onProgress(high, 10000)
            }
            val initial = rel.server
            val server = if (initial?.ready == true) initial else withTimeoutOrNull(2000) { recheck?.invoke() } ?: initial
            if (serverHttp != null && server?.ready == true && server.version == rel.version && server.sha.equals(want, true)) {
                try {
                    val http = serverHttp.newBuilder().dispatcher(okhttp3.Dispatcher())
                        .connectTimeout(3, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
                    val url = server.url + "?version=${rel.version}&sha256=$want"
                    UpdateTransfer(http).fetch(url, part, want, rel.size, report)
                    return@withContext store.promote(rel.version, want)
                } catch (e: CancellationException) { throw e
                } catch (e: IOException) { AppLog.w("update", "服务器下载中断，保留已下载内容后切换源", e) }
            }
            val urls = candidates(rel.apkUrl)
            val probes = measure(if (preferred.isNotEmpty()) urls.take(1) else urls)
            var error: IOException? = null
            for (url in (probes.map { it.url } + urls).distinct()) {
                try {
                    UpdateTransfer(client.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()).fetch(url, part, want, rel.size, report)
                    return@withContext store.promote(rel.version, want)
                } catch (e: CancellationException) { throw e
                } catch (e: IOException) { error = e; AppLog.i("update", "更换下载源: ${e.message}") }
            }
            throw error ?: IOException("下载失败，已保留断点")
        } finally { downloadLock.unlock() }
    }

    private class Probe(val url: String, val bps: Double, val ranged: Boolean, val total: Long)
    /** 所有源同时下载开头 256KB,按实际速度从快到慢排;返回的不是 APK(开头不是 "PK")的源不算。 */
    private suspend fun measure(urls: List<String>): List<Probe> = coroutineScope {
        urls.map { u ->
            async(Dispatchers.IO) {
                try {
                    val t0 = System.nanoTime()
                    probeClient.newCall(Request.Builder().url(u).header("Range", "bytes=0-${PROBE - 1}").header("User-Agent", UA).build()).awaitResp().use { r ->
                        if (r.code != 200 && r.code != 206) return@async null
                        val src = r.body.source()
                        val buf = ByteArray(32 * 1024)
                        var n = 0L
                        var magic = ""
                        while (n < PROBE) {
                            val k = src.read(buf, 0, minOf(buf.size.toLong(), PROBE - n).toInt())
                            if (k < 0) break
                            if (n == 0L && k >= 2) magic = String(buf, 0, 2, Charsets.ISO_8859_1)
                            n += k
                        }
                        if (magic != "PK") return@async null
                        val sec = (System.nanoTime() - t0) / 1e9
                        val total = if (r.code == 206) r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: -1L else r.body.contentLength()
                        Probe(u, n / sec.coerceAtLeast(0.001), r.code == 206, total)
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            }
        }.awaitAll().filterNotNull().sortedByDescending { it.bps }
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
