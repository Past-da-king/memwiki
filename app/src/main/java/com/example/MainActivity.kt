package com.example

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.WikiAppScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.WikiViewModel
import com.example.viewmodel.WikiViewModelFactory

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val viewModel: WikiViewModel = viewModel(
        factory = WikiViewModelFactory(applicationContext as Application)
      )
      // Theme mode is user-controlled in Settings (Default / Adaptive / Editorial).
      val themeMode by viewModel.appThemeMode.collectAsStateWithLifecycle()
      MyApplicationTheme(themeMode = themeMode) {
        WikiAppScreen(
          viewModel = viewModel,
          modifier = Modifier.fillMaxSize()
        )
      }
    }
  }
}

