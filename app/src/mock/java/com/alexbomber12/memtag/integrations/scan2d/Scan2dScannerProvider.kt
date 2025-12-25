package com.alexbomber12.memtag.integrations.scan2d

import android.content.Context
import com.alexbomber12.memtag.data.settings.SettingsStore

object Scan2dScannerProvider {
    fun create(
        context: Context,
        settingsStore: SettingsStore,
    ): Scan2dScanner = FakeScan2dScanner()
}
