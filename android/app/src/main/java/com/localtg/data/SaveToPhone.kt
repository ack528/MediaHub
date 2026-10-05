package com.localtg.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.localtg.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * 把服务器上的原文件保存到手机:图片进「图片/MediaHub」,视频进「影片/MediaHub」,系统相册里就能看到。
 * Android 10+ 通过 MediaStore 写入,不需要任何存储权限;更低版本存到本应用的专属目录。
 * 返回 null 表示成功,否则是中文错误说明。
 */
suspend fun saveToPhone(ctx: Context, http: OkHttpClient, api: Api, item: Item): String? = withContext(Dispatchers.IO) {
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(item.ext.lowercase())
        ?: if (item.isVideo) "video/*" else "image/*"
    val name = item.name.substringAfterLast('/')
    try {
        val resp = http.newCall(Request.Builder().url(api.fileUrl(item)).build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) {
                return@withContext when (r.code) {
                    404 -> "文件已不存在"
                    422 -> "文件大小为 0 字节,没有内容可保存"
                    else -> "下载失败(${r.code})"
                }
            }
            val body = r.body
            val sub = if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val col = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$sub/MediaHub")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(col, values) ?: return@withContext "无法在相册里创建文件"
                try {
                    ctx.contentResolver.openOutputStream(uri)!!.use { out -> body.byteStream().copyTo(out, 128 * 1024) }
                    ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    ctx.contentResolver.delete(uri, null, null) // 不留下写了一半的文件
                    throw e
                }
            } else {
                val dir = File(ctx.getExternalFilesDir(sub), "MediaHub").apply { mkdirs() }
                val f = File(dir, name)
                try {
                    f.outputStream().use { out -> body.byteStream().copyTo(out, 128 * 1024) }
                } catch (e: Exception) { f.delete(); throw e }
            }
        }
        AppLog.i("save", "已保存到手机 $name (${item.size} 字节)")
        null
    } catch (e: Exception) {
        AppLog.w("save", "保存失败 $name:${e.javaClass.simpleName} ${e.message}")
        "保存失败:" + (e.message ?: e.javaClass.simpleName)
    }
}

/** 在界面里调用:保存并用 Toast 提示结果。 */
suspend fun saveToPhoneWithToast(ctx: Context, http: OkHttpClient, api: Api, item: Item) {
    Toast.makeText(ctx, "正在保存…", Toast.LENGTH_SHORT).show()
    val err = saveToPhone(ctx, http, api, item)
    Toast.makeText(
        ctx, err ?: (if (item.isVideo) "已保存到 影片/MediaHub" else "已保存到 图片/MediaHub"),
        if (err == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
    ).show()
}
