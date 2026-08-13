package com.mtzallqmy.aiagent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mtzallqmy.aiagent.ui.AegisTheme
import com.mtzallqmy.aiagent.ui.screens.AegisNavHost

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AegisNavHost()
                }
            }
        }
    }
}
