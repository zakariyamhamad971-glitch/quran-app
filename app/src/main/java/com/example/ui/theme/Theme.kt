package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.data.prefs.BackgroundTheme

private val QabasatWhiteColorScheme = lightColorScheme(
  primary = QabasatDeepGreen,
  onPrimary = Color.White,
  primaryContainer = QabasatGoldSoft,
  onPrimaryContainer = QabasatDeepGreen,
  secondary = QabasatGold,
  onSecondary = QabasatDeepGreenDark,
  secondaryContainer = Color(0xFFECE6D8),
  onSecondaryContainer = QabasatWhiteInk,
  tertiary = QabasatDeepGreenDark,
  onTertiary = Color.White,
  background = QabasatWhiteBg,
  onBackground = QabasatWhiteInk,
  surface = QabasatWhiteSurface,
  onSurface = QabasatWhiteInk,
  surfaceVariant = QabasatWhiteSurfaceVar,
  onSurfaceVariant = QabasatWhiteMutedInk,
  outline = QabasatWhiteBorder,
  error = QabasatError,
  onError = Color.White,
)

private val QabasatBlackColorScheme = darkColorScheme(
  primary = QabasatNightGold,
  onPrimary = Color.Black,
  primaryContainer = Color(0xFF1A3D39),
  onPrimaryContainer = QabasatBlackInk,
  secondary = QabasatNightGreen,
  onSecondary = QabasatBlackInk,
  secondaryContainer = QabasatBlackSurfaceVar,
  onSecondaryContainer = QabasatBlackInk,
  tertiary = QabasatNightGold,
  onTertiary = Color.Black,
  background = QabasatBlackBg,
  onBackground = QabasatBlackInk,
  surface = QabasatBlackSurface,
  onSurface = QabasatBlackInk,
  surfaceVariant = QabasatBlackSurfaceVar,
  onSurfaceVariant = QabasatBlackMutedInk,
  outline = QabasatBlackBorder,
  error = QabasatErrorDark,
  onError = Color(0xFF601410),
)

@Composable
fun QabasatTheme(
  backgroundTheme: BackgroundTheme = BackgroundTheme.WHITE,
  darkTheme: Boolean = false,
  content: @Composable () -> Unit,
) {
  val useBlack = darkTheme || backgroundTheme == BackgroundTheme.BLACK
  val colorScheme = if (useBlack) QabasatBlackColorScheme else QabasatWhiteColorScheme
  MaterialTheme(
    colorScheme = colorScheme,
    typography = QabasatTypography,
    content = content,
  )
}
