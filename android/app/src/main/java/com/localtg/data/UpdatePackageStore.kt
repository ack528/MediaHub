package com.localtg.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Verified packages live in files/, independent of screen lifecycle and Android's cache eviction. */
class UpdatePackageStore(val directory: File) {
    private val pattern = Regex("^LocalBrowse-(\\d+(?:\\.\\d+){1,3})\\.apk$")
    data class Pending(val version: String, val file: File, val sha: String)
    fun file(version: String): File {
        require(Regex("\\d+(?:\\.\\d+){1,3}").matches(version))
        directory.mkdirs(); return File(directory, "LocalBrowse-$version.apk")
    }
    fun partial(version: String) = File(directory, file(version).name + ".part")
    fun cached(version: String, expected: String): File? = file(version).takeIf { it.isFile && digest(it) == expected.lowercase() }
    fun pending(installed: String): Pending? = directory.listFiles().orEmpty().mapNotNull { file ->
        val version = pattern.matchEntire(file.name)?.groupValues?.get(1) ?: return@mapNotNull null
        if (compareVersions(version, installed) <= 0) return@mapNotNull null
        val sha = runCatching { File(directory, file.name + ".sha256").readText().trim() }.getOrNull() ?: return@mapNotNull null
        if (!Regex("[a-f0-9]{64}").matches(sha) || digest(file) != sha) return@mapNotNull null
        Pending(version, file, sha)
    }.maxWithOrNull { a,b -> compareVersions(a.version,b.version) }
    fun promote(version: String, sha: String): File {
        val part = partial(version); if (!part.isFile || digest(part) != sha.lowercase()) throw IOException("安装包校验失败")
        val target = file(version)
        File(directory,target.name + ".sha256").writeText(sha.lowercase())
        if (!part.renameTo(target)) throw IOException("无法保存安装包")
        return target
    }
    /** Called only after reading the actually installed version; starting an installer is not success. */
    fun cleanupInstalled(installed: String) {
        directory.listFiles().orEmpty().forEach { file ->
            val base = file.name.removeSuffix(".part").removeSuffix(".sha256")
            val version = pattern.matchEntire(base)?.groupValues?.get(1) ?: return@forEach
            if (compareVersions(version, installed) <= 0) file.delete()
        }
    }
    companion object {
        fun digest(file: File): String = file.inputStream().use { input ->
            val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(128 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer,0,n) }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        fun compareVersions(a: String,b: String): Int {
            val x=a.removePrefix("v").split('.'); val y=b.removePrefix("v").split('.')
            for(i in 0 until maxOf(x.size,y.size)) { val c=(x.getOrNull(i)?.toIntOrNull()?:0).compareTo(y.getOrNull(i)?.toIntOrNull()?:0); if(c!=0)return c }
            return 0
        }
    }
}
