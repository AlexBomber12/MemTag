package com.alexbomber12.memtag

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.alexbomber12.memtag.app.HardwareAction
import com.alexbomber12.memtag.app.MemTagApplication
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import com.alexbomber12.memtag.ui.MemTagApp
import com.alexbomber12.memtag.ui.theme.MemTagTheme
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var rfidKeyCodes: Set<Int> = emptySet()
    private var scanKeyCodes: Set<Int> = emptySet()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as MemTagApplication).container
        lifecycleScope.launch {
            appContainer.settingsStore.settingsFlow.collect { settings ->
                rfidKeyCodes = settings.rfidKeyCodeSet()
                scanKeyCodes = settings.scanKeyCodeSet()
            }
        }
        setContent {
            MemTagTheme {
                MemTagApp(appContainer = appContainer)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val appContainer = (application as MemTagApplication).container
        lifecycleScope.launch {
            val settings = appContainer.settingsStore.settingsFlow.first()
            val initResult = appContainer.uhfReader.initialize()
            if (initResult.isSuccess) {
                appContainer.uhfReader.setPower(settings.uhfPower)
                appContainer.uhfReader.setRegion(UhfRegion.fromSettings(settings.uhfRegion))
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
}
