package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.R

// UI font strictly for navigation, buttons, labels, and numbers (never applied to Quran verses)
val IbmPlexSansArabicFamily = FontFamily(
  Font(R.font.ibm_plex_sans_arabic, FontWeight.Normal),
)

// Surah header title font (never applied to Quran verse body lines)
val AmiriFontFamily = FontFamily(
  Font(R.font.amiri, FontWeight.Normal),
)

val QabasatTypography = Typography(
  headlineMedium = TextStyle(
    fontFamily = AmiriFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 24.sp,
    lineHeight = 34.sp,
  ),
  headlineSmall = TextStyle(
    fontFamily = AmiriFontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 20.sp,
    lineHeight = 28.sp,
  ),
  titleLarge = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 18.sp,
    lineHeight = 26.sp,
  ),
  titleMedium = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  ),
  titleSmall = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 13.sp,
    lineHeight = 18.sp,
  ),
  bodyLarge = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 15.sp,
    lineHeight = 22.sp,
  ),
  bodyMedium = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 13.sp,
    lineHeight = 20.sp,
  ),
  bodySmall = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 11.sp,
    lineHeight = 16.sp,
  ),
  labelLarge = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 13.sp,
    lineHeight = 18.sp,
  ),
  labelMedium = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 11.sp,
    lineHeight = 16.sp,
  ),
  labelSmall = TextStyle(
    fontFamily = IbmPlexSansArabicFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 14.sp,
  ),
)
