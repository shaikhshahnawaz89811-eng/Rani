package com.sa.aidesktop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.sa.aidesktop.core.python.EmbeddedPythonEngine
import com.sa.aidesktop.ui.SADesktopApp
import com.sa.aidesktop.ui.theme.SADesktopTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Starts the real embedded CPython interpreter (Chaquopy) once, up front, so the
        // Terminal window and Developer Workspace "Run" button can execute `python`/`python3`
        // for real as soon as the desktop UI is shown.
        EmbeddedPythonEngine.ensureStarted(this)
        setContent { SADesktopTheme { SADesktopApp() } }
    }
}
