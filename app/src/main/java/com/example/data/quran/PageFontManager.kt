package com.example.data.quran

import android.graphics.Typeface
import android.util.LruCache
import com.example.data.integrity.VerifiedDiskCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

class PageFontManager(
  private val diskCache: VerifiedDiskCache,
) {
  // Keeps active, +-2 prefetch window, and p1 Basmalah fonts in memory without thrashing
  private val memoryCache = LruCache<String, Typeface>(10)
  private val inFlightLoads = ConcurrentHashMap<String, Deferred<Typeface?>>()

  private fun cacheKey(page: Int, edition: MushafEdition, useWhiteInk: Boolean): String =
    when (edition) {
      MushafEdition.MADINAH_1421 -> "${edition.name}_p$page"
      MushafEdition.TAJWEED_V4 -> "${edition.name}_${if (useWhiteInk) "white" else "black"}_p$page"
    }

  private fun relativeDiskPath(page: Int, edition: MushafEdition, useWhiteInk: Boolean): String =
    when (edition) {
      MushafEdition.MADINAH_1421 -> "fonts/qcf_v2/p$page.ttf"
      MushafEdition.TAJWEED_V4 ->
        if (useWhiteInk) "fonts/qcf_v4_white_v2/p$page.ttf" else "fonts/qcf_v4_black_v2/p$page.ttf"
    }

  internal fun fontUrl(page: Int, edition: MushafEdition): String =
    when (edition) {
      MushafEdition.MADINAH_1421 ->
        "https://verses.quran.foundation/fonts/quran/hafs/v2/ttf/p$page.ttf"
      MushafEdition.TAJWEED_V4 ->
        "https://verses.quran.foundation/fonts/quran/hafs/v4/colrv1/ttf/p$page.ttf"
    }

  fun getCachedFont(page: Int, edition: MushafEdition, useWhiteInk: Boolean = false): Typeface? =
    memoryCache.get(cacheKey(page, edition, useWhiteInk))

  suspend fun loadPageFont(
    page: Int,
    edition: MushafEdition,
    useWhiteInk: Boolean = false,
  ): Typeface? {
    if (page !in 1..604) return null
    val key = cacheKey(page, edition, useWhiteInk)
    memoryCache.get(key)?.let { return it }

    inFlightLoads[key]?.let { existing ->
      return existing.await()
    }

    val deferred = CompletableDeferred<Typeface?>()
    val raced = inFlightLoads.putIfAbsent(key, deferred)
    if (raced != null) {
      return raced.await()
    }

    try {
      val loaded = withContext(Dispatchers.IO) {
        memoryCache.get(key)?.let { return@withContext it }

        val diskPath = relativeDiskPath(page, edition, useWhiteInk)
        var file = diskCache.getVerifiedFile(diskPath)
        if (file == null) {
          if (edition == MushafEdition.TAJWEED_V4) {
            val rawPath = "fonts/qcf_v4_raw/p$page.ttf"
            val rawBytes = diskCache.readVerifiedBytes(rawPath) ?: run {
              val fetched = fetchBytes(fontUrl(page, edition)) ?: return@withContext null
              diskCache.writeVerifiedBytes(rawPath, fetched)
              fetched
            }
            val finalBytes = withContext(Dispatchers.Default) {
              if (useWhiteInk) {
                CpalFontPatcher.patchCpalBlackToWhite(rawBytes)
              } else {
                CpalFontPatcher.patchCpalBlackToBlack(rawBytes)
              }
            }
            file = diskCache.writeVerifiedBytes(diskPath, finalBytes)
          } else {
            val rawBytes = fetchBytes(fontUrl(page, edition)) ?: return@withContext null
            file = diskCache.writeVerifiedBytes(diskPath, rawBytes)
          }
        }

        val typeface = runCatching { Typeface.createFromFile(file) }.getOrNull()
          ?: return@withContext null

        memoryCache.put(key, typeface)
        typeface
      }
      deferred.complete(loaded)
      return loaded
    } catch (t: Throwable) {
      deferred.completeExceptionally(t)
      throw t
    } finally {
      inFlightLoads.remove(key, deferred)
    }
  }

  // Page 1 font holds the official 1:1 Basmalah QCF glyphs for Surah headers across the Mushaf
  suspend fun loadBasmalahFont(
    edition: MushafEdition,
    useWhiteInk: Boolean = false,
  ): Typeface? = loadPageFont(1, edition, useWhiteInk = useWhiteInk)

  private suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
    runCatching {
      val req = Request.Builder().url(url).build()
      QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        resp.body?.bytes()
      }
    }.getOrNull()
  }
}
