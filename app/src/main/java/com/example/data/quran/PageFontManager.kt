package com.example.data.quran

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.LruCache
import com.example.data.integrity.VerifiedDiskCache
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

enum class TajweedRenderPath(val displayAr: String) {
  QCF_V4_COLR("خط مصحف التجويد الملوّن (QCF V4 COLR)"),
  TAG_FALLBACK_COLR_UNSUPPORTED("التلوين بالوسوم (الجهاز لا يدعم ألوان QCF V4)"),
  TAG_FALLBACK_FONT_LOAD_FAILED("التلوين بالوسوم (تعذر تحميل خط QCF V4)"),
}

class PageFontManager(
  private val diskCache: VerifiedDiskCache,
) {
  // Keeps active, +-2 prefetch window, and p1 Basmalah fonts in memory without thrashing
  private val memoryCache = LruCache<String, Typeface>(10)
  private val inFlightLoads = ConcurrentHashMap<String, Deferred<Typeface?>>()

  @Volatile
  private var cachedTajweedRenderPath: TajweedRenderPath? = null

  fun peekCachedTajweedRenderPath(): TajweedRenderPath? = cachedTajweedRenderPath

  fun currentTajweedRenderPathOrDefault(): TajweedRenderPath =
    cachedTajweedRenderPath ?: if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      TajweedRenderPath.TAG_FALLBACK_COLR_UNSUPPORTED
    } else {
      TajweedRenderPath.QCF_V4_COLR
    }

  suspend fun resolveTajweedRenderPath(allowNetworkFetch: Boolean = true): TajweedRenderPath {
    val existing = cachedTajweedRenderPath
    if (existing == TajweedRenderPath.QCF_V4_COLR ||
      existing == TajweedRenderPath.TAG_FALLBACK_COLR_UNSUPPORTED
    ) {
      return existing
    }

    val p1Typeface = if (allowNetworkFetch) {
      loadPageFontForMeasurement(1, MushafEdition.TAJWEED_V4, useWhiteInk = false)
    } else {
      withContext(Dispatchers.IO) {
        val file = diskCache.getVerifiedFile(relativeDiskPath(1, MushafEdition.TAJWEED_V4, useWhiteInk = false))
        if (file != null) runCatching { Typeface.createFromFile(file) }.getOrNull() else null
      }
    }

    if (p1Typeface == null) {
      if (allowNetworkFetch) {
        cachedTajweedRenderPath = TajweedRenderPath.TAG_FALLBACK_FONT_LOAD_FAILED
      }
      return TajweedRenderPath.TAG_FALLBACK_FONT_LOAD_FAILED
    }

    val colrWorks = withContext(Dispatchers.Default) {
      hasChromaticColrRendering(p1Typeface)
    }
    val resolved = if (colrWorks) {
      TajweedRenderPath.QCF_V4_COLR
    } else {
      TajweedRenderPath.TAG_FALLBACK_COLR_UNSUPPORTED
    }
    cachedTajweedRenderPath = resolved
    return resolved
  }

  /**
   * Draws real QCF V4 glyphs (`\uFC63` = الضالين, `\uFC43` = الرحمن) onto an ARGB_8888 bitmap
   * and verifies that Android Skia/FreeType actually renders chromatic COLR/CPAL layers rather than
   * monochrome black fallback outlines.
   */
  internal fun hasChromaticColrRendering(p1Typeface: Typeface): Boolean {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    return try {
      val canvas = Canvas(bmp)
      val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = p1Typeface
        textSize = 40f
        color = Color.BLACK
        textAlign = Paint.Align.CENTER
      }
      // Page 1 QCF V4 glyphs containing chromatic Tajweed CPAL entries (blue, red, gray)
      canvas.drawText("\uFC63", size * 0.5f, 44f, paint)
      canvas.drawText("\uFC43", size * 0.5f, 84f, paint)

      val pixels = IntArray(size * size)
      bmp.getPixels(pixels, 0, size, 0, 0, size, size)
      var chromaticPixels = 0
      for (px in pixels) {
        val a = (px ushr 24) and 0xFF
        if (a <= 20) continue
        val r = (px ushr 16) and 0xFF
        val g = (px ushr 8) and 0xFF
        val b = px and 0xFF
        val chromaDelta = max(r, max(g, b)) - min(r, min(g, b))
        if (chromaDelta >= 24) {
          chromaticPixels++
          if (chromaticPixels >= 6) return true
        }
      }
      false
    } catch (_: Throwable) {
      false
    } finally {
      bmp.recycle()
    }
  }

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

  fun fontVersionFor(edition: MushafEdition, usesTagFallback: Boolean): String =
    when {
      usesTagFallback -> "tag_fallback_v1"
      edition == MushafEdition.MADINAH_1421 -> "qcf_v2"
      edition == MushafEdition.TAJWEED_V4 -> "qcf_v4_colrv1_v2"
      else -> edition.name
    }

  /**
   * Loads a page Typeface for background global font measurement without evicting the active
   * reader font LRU cache.
   */
  suspend fun loadPageFontForMeasurement(
    page: Int,
    edition: MushafEdition,
    useWhiteInk: Boolean = false,
  ): Typeface? {
    if (page !in 1..604) return null
    val key = cacheKey(page, edition, useWhiteInk)
    memoryCache.get(key)?.let { return it }

    return withContext(Dispatchers.IO) {
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
      runCatching { Typeface.createFromFile(file) }.getOrNull()
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
