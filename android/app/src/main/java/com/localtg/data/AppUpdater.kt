package com.localtg.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.localtg.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 手机端检查更新(2.5.0):设置 → 关于 → 检查更新。手动点击:查 GitHub Releases 最新版本 → 下载 APK(校验 SHA-256)→ 调起系统安装器。
 * 发布约定:Release 里有 LocalBrowse-<版本>.apk 和 SHA256SUMS.txt;版本号取自文件名,和当前 versionName 逐段按数字比较。
 * 注意:用自己的 OkHttp 客户端(系统信任的证书),不能用访问服务器的那个 —— 它只信任固定指纹的自签名证书,连不上 GitHub。
 */
object AppUpdater {
    const val REPO = "ack528/MediaHub"

    class Release(val tag: String, val version: String, val notes: String, val apkUrl: String, val sumUrl: String?, val size: Long)

    @Serializable private class GhAsset(val name: String = "", @SerialName("browser_download_url") val url: String = "", val size: Long = 0)
    @Serializable private class GhRelease(@SerialName("tag_name") val tag: String = "", val body: String? = null, val assets: List<GhAsset> = emptyList())

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
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

    private fun get(url: String): String {
        val req = Request.Builder().url(url).header("User-Agent", "LocalBrowse-Updater").header("Accept", "application/vnd.github+json").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            return r.body.string()
        }
    }

    /** 最新发布;发布里没有 APK 返回 null。 */
    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val r = AppJson.decodeFromString<GhRelease>(get("https://api.github.com/repos/$REPO/releases/latest"))
        var apk: GhAsset? = null
        var ver = ""
        for (a in r.assets) apkName.find(a.name)?.let { apk = a; ver = it.groupValues[1] }
        val a = apk ?: return@withContext null
        Release(r.tag, ver, r.body.orEmpty().trim().take(1500), a.url, r.assets.firstOrNull { it.name == "SHA256SUMS.txt" }?.url, a.size)
    }

    /** 下载并校验,返回本地文件。onProgress(已下载, 总大小)在 IO 线程调用。 */
    suspend fun download(ctx: Context, rel: Release, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        val sumUrl = rel.sumUrl ?: throw IOException("发布里没有 SHA256SUMS.txt,无法校验,已放弃")
        val name = "LocalBrowse-${rel.version}.apk"
        val want = get(sumUrl).lineSequence().map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 2 && it.last().removePrefix("*").equals(name, true) }?.first()?.lowercase()
            ?: throw IOException("SHA256SUMS.txt 里没有 $name")
        val dir = File(ctx.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, name)
        val md = MessageDigest.getInstance("SHA-256")
        client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(rel.apkUrl).header("User-Agent", "LocalBrowse-Updater").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("下载失败 HTTP ${r.code}")
                val total = r.body.contentLength().takeIf { it > 0 } ?: rel.size
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
        if (got != want) { out.delete(); throw IOException("校验和不一致(下载损坏或被篡改)") }
        AppLog.i("update", "已下载并校验 ${out.name}(${out.length() / 1024} KB)")
        out
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
}
