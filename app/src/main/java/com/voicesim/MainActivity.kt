package com.voicesim

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.voicesim.ui.VoiceChangerScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as VoiceSimApp).container.controller
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                VoiceChangerScreen(controller)
            }
        }
    }
}
