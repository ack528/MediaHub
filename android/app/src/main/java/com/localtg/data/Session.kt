package com.localtg.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.localtg.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "session")

data class Session(val baseUrl: String, val token: String)

/**
 * 令牌用 Android Keystore 里的 AES-GCM 密钥加密后再落盘(密钥不可导出,换机/清数据后旧令牌自然作废)。
 * 兼容旧版本:没有 "enc1:" 前缀的是明文令牌,读到后立刻改存为密文。
 */
private object TokenCrypto {
    private const val ALIAS = "mediahub_token_key"
    private const val PREFIX = "enc1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun isEncrypted(s: String) = s.startsWith(PREFIX)

    /** 加密失败(极少数设备的 Keystore 异常)时返回 null,调用方退回明文,保证仍可登录。 */
    fun encrypt(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray())
        PREFIX + Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) {
        AppLog.w("session", "令牌加密失败,暂存明文:${e.javaClass.simpleName}")
        null
    }

    /** 解密失败返回 null(例如 Keystore 被重置),视为未登录。 */
    fun decrypt(stored: String): String? = try {
        val raw = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        AppLog.w("session", "令牌解密失败,需要重新登录:${e.javaClass.simpleName}")
        null
    }
}

/**
 * 一台已保存的服务器(或"本地媒体"这个特殊条目)。令牌用 Keystore 加密后存在 [token] 里;密码从不落盘。
 * [pin] 是这台服务器已被用户核对并信任的证书指纹。
 */
@Serializable
data class ServerEntry(
    val id: String,
    val name: String,
    val kind: String = "server",   // server / local
    val baseUrl: String = "",
    val token: String = "",
    val pin: String = "",
    val address: String = "",      // 用户当初输入的地址,登录页回填用
    val user: String = "",
) {
    val isLocal: Boolean get() = kind == "local"
    val loggedIn: Boolean get() = isLocal || token.isNotEmpty()
    val host: String get() = if (isLocal) "这部手机上的照片和视频" else baseUrl.substringAfter("://")
}

/** 保存多个服务器(地址 + 令牌 + 证书指纹)和当前正在用的是哪一个。密码从不落盘。 */
class SessionStore(private val ctx: Context) {
    // 旧版本(单服务器)的键:第一次启动时迁移成一条记录
    private val kUrl = stringPreferencesKey("server_url")
    private val kToken = stringPreferencesKey("token")
    private val kPin = stringPreferencesKey("tls_pin")
    private val kMode = stringPreferencesKey("mode")
    private val kLastAddress = stringPreferencesKey("last_address")
    private val kServers = stringPreferencesKey("servers")
    private val kActive = stringPreferencesKey("active_server")

    /** null 表示没有可用的登录(显示登录页)。 */
    val session = MutableStateFlow<Session?>(null)
    val loaded = MutableStateFlow(false)

    /** 本地媒体模式(读取手机自己的照片和视频,不连服务器)。 */
    val local = MutableStateFlow(false)

    /** 当前服务器的证书指纹(只用来在设置页显示)。 */
    val pin = MutableStateFlow<String?>(null)

    val servers = MutableStateFlow<List<ServerEntry>>(emptyList())
    val activeId = MutableStateFlow<String?>(null)
    val active: ServerEntry? get() = servers.value.firstOrNull { it.id == activeId.value }

    /** 登录页刚核对过、还没保存进某条记录的指纹(只在内存里)。 */
    @Volatile private var extraPins: Set<String> = emptySet()

    /** 所有已信任的指纹:HTTPS 连接只接受其中之一(每条服务器记录各自的 + 刚核对的)。 */
    fun trustedPins(): Set<String> = (servers.value.mapNotNull { it.pin.ifEmpty { null } } + extraPins).map { it.uppercase() }.toSet()

    fun isTrusted(fp: String) = trustedPins().contains(fp.uppercase())

    suspend fun trust(fp: String) { extraPins = extraPins + fp.uppercase() }

    /** 本地记录(浏览位置、播放进度)按服务器分开存,键前缀;迁移来的第一台服务器沿用旧键(不加前缀)。 */
    fun ns(): String = when (val id = activeId.value) { null, "legacy" -> ""; else -> "${id}_" }

    private fun hostOf(url: String) = url.substringAfter("://").trimEnd('/')

    private fun activate(e: ServerEntry?) {
        activeId.value = e?.id
        when {
            e == null -> { local.value = false; pin.value = null; session.value = null }
            e.isLocal -> { local.value = true; pin.value = null; session.value = Session(LocalMode.BASE, "local") }
            else -> {
                local.value = false
                pin.value = e.pin.ifEmpty { null }
                session.value = if (e.token.isEmpty()) null else
                    (if (TokenCrypto.isEncrypted(e.token)) TokenCrypto.decrypt(e.token) else e.token)?.let { Session(e.baseUrl, it) }
            }
        }
    }

