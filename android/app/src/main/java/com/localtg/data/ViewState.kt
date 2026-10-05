package com.localtg.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val Context.viewDataStore by preferencesDataStore(name = "view_state")

/**
 * 每个群组(文件夹)各自记住:上次看到哪一条、滚动偏移,以及排序/视图/过滤。
 * 下次点开时直接回到那个位置(Telegram 打开聊天回到上次位置的做法)。
 */
@Serializable
data class DialogView(
    /** 退出时屏幕上"第一项"(聊天流里是最靠下的一条,网格里是左上角)的条目 id */
    val itemId: String? = null,
    /** 该条目相对于列表边缘的像素偏移,用于精确还原 */
    val offsetPx: Int = 0,
    val sort: String = "taken",
    val ascChat: Boolean = true,
    val ascGrid: Boolean = false,
    val grid: Boolean = false,
    val types: List<String> = listOf("photo", "video", "gif"),
    val columns: Int = 3,
    val savedAt: Long = 0,
    /** 写入这条记录的设备(用来提示"已接着 xx 上次的位置") */
    val device: String = "",
)

class ViewStateStore(private val ctx: Context, private val ns: () -> String = { "" }) {
    /** 键按服务器分前缀(不同服务器的文件夹 id 可能重复) */
    private fun key(dialogId: String, prefix: String) = stringPreferencesKey("dv_$prefix$dialogId")

    suspend fun load(dialogId: String, prefix: String = ns()): DialogView? = runCatching {
        ctx.viewDataStore.data.first()[key(dialogId, prefix)]?.let { AppJson.decodeFromString<DialogView>(it) }
    }.getOrNull()

    suspend fun save(dialogId: String, v: DialogView, prefix: String = ns()) {
        runCatching {
            ctx.viewDataStore.edit { it[key(dialogId, prefix)] = AppJson.encodeToString(v.copy(savedAt = System.currentTimeMillis())) }
        }
    }

    /** 退出登录或换服务器时清掉所有位置记录(条目 id 只对原服务器有效)。 */
    suspend fun clearAll() {
        runCatching { ctx.viewDataStore.edit { it.clear() } }
    }
}
