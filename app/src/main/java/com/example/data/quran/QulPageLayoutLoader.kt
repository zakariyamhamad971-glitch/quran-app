package com.example.data.quran

import android.content.Context
import androidx.compose.runtime.Immutable
import com.squareup.moshi.JsonReader
import okio.Buffer
import java.util.concurrent.ConcurrentHashMap

@Immutable
data class QulLayoutLine(
  val line: Int,
  val type: String,
  val centered: Boolean,
  val first: Int? = null,
  val last: Int? = null,
  val surah: Int? = null,
)

object QulPageLayoutLoader {
  private val cache = ConcurrentHashMap<MushafEdition, Map<Int, List<QulLayoutLine>>>()

  fun assetPathFor(edition: MushafEdition): String = when (edition) {
    MushafEdition.MADINAH_1421 -> "quran/layout_madinah_v2.json"
    MushafEdition.TAJWEED_V4 -> "quran/layout_tajweed_v4.json"
  }

  fun getLayoutForEdition(context: Context, edition: MushafEdition): Map<Int, List<QulLayoutLine>> {
    cache[edition]?.let { return it }
    val json = context.assets.open(assetPathFor(edition)).bufferedReader().use { it.readText() }
    val parsed = parseLayoutJson(json)
    cache.putIfAbsent(edition, parsed)
    return cache[edition] ?: parsed
  }

  fun getPageLines(context: Context, edition: MushafEdition, pageNumber: Int): List<QulLayoutLine> =
    getLayoutForEdition(context, edition)[pageNumber].orEmpty()

  fun parseLayoutJson(json: String): Map<Int, List<QulLayoutLine>> {
    val result = HashMap<Int, List<QulLayoutLine>>(604)
    val reader = JsonReader.of(Buffer().writeUtf8(json))
    reader.use { r ->
      r.beginObject()
      while (r.hasNext()) {
        val topKey = r.nextName()
        if (topKey == "pages") {
          r.beginObject()
          while (r.hasNext()) {
            val pageKey = r.nextName()
            val pageNum = pageKey.toIntOrNull()
            if (pageNum == null) {
              r.skipValue()
              continue
            }
            val lines = ArrayList<QulLayoutLine>(15)
            r.beginArray()
            while (r.hasNext()) {
              var lineNo = 0
              var type = ""
              var centered = false
              var first: Int? = null
              var last: Int? = null
              var surah: Int? = null
              r.beginObject()
              while (r.hasNext()) {
                when (r.nextName()) {
                  "line" -> lineNo = r.nextInt()
                  "type" -> type = r.nextString()
                  "centered" -> centered = if (r.peek() == JsonReader.Token.BOOLEAN) r.nextBoolean() else r.nextInt() == 1
                  "first" -> first = if (r.peek() == JsonReader.Token.NULL) { r.nextNull<Any?>(); null } else r.nextInt()
                  "last" -> last = if (r.peek() == JsonReader.Token.NULL) { r.nextNull<Any?>(); null } else r.nextInt()
                  "surah" -> surah = if (r.peek() == JsonReader.Token.NULL) { r.nextNull<Any?>(); null } else r.nextInt()
                  else -> r.skipValue()
                }
              }
              r.endObject()
              lines += QulLayoutLine(
                line = lineNo,
                type = type,
                centered = centered,
                first = first,
                last = last,
                surah = surah,
              )
            }
            r.endArray()
            lines.sortBy { it.line }
            result[pageNum] = lines
          }
          r.endObject()
        } else {
          r.skipValue()
        }
      }
      r.endObject()
    }
    return result
  }
}
