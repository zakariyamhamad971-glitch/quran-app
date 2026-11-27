package com.example.data.quran

import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Fallback parser for AlQuran Cloud `quran-tajweed` markup (`[tag:id[text]]` or `[tag[text]]`)
 * using the official `tajweed_legend.json` color palette.
 * Never mutates, drops, or alters any Arabic character or diacritic inside the brackets.
 */
object TajweedTagParser {

  private const val COLOR_GHUNNAH_LIGHT = 0xFF169200.toInt()
  private const val COLOR_QALQALAH_LIGHT = 0xFFDD0008.toInt()
  private const val COLOR_MADD_LAZIM_LIGHT = 0xFF000EBC.toInt()
  private const val COLOR_MADD_WAJIB_LIGHT = 0xFFD500B7.toInt()
  private const val COLOR_MADD_NORMAL_LIGHT = 0xFF4B6FD4.toInt()
  private const val COLOR_IQLAB_LIGHT = 0xFF0099CC.toInt()
  private const val COLOR_SILENT_LIGHT = 0xFF8F9995.toInt()

  private const val COLOR_GHUNNAH_DARK = 0xFF28C746.toInt()
  private const val COLOR_QALQALAH_DARK = 0xFFFF5252.toInt()
  private const val COLOR_MADD_LAZIM_DARK = 0xFF5C7CFF.toInt()
  private const val COLOR_MADD_WAJIB_DARK = 0xFFF062E5.toInt()
  private const val COLOR_MADD_NORMAL_DARK = 0xFF80A4FF.toInt()
  private const val COLOR_IQLAB_DARK = 0xFF33C5F0.toInt()
  private const val COLOR_SILENT_DARK = 0xFFA8B2AE.toInt()

  data class TaggedToken(
    val plainText: String,
    val charColors: IntArray,
  )

  fun argbForCode(code: Char, effectiveDark: Boolean = false): Int = when (code) {
    // Hamzat Wasl, Lam Shamsiyyah, Silent letters, Idgham without Ghunnah (u, d)
    'h', 'l', 's', 'u', 'd' -> if (effectiveDark) COLOR_SILENT_DARK else COLOR_SILENT_LIGHT
    // Ghunnah, Ikhfa, Ikhfa Shafawi, Idgham with Ghunnah, Idgham Shafawi
    'g', 'f', 'c', 'a', 'w' -> if (effectiveDark) COLOR_GHUNNAH_DARK else COLOR_GHUNNAH_LIGHT
    // Qalqalah
    'q' -> if (effectiveDark) COLOR_QALQALAH_DARK else COLOR_QALQALAH_LIGHT
    // Madd Lazim (6 harakat)
    'm' -> if (effectiveDark) COLOR_MADD_LAZIM_DARK else COLOR_MADD_LAZIM_LIGHT
    // Madd Wajib / Jaiz (4-5 harakat)
    'o' -> if (effectiveDark) COLOR_MADD_WAJIB_DARK else COLOR_MADD_WAJIB_LIGHT
    // Madd Aarid / Normal / Permissible (2-4-6)
    'n', 'p' -> if (effectiveDark) COLOR_MADD_NORMAL_DARK else COLOR_MADD_NORMAL_LIGHT
    // Iqlab
    'i' -> if (effectiveDark) COLOR_IQLAB_DARK else COLOR_IQLAB_LIGHT
    else -> 0
  }

  private fun colorForCode(code: Char, defaultColor: Color, effectiveDark: Boolean = false): Color {
    val argb = argbForCode(code, effectiveDark)
    return if (argb == 0) defaultColor else Color(argb)
  }

