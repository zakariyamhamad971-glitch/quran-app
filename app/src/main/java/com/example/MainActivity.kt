package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ReaderScreen
import com.example.ui.theme.QabasatTheme
import com.example.viewmodel.QabasatViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
    windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

    setContent {
      val qabasatViewModel: QabasatViewModel = viewModel()
      val themeState by qabasatViewModel.themeStateFlow.collectAsStateWithLifecycle()

      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        QabasatTheme(
          backgroundTheme = themeState.first,
          darkTheme = themeState.second,
        ) {
          ReaderScreen(viewModel = qabasatViewModel)
        }
      }
    }
  }
}
