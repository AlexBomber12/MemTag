package com.alexbomber12.memtag

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.alexbomber12.memtag.app.MemTagApplication
import com.alexbomber12.memtag.ui.MemTagApp
import com.alexbomber12.memtag.ui.theme.MemTagTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as MemTagApplication).container
        setContent {
            MemTagTheme {
                MemTagApp(appContainer = appContainer)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val appContainer = (application as MemTagApplication).container
        lifecycleScope.launch {
            appContainer.uhfReader.close()
        }
    }
}
