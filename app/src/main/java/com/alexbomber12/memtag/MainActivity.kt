package com.alexbomber12.memtag

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.app.MemTagApplication
import com.alexbomber12.memtag.ui.MemTagApp
import com.alexbomber12.memtag.ui.theme.MemTagTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var rfidKeyCodes: Set<Int> = emptySet()
    private var scanKeyCodes: Set<Int> = emptySet()
    private var deepLinkIntent: Intent? by mutableStateOf(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as MemTagApplication).container
        handleIncomingIntent(intent)
        lifecycleScope.launch {
            appContainer.settingsStore.settingsFlow.collect { settings ->
                rfidKeyCodes = settings.rfidKeyCodeSet()
                scanKeyCodes = settings.scanKeyCodeSet()
            }
        }
        setContent {
            val sessionActive by appContainer.sessionFlagsStore.sessionActive.collectAsStateWithLifecycle(
                initialValue = false,
            )
            LaunchedEffect(sessionActive) {
                if (sessionActive) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            MemTagTheme {
                MemTagApp(
                    appContainer = appContainer,
                    deepLinkIntent = deepLinkIntent,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val appContainer = (application as MemTagApplication).container
        lifecycleScope.launch {
            if (appContainer.uhfReader.initialize().isSuccess) {
                appContainer.uhfReader.applyDesiredConfigBestEffort("activity-start")
            }
        }
        appContainer.syncCoordinator.requestAutoSync(hasNetwork = isNetworkAvailable())
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val appContainer = (application as MemTagApplication).container
            if (rfidKeyCodes.contains(event.keyCode)) {
                appContainer.hardwareKeyDispatcher.tryEmit(HardwareAction.Rfid)
                return true
            }
            if (scanKeyCodes.contains(event.keyCode)) {
                appContainer.hardwareKeyDispatcher.tryEmit(HardwareAction.Scan)
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) {
            return
        }
        val appContainer = (application as MemTagApplication).container
        lifecycleScope.launch {
            appContainer.uhfReader.close()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        deepLinkIntent = intent
    }
}
