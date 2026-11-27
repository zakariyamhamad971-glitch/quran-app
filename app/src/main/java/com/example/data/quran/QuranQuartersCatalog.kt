package com.example.data.quran

object QuranQuartersCatalog {
  // Exact 240 starting pages of the 240 Rub' el Hizb in the standard 604-page Madinah Mushaf
  val quarterStartPages: IntArray = intArrayOf(
    1, 5, 7, 9, 11, 14, 17, 19, 22, 24, 27, 29, 32, 34, 37, 39,
    42, 44, 46, 49, 51, 54, 56, 59, 62, 64, 67, 69, 72, 74, 77, 79,
    82, 84, 87, 89, 92, 94, 97, 100, 102, 104, 106, 109, 112, 114, 117, 119,
    121, 124, 126, 129, 132, 134, 137, 140, 142, 144, 146, 148, 151, 154, 156, 158,
    162, 164, 167, 170, 173, 175, 177, 179, 182, 184, 187, 189, 192, 194, 196, 199,
    201, 204, 206, 209, 212, 214, 217, 219, 222, 224, 226, 228, 231, 233, 236, 238,
    242, 244, 247, 249, 252, 254, 256, 259, 262, 264, 267, 270, 272, 275, 277, 280,
    282, 284, 287, 289, 292, 295, 297, 299, 302, 304, 306, 309, 312, 315, 317, 319,
    322, 324, 326, 329, 332, 334, 336, 339, 342, 344, 347, 350, 352, 354, 356, 359,
    362, 364, 367, 369, 371, 374, 377, 379, 382, 384, 386, 389, 392, 394, 396, 399,
    402, 404, 407, 410, 413, 415, 418, 420, 422, 425, 426, 429, 431, 433, 436, 439,
    442, 444, 446, 449, 451, 454, 456, 459, 462, 464, 467, 469, 472, 474, 477, 479,
    482, 484, 486, 488, 491, 493, 496, 499, 502, 505, 507, 510, 513, 515, 517, 519,
    522, 524, 526, 529, 531, 534, 536, 539, 542, 544, 547, 550, 553, 554, 558, 560,
    562, 564, 566, 569, 572, 575, 577, 579, 582, 585, 587, 589, 591, 594, 596, 599
  )

  private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  fun toArabicIndic(n: Int): String {
    val s = n.toString()
    return buildString(s.length) {
      for (ch in s) {
        append(if (ch in '0'..'9') arabicDigits[ch - '0'] else ch)
      }
    }
  }

  /**
   * If [page] begins a quarter, returns the short banner text for it:
   * e.g. "الجزء K", "الحزب N", "ربع الحزب N", "نصف الحزب N", "ثلاثة أرباع الحزب N".
   * Returns null if page is not the start of a quarter.
   */
  fun getQuarterBannerForPage(page: Int): String? {
    if (page !in 1..604) return null
    val idx = quarterStartPages.indexOf(page)
    if (idx < 0) return null
    val rubNumber = idx + 1
    val hizbNumber = (rubNumber - 1) / 4 + 1
    val posInHizb = (rubNumber - 1) % 4
    val isJuzStart = (rubNumber - 1) % 8 == 0
    val juzNumber = (hizbNumber - 1) / 2 + 1

    return when {
      isJuzStart -> "الجزء ${toArabicIndic(juzNumber)}"
      posInHizb == 0 -> "الحزب ${toArabicIndic(hizbNumber)}"
      posInHizb == 1 -> "ربع الحزب ${toArabicIndic(hizbNumber)}"
      posInHizb == 2 -> "نصف الحزب ${toArabicIndic(hizbNumber)}"
      else -> "ثلاثة أرباع الحزب ${toArabicIndic(hizbNumber)}"
    }
  }

  /**
   * Returns the header text: "الجزء K • [الربع] الحزب N"
   * based on the latest quarter at or before the first verse of the page.
   */
  fun getHeaderQuarterText(page: Int): String {
    val p = page.coerceIn(1, 604)
    var latestIdx = 0
    for (i in quarterStartPages.indices) {
      if (quarterStartPages[i] <= p) {
        latestIdx = i
      } else {
        break
      }
    }
    val rubNumber = latestIdx + 1
    val hizbNumber = (rubNumber - 1) / 4 + 1
    val posInHizb = (rubNumber - 1) % 4
    val juzNumber = (hizbNumber - 1) / 2 + 1

    val hizbPart = when (posInHizb) {
      0 -> "الحزب ${toArabicIndic(hizbNumber)}"
      1 -> "ربع الحزب ${toArabicIndic(hizbNumber)}"
      2 -> "نصف الحزب ${toArabicIndic(hizbNumber)}"
      else -> "ثلاثة أرباع الحزب ${toArabicIndic(hizbNumber)}"
    }
    return "الجزء ${toArabicIndic(juzNumber)} • $hizbPart"
  }
}