    private suspend fun persist() {
        ctx.dataStore.edit {
            it[kServers] = AppJson.encodeToString(servers.value)
            val a = activeId.value
            if (a == null) it.remove(kActive) else it[kActive] = a
        }
    }

    suspend fun load() {
        val p = ctx.dataStore.data.first()
        var list = p[kServers]?.let { runCatching { AppJson.decodeFromString<List<ServerEntry>>(it) }.getOrNull() }
        var act = p[kActive]
        if (list == null) { // 旧版本(单服务器)升级:把原来的登录变成第一条记录
            val m = mutableListOf<ServerEntry>()
            val url = p[kUrl]
            val stored = p[kToken].orEmpty()
            if (!url.isNullOrEmpty()) {
                val enc = if (stored.isEmpty() || TokenCrypto.isEncrypted(stored)) stored else (TokenCrypto.encrypt(stored) ?: stored)
                m += ServerEntry("legacy", hostOf(url), baseUrl = url, token = enc, pin = p[kPin].orEmpty(), address = p[kLastAddress].orEmpty())
            }
            if (p[kMode] == "local") { m += ServerEntry("local", "本地媒体", kind = "local"); act = "local" }
            else if (m.isNotEmpty() && m[0].token.isNotEmpty()) act = "legacy"
            list = m
            servers.value = m
            activeId.value = act
            ctx.dataStore.edit { it.remove(kUrl); it.remove(kToken); it.remove(kPin); it.remove(kMode) }
            persist()
        }
        servers.value = list
        activate(list.firstOrNull { it.id == act })
        AppLog.i("session", "已保存 ${list.size} 个服务器,当前=${activeId.value ?: "无"}")
        loaded.value = true
    }

    /** 登录页回填的地址:当前(需要重新登录的)服务器优先,否则是上次输入的。 */
    suspend fun lastAddress(): String = active?.address?.ifEmpty { null } ?: ctx.dataStore.data.first()[kLastAddress].orEmpty()

    /** 登录成功:新服务器加一条记录,已有的(同地址)更新令牌,并切换到它。 */
    suspend fun save(s: Session, addressAsTyped: String, user: String = "", pin: String? = null) {
        val enc = TokenCrypto.encrypt(s.token) ?: s.token
        val old = servers.value.firstOrNull { !it.isLocal && it.baseUrl.equals(s.baseUrl, ignoreCase = true) }
        val e = (old ?: ServerEntry(id = "s" + java.lang.Long.toString(System.currentTimeMillis(), 36), name = hostOf(s.baseUrl)))
            .copy(baseUrl = s.baseUrl, token = enc, pin = pin.orEmpty().ifEmpty { old?.pin.orEmpty() }, address = addressAsTyped, user = user)
        servers.value = if (old == null) servers.value + e else servers.value.map { if (it.id == e.id) e else it }
        pin?.let { fp -> extraPins = extraPins - fp.uppercase() }
        ctx.dataStore.edit { it[kLastAddress] = addressAsTyped }
        activate(e)
        persist()
    }

    /** 进入本地媒体模式(没有"本地媒体"记录就新建一条)。 */
    suspend fun enterLocal() {
        val e = servers.value.firstOrNull { it.isLocal } ?: ServerEntry("local", "本地媒体", kind = "local").also { servers.value = servers.value + it }
        activate(e)
        persist()
    }

    /** 切换到另一个已保存的服务器(没登录的会停在登录页,地址已回填)。 */
    suspend fun switchTo(id: String) {
        val e = servers.value.firstOrNull { it.id == id } ?: return
        activate(e)
        persist()
        AppLog.i("session", "切换服务器 → ${e.name}")
    }

    /** 回到登录页去添加新服务器;当前的登录保留,随时可以切回。 */
    suspend fun deactivate() {
        activate(null)
        persist()
    }

    suspend fun rename(id: String, name: String) {
        servers.value = servers.value.map { if (it.id == id) it.copy(name = name.trim().ifEmpty { it.name }) else it }
        persist()
    }

    suspend fun remove(id: String) {
        servers.value = servers.value.filterNot { it.id == id }
        if (activeId.value == id) activate(null)
        persist()
    }

    /** 忘记某台服务器的登录令牌(记录和地址保留)。 */
    suspend fun forget(id: String) {
        val e = servers.value.firstOrNull { it.id == id } ?: return
        if (e.isLocal) { if (activeId.value == id) activate(null); persist(); return }
        val n = e.copy(token = "")
        servers.value = servers.value.map { if (it.id == id) n else it }
        if (activeId.value == id) activate(n)
        persist()
    }

    /** 退出登录或令牌失效:保留服务器记录和地址,清掉当前服务器的令牌。 */
    suspend fun clearToken() {
        val id = activeId.value ?: return
        forget(id)
    }

    /** 收到 401 时只处理"仍然是发出请求的那台服务器"(切换服务器后迟到的 401 不应该让新服务器掉线)。 */
    suspend fun clearTokenIf(baseUrl: String) {
        if (session.value?.baseUrl == baseUrl) clearToken()
    }
}
