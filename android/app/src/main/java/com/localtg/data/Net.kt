package com.localtg.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.localtg.AppLog
import kotlinx.coroutines.delay
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络状态与断网恢复(2.4.0):
 *  - 监听系统的默认网络:断开 → 标记离线,请求先等一会儿网络回来再发,而不是立刻失败 / 卡在死连接上;
 *  - 默认网络变了(Wi-Fi ↔ 移动数据、Tailscale / 代理开关)→ [onChange] 回调:清掉连接池里旧网络上的连接、取消卡在旧网络上的接口请求(之后自动重试),
 *    否则旧连接在系统层面已经死了,请求要一直等到读取超时(30 秒)才失败,期间界面就是"卡住"。
 */
object Net {
    @Volatile var online = true
        private set
    /** 默认网络每变化一次加 1(接口请求据此判断"这次失败是不是网络切换造成的",是就直接重试)。 */
    @Volatile var gen = 0
        private set
    @Volatile var onChange: (() -> Unit)? = null
    private var current: Network? = null

    fun init(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        online = hasInternet(cm, cm.activeNetwork)
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val changed = current != null && current != network
                    current = network
                    setOnline(true, "网络可用")
                    if (changed) changed("默认网络切换")
                }

                override fun onLost(network: Network) {
                    if (current == network) current = null
                    setOnline(false, "网络断开")
                    changed("网络断开")
                }

                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    if (ok != online) setOnline(ok, if (ok) "网络恢复" else "网络不可用")
                }
            })
        }.onFailure { AppLog.w("net", "注册网络监听失败:${it.message}") }
    }

    private fun hasInternet(cm: ConnectivityManager, n: Network?): Boolean =
        n != null && cm.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    private fun setOnline(v: Boolean, why: String) {
        if (online != v) AppLog.i("net", "$why(在线=$v)")
        online = v
    }

    private fun changed(why: String) {
        gen++
        AppLog.i("net", "$why:清理旧连接,取消卡住的接口请求")
        runCatching { onChange?.invoke() }
    }

    /** 离线时等网络回来,最多等 timeoutMs;返回当前是否在线。 */
    suspend fun awaitOnline(timeoutMs: Long): Boolean {
        var waited = 0L
        while (!online && waited < timeoutMs) { delay(300); waited += 300 }
        return online
    }
}

/**
 * DNS 失败时退回上一次解析成功的地址:云服务器的域名偶尔解析失败(日志里 UnknownHostException: Unable to resolve host),
 * 其实地址没变,直接用上次的就能连上;真断网的话连接本身会很快失败,不影响。
 */
class FallbackDns : Dns {
    private val cache = ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> = try {
        Dns.SYSTEM.lookup(hostname).also { cache[hostname] = it }
    } catch (e: UnknownHostException) {
        val old = cache[hostname]
        if (old != null) { AppLog.w("net", "解析 $hostname 失败,使用上次的地址 ${old.joinToString { it.hostAddress ?: "?" }}"); old } else throw e
    }
}