  /**
   * Parses raw AlQuran Cloud tagged ayah text into stripped plain characters + per-character ARGB colors.
   * Supports nested tags (e.g. `[o[ُوٓ[s[اْ]‌ۚ]`).
   */
  fun parseAyahToTaggedTokens(
    rawTaggedText: String,
    effectiveDark: Boolean = false,
  ): List<TaggedToken> {
    if (rawTaggedText.isEmpty()) return emptyList()
    val plainBuf = StringBuilder(rawTaggedText.length)
    val colorList = ArrayList<Int>(rawTaggedText.length)
    val colorStack = ArrayList<Int>(4)

    val usesDoubleClosingBrackets = countChar(rawTaggedText, '[') == countChar(rawTaggedText, ']')
    var i = 0
    val len = rawTaggedText.length
    while (i < len) {
      val ch = rawTaggedText[i]
      if (ch == '[') {
        val openInner = rawTaggedText.indexOf('[', i + 1)
        if (openInner != -1) {
          val header = rawTaggedText.substring(i + 1, openInner)
          if (header.isNotEmpty() && !header.contains(']') && !header.contains(' ')) {
            val ruleCode = header[0]
            val argb = argbForCode(ruleCode, effectiveDark)
            colorStack.add(argb)
            if (usesDoubleClosingBrackets) {
              colorStack.add(argb)
            }
            i = openInner + 1
            continue
          }
        }
      }
      if (ch == ']' && colorStack.isNotEmpty()) {
        colorStack.removeAt(colorStack.lastIndex)
        i++
        continue
      }
      plainBuf.append(ch)
      colorList.add(colorStack.lastOrNull() ?: 0)
      i++
    }

    val tokens = ArrayList<TaggedToken>(16)
    var tokenStart = -1
    for (idx in 0..plainBuf.length) {
      val isSep = idx == plainBuf.length || plainBuf[idx].isWhitespace()
      if (!isSep) {
        if (tokenStart < 0) tokenStart = idx
      } else if (tokenStart >= 0) {
        val tokStr = plainBuf.substring(tokenStart, idx)
        val tokColors = IntArray(tokStr.length) { k -> colorList[tokenStart + k] }
        tokens.add(TaggedToken(tokStr, tokColors))
        tokenStart = -1
      }
    }
    return tokens
  }

  /**
   * Maps Tajweed rule colors from parsed AlQuran Cloud tokens onto the exact official
   * `wordTextUthmani` string without altering a single Quranic character or diacritic.
   */
  fun buildWordSpannable(
    wordTextUthmani: String,
    wordPosition1Based: Int,
    ayahTokens: List<TaggedToken>?,
  ): CharSequence {
    if (wordTextUthmani.isEmpty() || ayahTokens.isNullOrEmpty()) return wordTextUthmani

    val matchedToken = findBestMatchingToken(wordTextUthmani, wordPosition1Based, ayahTokens)
      ?: return wordTextUthmani

    var hasAnyColor = false
    for (c in matchedToken.charColors) {
      if (c != 0) {
        hasAnyColor = true
        break
      }
    }
    if (!hasAnyColor) return wordTextUthmani

    val mappedColors = if (matchedToken.plainText == wordTextUthmani) {
      matchedToken.charColors
    } else {
      mapColorsByBaseLetters(wordTextUthmani, matchedToken)
    }

    var anyMapped = false
    for (c in mappedColors) {
      if (c != 0) {
        anyMapped = true
        break
      }
    }
    if (!anyMapped) return wordTextUthmani

    val spannable = SpannableString(wordTextUthmani)
    var runStart = -1
    var runColor = 0
    for (idx in 0..mappedColors.size) {
      val cur = if (idx < mappedColors.size) mappedColors[idx] else 0
      if (cur != runColor) {
        if (runStart >= 0 && runColor != 0) {
          spannable.setSpan(
            ForegroundColorSpan(runColor),
            runStart,
            idx,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
          )
        }
        runStart = if (cur != 0) idx else -1
        runColor = cur
      }
    }
    return spannable
  }

  private fun findBestMatchingToken(
    wordTextUthmani: String,
    wordPosition1Based: Int,
    ayahTokens: List<TaggedToken>,
  ): TaggedToken? {
    val targetIdx = (wordPosition1Based - 1).coerceIn(0, ayahTokens.lastIndex)
    val direct = ayahTokens[targetIdx]
    if (direct.plainText == wordTextUthmani) return direct

    val targetBase = extractBaseLetters(wordTextUthmani)
    if (targetBase.isEmpty()) return direct
    if (extractBaseLetters(direct.plainText) == targetBase) return direct

    val start = (targetIdx - 3).coerceAtLeast(0)
    val end = (targetIdx + 3).coerceAtMost(ayahTokens.lastIndex)
    for (idx in start..end) {
      val cand = ayahTokens[idx]
      if (extractBaseLetters(cand.plainText) == targetBase) return cand
    }
    for (idx in start..end) {
      val cand = ayahTokens[idx]
      val candBase = extractBaseLetters(cand.plainText)
      if (candBase.contains(targetBase) || targetBase.contains(candBase)) return cand
    }
    return direct
  }

