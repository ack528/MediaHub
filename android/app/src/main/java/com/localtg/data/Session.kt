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

/** 保存服务器地址与令牌。密码从不落盘。 */
class SessionStore(private val ctx: Context) {
    private val kUrl = stringPreferencesKey("server_url")
    private val kToken = stringPreferencesKey("token")
    private val kLastAddress = stringPreferencesKey("last_address")
    private val kPin = stringPreferencesKey("tls_pin")
    private val kMode = stringPreferencesKey("mode")

    /** null 表示未登录。 */
    val session = MutableStateFlow<Session?>(null)
    val loaded = MutableStateFlow(false)

    /** 本地媒体模式(读取手机自己的照片和视频,不连服务器)。 */
    val local = MutableStateFlow(false)

    /** 已信任的服务器证书指纹(SHA-256);HTTPS 连接只接受这张证书。 */
    val pin = MutableStateFlow<String?>(null)

    suspend fun load() {
        val p = ctx.dataStore.data.first()
        val url = p[kUrl]
        pin.value = p[kPin]
        val stored = p[kToken]
        var tok: String? = null
        if (!stored.isNullOrEmpty()) {
            if (TokenCrypto.isEncrypted(stored)) {
                tok = TokenCrypto.decrypt(stored)
            } else {
                tok = stored // 旧版本留下的明文令牌:读出后改存密文
                TokenCrypto.encrypt(stored)?.let { enc -> ctx.dataStore.edit { it[kToken] = enc } }
            }
        }
        if (p[kMode] == "local") {
            local.value = true
            session.value = Session(LocalMode.BASE, "local")
        } else {
            session.value = if (!url.isNullOrEmpty() && !tok.isNullOrEmpty()) Session(url, tok) else null
        }
        loaded.value = true
    }

    suspend fun setPin(fp: String) {
        pin.value = fp
        ctx.dataStore.edit { it[kPin] = fp }
    }

    suspend fun lastAddress(): String = ctx.dataStore.data.first()[kLastAddress].orEmpty()

    /** 进入本地媒体模式。 */
    suspend fun enterLocal() {
        ctx.dataStore.edit { it[kMode] = "local" }
        local.value = true
        session.value = Session(LocalMode.BASE, "local")
    }

    suspend fun save(s: Session, addressAsTyped: String) {
        val stored = TokenCrypto.encrypt(s.token) ?: s.token
        local.value = false
        ctx.dataStore.edit {
            it[kMode] = "server"
            it[kUrl] = s.baseUrl
            it[kToken] = stored
            it[kLastAddress] = addressAsTyped
        }
        session.value = s
    }

    /** 退出登录或令牌失效:保留服务器地址,清掉令牌。 */
    suspend fun clearToken() {
        ctx.dataStore.edit { it.remove(kToken); it.remove(kMode) }
        local.value = false
        session.value = null
    }
}
