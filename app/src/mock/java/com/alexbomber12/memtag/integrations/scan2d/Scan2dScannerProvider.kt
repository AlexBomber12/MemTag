package com.alexbomber12.memtag.integrations.scan2d

import android.content.Context
import com.alexbomber12.memtag.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher

object Scan2dScannerProvider {
    fun create(
        context: Context,
        settingsStore: SettingsStore,
        ioDispatcher: CoroutineDispatcher,
    ): Scan2dScanner = FakeScan2dScanner()
}
