package com.localtg.data

import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class UpdateTransferTest {
    private fun temporary() = kotlin.io.path.createTempDirectory("update-test").toFile().apply { deleteOnExit() }
    private fun serve(body: ByteArray, status: Int, range: String? = null, block: (String, AtomicReference<String>) -> Unit) {
        ServerSocket(0).use { server ->
            val request = AtomicReference("")
            val worker = thread {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val lines = mutableListOf<String>()
                    while (true) { val line=reader.readLine() ?: break; if (line.isEmpty()) break; lines+=line }
                    request.set(lines.joinToString("\n"))
                    val headers="HTTP/1.1 $status OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n" + (range?.let { "Content-Range: $it\r\n" } ?: "") + "\r\n"
                    socket.getOutputStream().apply { write(headers.toByteArray());write(body);flush() }
                }
            }
            try { block("http://127.0.0.1:${server.localPort}/app.apk",request) } finally { worker.join(3000) }
        }
    }
    @Test fun resumesAndPromotesThenRetainsUntilInstalled() = runBlocking {
        val dir=temporary();val store=UpdatePackageStore(dir)
        val bytes=ByteArray(65536) { (it%251).toByte() }
        val reference=File(dir,"reference").apply { writeBytes(bytes) }
        val sha=UpdatePackageStore.digest(reference)
        store.partial("4.2.0").writeBytes(bytes.take(8192).toByteArray())
        serve(bytes.drop(8192).toByteArray(),206,"bytes 8192-65535/65536") { url,request ->
            runBlocking { UpdateTransfer(OkHttpClient()).fetch(url,store.partial("4.2.0"),sha,65536) { _,_-> } }
            assertTrue(request.get().contains("Range: bytes=8192-"))
        }
        val apk=store.promote("4.2.0",sha)
        assertArrayEquals(bytes,apk.readBytes())
        store.cleanupInstalled("4.1.9")
        assertEquals(apk,store.pending("4.1.9")?.file)
        assertEquals(apk,store.cached("4.2.0",sha))
        store.cleanupInstalled("4.2.0")
        assertFalse(apk.exists());assertNull(store.pending("4.2.0"))
        dir.deleteRecursively(); Unit
    }
    @Test fun serverIgnoringRangeRestartsSafelyAndRejectsCorruption() = runBlocking {
        val dir=temporary();val part=File(dir,"partial").apply { writeText("old-prefix") }
        val valid=File(dir,"valid").apply { writeText("correct-package") };val sha=UpdatePackageStore.digest(valid)
        serve(valid.readBytes(),200) { url,_-> runBlocking { UpdateTransfer(OkHttpClient()).fetch(url,part,sha) { _,_-> } } }
        assertArrayEquals(valid.readBytes(),part.readBytes())
        part.writeText("old-prefix")
        serve("bad".toByteArray(),200) { url,_->
            try { runBlocking { UpdateTransfer(OkHttpClient()).fetch(url,part,sha) { _,_-> } };fail("bad package accepted") } catch (_: UpdateChecksumException) { }
        }
        assertFalse(part.exists());dir.deleteRecursively(); Unit
    }
    @Test fun oldIntegerSlideshowSettingsAndBoundsRemainCompatible() {
        val old=AppJson.decodeFromString<AppSettings>("{\"slideshowSeconds\":30}")
        assertEquals(10f,slideSeconds(old.slideshowSeconds))
        assertEquals(0.5f,slideSeconds(0.1f))
        assertEquals(5f,slideSeconds(Float.NaN))
    }
    @Test fun newVersionNeverUsesThePreviousServersDigest() {
        val old="a".repeat(64);val current="b".repeat(64)
        val preparing=AppUpdater.ServerSrc("http://server/app.apk","4.2.0",old,false)
        val release=AppUpdater.Release("","4.2.0","","",null,123,old,preparing)
        assertNull(AppUpdater.trustedSha(release,preparing))
        assertEquals(current,AppUpdater.trustedSha(release,AppUpdater.ServerSrc("http://server/app.apk","4.2.0",current,true)))
        assertNull(AppUpdater.trustedSha(release,AppUpdater.ServerSrc("http://server/app.apk","4.1.9",old,true)))
    }
}
