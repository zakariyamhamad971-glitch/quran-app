package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ReaderScreen
import com.example.ui.theme.QabasatTheme
import com.example.viewmodel.QabasatViewModel

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val qabasatViewModel: QabasatViewModel = viewModel()
      val uiState by qabasatViewModel.uiState.collectAsStateWithLifecycle()

      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        QabasatTheme(
          backgroundTheme = uiState.backgroundTheme,
          darkTheme = uiState.isDarkMode,
        ) {
          ReaderScreen(viewModel = qabasatViewModel)
        }
      }
    }
  }
}
