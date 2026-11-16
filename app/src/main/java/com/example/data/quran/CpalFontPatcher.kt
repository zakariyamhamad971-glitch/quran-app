package com.example.data.quran

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Patches OpenType COLRv1 CPAL base-ink (near-black) color entries to either pure Black (White bg)
 * or pure White (Black bg) while preserving all chromatic Tajweed rule color entries untouched.
 * Operates strictly on the binary CPAL color palette table without altering any glyph outlines or mappings.
 */
object CpalFontPatcher {

  fun patchCpalBlackToWhite(ttfBytes: ByteArray): ByteArray =
    patchCpalBaseInk(ttfBytes, targetR = 0xFF, targetG = 0xFF, targetB = 0xFF)

  fun patchCpalBlackToBlack(ttfBytes: ByteArray): ByteArray =
    patchCpalBaseInk(ttfBytes, targetR = 0x00, targetG = 0x00, targetB = 0x00)

  fun patchCpalBaseInk(
    ttfBytes: ByteArray,
    targetR: Int,
    targetG: Int,
    targetB: Int,
  ): ByteArray {
    if (ttfBytes.size < 16) return ttfBytes
    val out = ttfBytes.copyOf()
    val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)

    val numTables = buf.getShort(4).toInt() and 0xFFFF
    var cpalOffset = -1
    var cpalLength = 0
    var cpalRecordOffset = -1
    var headOffset = -1

    for (i in 0 until numTables) {
      val recOffset = 12 + i * 16
      if (recOffset + 16 > out.size) break
      val tag = String(out, recOffset, 4, Charsets.US_ASCII)
      val tblOffset = buf.getInt(recOffset + 8)
      val tblLen = buf.getInt(recOffset + 12)
      if (tag == "CPAL") {
        cpalOffset = tblOffset
        cpalLength = tblLen
        cpalRecordOffset = recOffset
      } else if (tag == "head") {
        headOffset = tblOffset
      }
    }

    if (cpalOffset < 0 || cpalOffset + 12 > out.size) return ttfBytes

    val numColorRecords = buf.getShort(cpalOffset + 6).toInt() and 0xFFFF
    val colorRecordsRel = buf.getInt(cpalOffset + 8)
    val colorRecordsStart = cpalOffset + colorRecordsRel

    if (colorRecordsStart < 0 || colorRecordsStart + numColorRecords * 4 > out.size) {
      return ttfBytes
    }

    var modified = false
    for (i in 0 until numColorRecords) {
      val idx = colorRecordsStart + i * 4
      // OpenType CPAL ColorRecord is BGRA
      val b = out[idx].toInt() and 0xFF
      val g = out[idx + 1].toInt() and 0xFF
      val r = out[idx + 2].toInt() and 0xFF
      val a = out[idx + 3].toInt() and 0xFF

      // Patch dark/black base ink entries to pure Black or White, keeping all chromatic and gray Tajweed entries intact
      val isNearBlack = r <= 75 && g <= 75 && b <= 75 && a >= 180
      if (isNearBlack) {
        out[idx] = targetB.toByte()
        out[idx + 1] = targetG.toByte()
        out[idx + 2] = targetR.toByte()
        out[idx + 3] = 0xFF.toByte()
        modified = true
      }
    }

    if (!modified) return ttfBytes

    // Recompute CPAL table checksum
    if (cpalRecordOffset >= 0 && cpalOffset + cpalLength <= out.size) {
      val cpalSum = computeTableChecksum(out, cpalOffset, cpalLength)
      buf.putInt(cpalRecordOffset + 4, cpalSum)
    }

    // Recompute head.checkSumAdjustment if head table exists
    if (headOffset >= 0 && headOffset + 12 <= out.size) {
      buf.putInt(headOffset + 8, 0)
      val wholeSum = computeTableChecksum(out, 0, out.size)
      buf.putInt(headOffset + 8, (0xB1B0AFBAL - wholeSum.toLong()).toInt())
    }

    return out
  }

  private fun computeTableChecksum(data: ByteArray, offset: Int, length: Int): Int {
    var sum = 0L
    val end = offset + length
    var i = offset
    while (i < end) {
      val b0 = if (i < end) data[i].toLong() and 0xFF else 0L
      val b1 = if (i + 1 < end) data[i + 1].toLong() and 0xFF else 0L
      val b2 = if (i + 2 < end) data[i + 2].toLong() and 0xFF else 0L
      val b3 = if (i + 3 < end) data[i + 3].toLong() and 0xFF else 0L
      val word = (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
      sum = (sum + word) and 0xFFFFFFFFL
      i += 4
    }
    return sum.toInt()
  }
}
