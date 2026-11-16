package com.example.data.integrity

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class AssetHashStatus(
  val path: String,
  val expectedSha256: String,
  val actualSha256: String,
  val isValid: Boolean,
)

object Sha256Integrity {
  fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
  }

  fun sha256Hex(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
      val buf = ByteArray(16 * 1024)
      while (true) {
        val read = input.read(buf)
        if (read <= 0) break
        md.update(buf, 0, read)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}

class VerifiedDiskCache(private val context: Context) {
  private val cacheRoot = File(context.filesDir, "qabasat_verified_cache").apply { mkdirs() }

  suspend fun readVerifiedBytes(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
    val file = File(cacheRoot, relativePath)
    val hashFile = File(cacheRoot, "$relativePath.sha256")
    if (!file.exists() || !hashFile.exists()) return@withContext null
    val expected = runCatching { hashFile.readText().trim().lowercase() }.getOrNull() ?: return@withContext null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
    val actual = Sha256Integrity.sha256Hex(bytes)
    if (actual != expected) {
      file.delete()
      hashFile.delete()
      return@withContext null
    }
    bytes
  }

  suspend fun getVerifiedFile(relativePath: String): File? = withContext(Dispatchers.IO) {
    val file = File(cacheRoot, relativePath)
    val hashFile = File(cacheRoot, "$relativePath.sha256")
    if (!file.exists() || !hashFile.exists()) return@withContext null
    val expected = runCatching { hashFile.readText().trim().lowercase() }.getOrNull() ?: return@withContext null
    val actual = runCatching { Sha256Integrity.sha256Hex(file) }.getOrNull() ?: return@withContext null
    if (actual != expected) {
      file.delete()
      hashFile.delete()
      return@withContext null
    }
    file
  }

  suspend fun writeVerifiedBytes(relativePath: String, bytes: ByteArray): File = withContext(Dispatchers.IO) {
    val file = File(cacheRoot, relativePath)
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeBytes(bytes)
    val hash = Sha256Integrity.sha256Hex(bytes)
    val hashFile = File(cacheRoot, "$relativePath.sha256")
    hashFile.writeText(hash)
    if (file.exists()) file.delete()
    tmp.renameTo(file)
    file
  }

  suspend fun verifyBundledAssetsManifest(): List<AssetHashStatus> = withContext(Dispatchers.IO) {
    val manifestBytes = context.assets.open("quran/manifest.sha256.json").use { it.readBytes() }
    val root = JSONObject(manifestBytes.decodeToString())
    val array = root.getJSONArray("files")
    val results = mutableListOf<AssetHashStatus>()
    for (i in 0 until array.length()) {
      val item = array.getJSONObject(i)
      val path = item.getString("path")
      val expected = item.getString("sha256").lowercase()
      val actual = runCatching {
        context.assets.open("quran/$path").use { Sha256Integrity.sha256Hex(it.readBytes()) }
      }.getOrDefault("MISSING")
      results += AssetHashStatus(
        path = path,
        expectedSha256 = expected,
        actualSha256 = actual,
        isValid = expected == actual,
      )
    }
    results
  }
}
