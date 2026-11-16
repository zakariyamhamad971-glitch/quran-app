package com.example.data.quran

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Fallback parser for AlQuran Cloud `quran-tajweed` markup (`[tag:id[text]]` or `[tag[text]]`)
 * used ONLY on older Android devices (API < 33) that lack native Skia COLRv1 color font rendering.
 * Never mutates, drops, or alters any Arabic character or diacritic inside the brackets.
 */
object TajweedTagParser {

  private fun colorForCode(code: Char, defaultColor: Color): Color = when (code) {
    'h', 'l', 's' -> Color(0xFF8F9995) // Hamzat Wasl / Silent / Laam Shamsiyyah
    'n', 'g', 'f', 'c', 'a', 'u', 'w' -> Color(0xFF169200) // Ghunnah / Ikhfa / Idgham with Ghunnah
    'q' -> Color(0xFFDD0008) // Qalqalah
    'm' -> Color(0xFF000EBC) // Madd Lazim
    'o', 'p' -> Color(0xFFD500B7) // Madd Wajib / Jaiz
    'i' -> Color(0xFF0099CC) // Iqlab
    else -> defaultColor
  }

  fun parseToAnnotatedString(rawTaggedText: String, baseColor: Color): AnnotatedString {
    return buildAnnotatedString {
      var i = 0
      val len = rawTaggedText.length
      while (i < len) {
        val ch = rawTaggedText[i]
        if (ch == '[') {
          val openInner = rawTaggedText.indexOf('[', i + 1)
          val closeInner = if (openInner != -1) rawTaggedText.indexOf(']', openInner + 1) else -1
          if (openInner != -1 && closeInner != -1 && !rawTaggedText.substring(i + 1, openInner).contains(']')) {
            val tagHeader = rawTaggedText.substring(i + 1, openInner)
            val content = rawTaggedText.substring(openInner + 1, closeInner)
            val ruleCode = tagHeader.firstOrNull() ?: ' '
            val spanColor = colorForCode(ruleCode, baseColor)
            withStyle(SpanStyle(color = spanColor)) {
              append(content)
            }
            i = if (closeInner + 1 < len && rawTaggedText[closeInner + 1] == ']') {
              closeInner + 2
            } else {
              closeInner + 1
            }
            continue
          }
        }
        append(ch)
        i++
      }
    }
  }
}
