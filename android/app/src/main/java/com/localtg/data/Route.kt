package com.localtg.data

import com.localtg.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 内网直连优先(3.1.0):手机和服务器在同一个局域网时,不要绕一大圈走内网穿透 / 云中继,直接连服务器的内网地址。
 *
 * 做法:所有地址仍然按"服务器原地址"生成(图片磁盘缓存的键、令牌的附加判断都不变),
 * 发请求的最后一步 [RouteInterceptor] 才把主机换成当前选中的内网地址;切线路不用重建任何 URL,播放中的视频重连时也自动用新线路。
 * 安全:只在服务器是 HTTPS 时启用 —— 内网地址同样要通过证书指纹校验,别的设备冒充这个 IP 过不了校验,令牌不会发给它。
 */
object Route {
    /** 当前使用的内网直连基地址(如 https://192.168.1.20:8480);null = 走服务器原地址。 */
    val current = MutableStateFlow<String?>(null)

    @Volatile var failedAt = 0L

    fun fail(why: String) {
        if (current.value != null) {
            AppLog.w("route", "内网直连失败,退回服务器原地址:$why")
            current.value = null
            failedAt = System.currentTimeMillis()
        }
    }

    /** 连接阶段的失败(连不上 / 超时 / 证书不对):离开了家里的网络、电脑换了 IP。读取超时等不算,免得慢请求把线路切掉。 */
    fun isConnectFailure(e: IOException): Boolean =
        e is ConnectException || e is NoRouteToHostException || e is UnknownHostException || e is SSLException ||
            (e is SocketTimeoutException && e.message?.contains("connect", ignoreCase = true) == true)
}

class RouteInterceptor(private val canonical: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val route = Route.current.value
        val base = canonical()?.trimEnd('/')
        val url = req.url.toString()
        if (route == null || base == null || !url.startsWith(base)) return chain.proceed(req)
        val rewritten = (route.trimEnd('/') + url.substring(base.length)).toHttpUrlOrNull() ?: return chain.proceed(req)
        try {
            return chain.proceed(req.newBuilder().url(rewritten).build())
        } catch (e: IOException) {
            if (chain.call().isCanceled() || !Route.isConnectFailure(e)) throw e
            // 内网地址连不上:退回原地址,这个请求用原地址再来一次(请求体只能发一次的不重试)
            Route.fail(e.javaClass.simpleName + (e.message?.let { ": ${it.take(60)}" } ?: ""))
            if (req.body?.isOneShot() == true) throw e
            return chain.proceed(req)
        }
    }
}
