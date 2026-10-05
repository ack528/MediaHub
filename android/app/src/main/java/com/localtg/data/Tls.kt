package com.localtg.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * 局域网服务器用自签名证书 + 证书固定:
 *  - 第一次连接(登录页)先取得服务器证书的 SHA-256 指纹,显示给用户,与电脑上管理程序显示的核对一致后才"信任";
 *  - 以后所有 HTTPS 连接只接受指纹相同的证书,不依赖域名 / IP,服务器换 IP 也没关系;
 *    证书变了(服务器重装 / 有人冒充)就拒绝连接,需要重新核对。
 * 传输层是 TLS 1.3(Android 10+)+ HTTP/2,AES-GCM 有硬件加速,开销很小。
 */
object Tls {
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

    /** 只接受指纹在已信任集合里的证书(每台已保存的服务器各有一个指纹)。 */
    class PinTrustManager(private val pins: () -> Set<String>) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("不接受客户端证书")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("服务器没有出示证书")
            val trusted = pins()
            if (trusted.isEmpty()) throw CertificateException("尚未信任这个服务器的证书")
            if (!trusted.contains(fingerprint(leaf).uppercase())) throw CertificateException("证书指纹与已信任的不一致")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun pinnedContext(pins: () -> Set<String>): Pair<SSLContext, X509TrustManager> {
        val tm = PinTrustManager(pins)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return ctx to tm
    }

    /** 连接 host:port 做一次 TLS 握手,返回服务器证书指纹(此时不信任任何东西,只是读取,拿到后要给用户核对)。 */
    suspend fun probe(host: String, port: Int, timeoutMs: Int = 5000): String = withContext(Dispatchers.IO) {
        var seen: X509Certificate? = null
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) { seen = chain?.firstOrNull() }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        val sock = ctx.socketFactory.createSocket() as SSLSocket
        try {
            sock.soTimeout = timeoutMs
            sock.connect(InetSocketAddress(host, port), timeoutMs)
            sock.startHandshake()
        } finally {
            runCatching { sock.close() }
        }
        fingerprint(seen ?: throw CertificateException("服务器没有出示证书"))
    }
}
