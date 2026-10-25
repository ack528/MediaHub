package com.localtg.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UpdateChecksumException : IOException("安装包校验和不一致")

/** One owner writes the partial file; resumed bytes are trusted only after the complete SHA-256 check. */
class UpdateTransfer(private val http: OkHttpClient) {
    suspend fun fetch(url: String, part: File, sha: String, size: Long = 0, progress: (Long,Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        require(Regex("[a-fA-F0-9]{64}").matches(sha))
        part.parentFile?.mkdirs()
        if (part.isFile && UpdatePackageStore.digest(part) == sha.lowercase()) return@withContext
        if (size > 0 && part.length() >= size) part.delete()
        val offset=part.length()
        val request=Request.Builder().url(url).header("Accept-Encoding","identity").header("User-Agent","LocalBrowse-Updater")
        if(offset>0)request.header("Range","bytes=$offset-")
        val call=http.newCall(request.build())
        val response=suspendCancellableCoroutine<okhttp3.Response> { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object:okhttp3.Callback {
                override fun onFailure(call:okhttp3.Call,e:IOException) { if(cont.isActive)cont.resumeWithException(e) }
                override fun onResponse(call:okhttp3.Call,response:okhttp3.Response) { if(cont.isActive)cont.resume(response) else response.close() }
            })
        }
        response.use { r ->
            if (r.code == 416 && offset > 0) {
                r.close()
                if (!part.delete()) throw IOException("无法重置无效的下载断点")
                return@withContext fetch(url, part, sha, size, progress)
            }
            if(r.code!=200 && r.code!=206)throw IOException("下载失败 HTTP ${r.code}")
            val range=r.header("Content-Range")
            val start=if(r.code==206) {
                val match=Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(range.orEmpty()) ?: throw IOException("下载范围响应无效")
                if(match.groupValues[1].toLong()!=offset)throw IOException("下载范围位置不一致")
                if(size>0 && match.groupValues[3].toLong()!=size)throw IOException("安装包大小已变化")
                offset
            } else 0L
            val total=if(r.code==206)range!!.substringAfterLast('/').toLong() else r.body.contentLength().takeIf{it>0}?:size
            if(size>0 && total>0 && total!=size)throw IOException("安装包大小不一致")
            RandomAccessFile(part,"rw").use { file ->
                if(start==0L)file.setLength(0)
                file.seek(start);var done=start
                r.body.byteStream().use { input ->
                    val buffer=ByteArray(128*1024)
                    while(true) {
                        currentCoroutineContext().ensureActive()
                        val n=input.read(buffer);if(n<0)break
                        file.write(buffer,0,n);done+=n
                        if(total>0 && done>total)throw IOException("安装包超过预期大小")
                        progress(done,total)
                    }
                }
                if(total>0 && done!=total)throw IOException("下载中断，可继续下载")
            }
        }
        currentCoroutineContext().ensureActive()
        if(UpdatePackageStore.digest(part)!=sha.lowercase()) { part.delete();throw UpdateChecksumException() }
    }
}
