package com.majkeylab.seliadocs

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        window.isNavigationBarContrastEnforced = true
        setContent {
            SeliaDocsApp()
        }
    }

    internal fun setSystemBarIconAppearance(darkTheme: Boolean, darkNavigation: Boolean = darkTheme) {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkNavigation
        }
    }
}
