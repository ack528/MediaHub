package com.localtg.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "session")

data class Session(val baseUrl: String, val token: String)

/**
 * 保存服务器地址与令牌。密码从不落盘。
 * TODO(M5):令牌改用 Android Keystore 包装的 AES-GCM 加密存储。
 */
class SessionStore(private val ctx: Context) {
    private val kUrl = stringPreferencesKey("server_url")
    private val kToken = stringPreferencesKey("token")
    private val kLastAddress = stringPreferencesKey("last_address")

    /** null 表示未登录。 */
    val session = MutableStateFlow<Session?>(null)
    val loaded = MutableStateFlow(false)

    suspend fun load() {
        val p = ctx.dataStore.data.first()
        val url = p[kUrl]
        val tok = p[kToken]
        session.value = if (!url.isNullOrEmpty() && !tok.isNullOrEmpty()) Session(url, tok) else null
        loaded.value = true
    }

    suspend fun lastAddress(): String = ctx.dataStore.data.first()[kLastAddress].orEmpty()

    suspend fun save(s: Session, addressAsTyped: String) {
        ctx.dataStore.edit {
            it[kUrl] = s.baseUrl
            it[kToken] = s.token
            it[kLastAddress] = addressAsTyped
        }
        session.value = s
    }

    /** 退出登录或令牌失效:保留服务器地址,清掉令牌。 */
    suspend fun clearToken() {
        ctx.dataStore.edit { it.remove(kToken) }
        session.value = null
    }
}
