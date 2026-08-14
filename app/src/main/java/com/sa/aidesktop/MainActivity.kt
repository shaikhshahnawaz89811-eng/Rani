package com.sa.aidesktop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sa.aidesktop.ui.SADesktopApp
import com.sa.aidesktop.ui.theme.SADesktopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SADesktopTheme { SADesktopApp() } }
    }
}
