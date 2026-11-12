package com.localtg.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.localtg.AppContainer
import com.localtg.AppLog
import com.localtg.data.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import java.io.File

/*
 * 图片查看器和视频播放器共用的"文件操作"(菜单底部同一组、同一顺序)和"信息"开头的通用几行:
 * 以前图片菜单只有 保存 / 幻灯片 / 图片信息,视频把保存和信息藏在「更多」里,两边位置、名字都不一样。
 */

/** 菜单底部的通用操作:分享 / 保存到手机 / 在文件夹中定位 / 复制文件名 / 信息。 */
@Composable
internal fun CommonFileRows(c: AppContainer, item: Item, onClose: () -> Unit, onBack: () -> Unit, onInfo: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = !c.isLocal && item.size > 0L && !item.flags.corrupt
    if (remote) {
        MenuRow("分享", arrow = false) { onClose(); scope.launch { shareItem(ctx, c, item) } }
        MenuRow("保存到手机", arrow = false) { onClose(); scope.launch { com.localtg.data.saveToPhoneWithToast(ctx, c.http, c.api, item) } }
    }
    MenuRow("在文件夹中定位", arrow = false) { onClose(); locateInFolder(ctx, c, item, onBack) }
    MenuRow("复制文件名", arrow = false) {
        onClose()
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(ClipData.newPlainText("文件名", item.name))
        Toast.makeText(ctx, "已复制文件名", Toast.LENGTH_SHORT).show()
    }
    MenuRow("信息") { onInfo() }
}

/** 「信息」开头的通用几行:名称、所在文件夹、大小、时间。之后各自接图片 / 视频特有的内容。 */
@Composable
internal fun CommonInfoRows(c: AppContainer, item: Item, size: String = "${formatSize(item.size)}(${"%,d".format(item.size)} 字节)") {
    var folder by remember(item.dialogId) { mutableStateOf<String?>(null) }
    LaunchedEffect(item.dialogId) {
        if (item.dialogId.isNotEmpty()) folder = runCatching { c.api.dialog(item.dialogId).pathDisplay }.getOrNull()
    }
    MenuInfo("名称", item.name)
    MenuInfo("位置", folder ?: "…")
    MenuInfo("大小", size)
    formatDateTime(item.takenAt).takeIf { it.isNotEmpty() }?.let { MenuInfo("拍摄", it) }
    formatDateTime(item.modifiedAt).takeIf { it.isNotEmpty() && it != formatDateTime(item.takenAt) }?.let { MenuInfo("修改", it) }
    MenuInfo("格式", listOf(item.ext.uppercase(), item.mime).filter { it.isNotEmpty() }.joinToString(" · "))
}

/**
 * 在文件夹中定位:从这个文件夹打开的查看器直接关掉(列表已经跟着滚到了这一项,返回动画落在它上面);
 * 从随机浏览 / 搜索打开的,关掉查看器再打开它所在的文件夹,定位到这一项。
 */
private fun locateInFolder(ctx: Context, c: AppContainer, item: Item, onBack: () -> Unit) {
    if (c.viewerFeed.originDialogId == item.dialogId) { onBack(); return }
    onBack()
    val route = JsonObject(mapOf(
        "name" to JsonPrimitive("chat"),
        "params" to JsonObject(mapOf("id" to JsonPrimitive(item.dialogId), "focus" to JsonPrimitive(item.id))),
    ))
    com.localtg.WebPageActivity.start(ctx, route)
}

/** 分享:先把原文件下载到缓存(cache/shared/media/,每次分享前清空),再交给系统分享面板。 */
private suspend fun shareItem(ctx: Context, c: AppContainer, item: Item) {
    Toast.makeText(ctx, if (item.size > 50L * 1024 * 1024) "正在准备分享(${formatSize(item.size)},请稍候)…" else "正在准备分享…", Toast.LENGTH_SHORT).show()
    val result = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(ctx.cacheDir, "shared/media")
            dir.deleteRecursively()
            dir.mkdirs()
            val name = item.name.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "${item.id}.${item.ext}" }
            val out = File(dir, name)
            c.http.newCall(Request.Builder().url(c.api.fileUrl(item)).build()).execute().use { r ->
                if (!r.isSuccessful) error(if (r.code == 404) "文件已不存在" else "下载失败(${r.code})")
                out.outputStream().use { o -> r.body.byteStream().copyTo(o, 128 * 1024) }
            }
            out
        }
    }
    val file = result.getOrElse {
        AppLog.w("viewer", "分享失败 ${item.name}: ${it.message}")
        Toast.makeText(ctx, "分享失败:${it.message}", Toast.LENGTH_LONG).show()
        return
    }
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.ext.lowercase()) ?: if (item.isVideo) "video/*" else "image/*"
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".logs", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "分享 ${item.name}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