  private fun isLetterForColorAlignment(ch: Char): Boolean {
    val code = ch.code
    // Arabic base letters + Alef Wasla (U+0671) + superscript Alef (U+0670) + small Waw/Ya (U+06E5, U+06E6)
    return (code in 0x0621..0x063F) ||
      (code in 0x0641..0x064A) ||
      code == 0x0670 ||
      code == 0x0671 ||
      code == 0x06E5 ||
      code == 0x06E6
  }

  private fun normalizeBaseChar(ch: Char): Char = when (ch) {
    '\u0671', '\u0622', '\u0623', '\u0625', '\u0670' -> '\u0627'
    '\u0629' -> '\u0647'
    '\u0649', '\u06CC' -> '\u064A'
    else -> ch
  }

  private fun extractBaseLetters(text: String): String = buildString(text.length) {
    for (ch in text) {
      val c = ch.code
      if ((c in 0x0621..0x063F) || (c in 0x0641..0x064A) || c == 0x0671) {
        append(normalizeBaseChar(ch))
      }
    }
  }

  private fun mapColorsByBaseLetters(
    target: String,
    src: TaggedToken,
  ): IntArray {
    val result = IntArray(target.length)
    data class CharCluster(val startIdx: Int, val endIdx: Int, val color: Int, val base: Char)

    fun extractClusters(text: String, colors: IntArray?): List<CharCluster> {
      val list = ArrayList<CharCluster>(text.length)
      var i = 0
      while (i < text.length) {
        val ch = text[i]
        if (isLetterForColorAlignment(ch)) {
          val start = i
          var clusterColor = colors?.getOrNull(i) ?: 0
          i++
          while (i < text.length && !isLetterForColorAlignment(text[i])) {
            if (clusterColor == 0 && colors != null) {
              val c = colors[i]
              if (c != 0) clusterColor = c
            }
            i++
          }
          list.add(CharCluster(start, i, clusterColor, normalizeBaseChar(ch)))
        } else {
          i++
        }
      }
      return list
    }

    val dstClusters = extractClusters(target, null)
    val srcClusters = extractClusters(src.plainText, src.charColors)
    if (dstClusters.isEmpty() || srcClusters.isEmpty()) return result

    var sIdx = 0
    for (dCluster in dstClusters) {
      while (sIdx < srcClusters.size &&
        srcClusters[sIdx].base != dCluster.base &&
        sIdx + 1 < srcClusters.size &&
        srcClusters[sIdx + 1].base == dCluster.base
      ) {
        sIdx++
      }
      val chosen = srcClusters.getOrNull(sIdx)
      if (chosen != null && chosen.color != 0 && chosen.base == dCluster.base) {
        for (pos in dCluster.startIdx until dCluster.endIdx) {
          result[pos] = chosen.color
        }
      }
      if (chosen != null && chosen.base == dCluster.base) {
        sIdx++
      }
    }
    return result
  }

  fun parseToAnnotatedString(
    rawTaggedText: String,
    baseColor: Color,
    effectiveDark: Boolean = false,
  ): AnnotatedString {
    val tokens = parseAyahToTaggedTokens(rawTaggedText, effectiveDark = effectiveDark)
    if (tokens.isEmpty()) return AnnotatedString(rawTaggedText)
    val usesDoubleClosingBrackets = countChar(rawTaggedText, '[') == countChar(rawTaggedText, ']')
    return buildAnnotatedString {
      var i = 0
      val len = rawTaggedText.length
      val stack = ArrayList<Color>(4)
      while (i < len) {
        val ch = rawTaggedText[i]
        if (ch == '[') {
          val openInner = rawTaggedText.indexOf('[', i + 1)
          if (openInner != -1) {
            val header = rawTaggedText.substring(i + 1, openInner)
            if (header.isNotEmpty() && !header.contains(']') && !header.contains(' ')) {
              val ruleCode = header[0]
              val ruleColor = colorForCode(ruleCode, baseColor, effectiveDark)
              stack.add(ruleColor)
              if (usesDoubleClosingBrackets) {
                stack.add(ruleColor)
              }
              i = openInner + 1
              continue
            }
          }
        }
        if (ch == ']' && stack.isNotEmpty()) {
          stack.removeAt(stack.lastIndex)
          i++
          continue
        }
        val activeColor = stack.lastOrNull()
        if (activeColor != null) {
          withStyle(SpanStyle(color = activeColor)) {
            append(ch)
          }
        } else {
          append(ch)
        }
        i++
      }
    }
  }

  private fun countChar(text: String, target: Char): Int {
    var count = 0
    for (ch in text) {
      if (ch == target) count++
    }
    return count
  }
}
