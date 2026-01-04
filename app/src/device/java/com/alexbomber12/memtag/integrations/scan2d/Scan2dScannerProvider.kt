package com.alexbomber12.memtag.integrations.scan2d

import android.content.Context
import com.alexbomber12.memtag.data.settings.SettingsStore
import kotlinx.coroutines.CoroutineDispatcher

object Scan2dScannerProvider {
    fun create(
        context: Context,
        settingsStore: SettingsStore,
        ioDispatcher: CoroutineDispatcher,
    ): Scan2dScanner {
        val hasSoftScanner =
            runCatching { Class.forName(SOFT_SCANNER_CLASS) }.isSuccess
        return if (hasSoftScanner) {
            ChainwaySoft2dScan2dScanner(context)
        } else {
            ChainwayBroadcastScan2dScanner(context, settingsStore, ioDispatcher)
        }
    }

    private const val SOFT_SCANNER_CLASS = "com.zebra.adc.decoder.Barcode2DWithSoft"
}
