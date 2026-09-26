package com.loupeai.android

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.loupeai.android.ui.HomeScreen
import com.loupeai.android.ui.LoupeTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Dark neon only: light status and navigation bar icons over the ground colour.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            LoupeTheme {
                // Parsing the Public Suffix List takes a moment; keep it off the main thread.
                val summary by produceState<HomeSummary?>(null) {
                    value = withContext(Dispatchers.Default) {
                        HomeSummary.compute().also {
                            Log.i(TAG, "shared engine ok: ${it.templateCount} templates, PSL ${it.pslVersion}, links ${it.links.map { l -> l.level }}")
                        }
                    }
                }
                HomeScreen(summary)
            }
        }
    }

    private companion object {
        const val TAG = "Loupe"
    }
}
