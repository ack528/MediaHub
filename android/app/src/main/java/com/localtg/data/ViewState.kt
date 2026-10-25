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
    val itemName: String = "",
    /** 该条目相对于列表边缘的像素偏移,用于精确还原 */
    val offsetPx: Int = 0,
    val sort: String = "taken",
    val ascChat: Boolean = true,
    val ascGrid: Boolean = false,
    val grid: Boolean = false,
    /** 网格模式下用瀑布流卡片(小红书式)显示;旧版本不认识这个字段,看到的还是网格 */
    val feed: Boolean = false,
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

    private fun historyKey(dialogId: String, prefix: String) = stringPreferencesKey("bh_$prefix$dialogId")
    suspend fun history(dialogId: String, prefix: String = ns()): List<BrowseCheckpoint> = runCatching {
        ctx.viewDataStore.data.first()[historyKey(dialogId,prefix)]?.let { AppJson.decodeFromString<List<BrowseCheckpoint>>(it) }.orEmpty()
    }.getOrDefault(emptyList())
    suspend fun checkpoint(dialogId: String, view: DialogView, manual: Boolean = false, prefix: String = ns()) {
        if (view.itemId == null) return
        ctx.viewDataStore.edit { prefs ->
            val key = historyKey(dialogId,prefix)
            val old = prefs[key]?.let { AppJson.decodeFromString<List<BrowseCheckpoint>>(it) }.orEmpty()
            val now = System.currentTimeMillis()
            val first = old.firstOrNull()
            val next = if (manual || first == null || (now-first.savedAt >= 60000 && first.view.itemId != view.itemId)) listOf(BrowseCheckpoint(view,now)) + old else old
            prefs[key] = AppJson.encodeToString(next.filter { now-it.savedAt <= 90L*86400000 }.take(100))
        }
    }
    suspend fun deleteHistory(dialogId: String, at: Long?, prefix: String = ns()) {
        ctx.viewDataStore.edit { prefs ->
            val key=historyKey(dialogId,prefix)
            if (at == null) prefs.remove(key) else {
                val old=prefs[key]?.let { AppJson.decodeFromString<List<BrowseCheckpoint>>(it) }.orEmpty()
                prefs[key]=AppJson.encodeToString(old.filter { it.savedAt != at })
            }
        }
    }
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

@Serializable
data class BrowseCheckpoint(val view: DialogView, val savedAt: Long)
@Serializable
data class BrowseHistoryPolicy(val enabled: Boolean = true, val maxEntries: Int = 100, val retentionDays: Int = 90, val intervalSeconds: Int = 60)
@Serializable
data class BrowseHistoryResult(val entries: List<BrowseCheckpoint> = emptyList(), val config: BrowseHistoryPolicy = BrowseHistoryPolicy())
