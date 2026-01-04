package com.alexbomber12.memtag.integrations.scan2d

import android.content.Context
import android.os.Looper
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.zebra.adc.decoder.Barcode2DWithSoft
import kotlinx.coroutines.CoroutineDispatcher

object Scan2dScannerProvider {
    fun create(
        context: Context,
        settingsStore: SettingsStore,
        ioDispatcher: CoroutineDispatcher,
    ): Scan2dScanner {
        val hasSoftScanner =
            runCatching { Class.forName(SOFT_SCANNER_CLASS) }.isSuccess
        val canUseSoftScanner = hasSoftScanner && canOpenSoftScanner(context)
        return if (canUseSoftScanner) {
            ChainwaySoft2dScan2dScanner(context)
        } else {
            ChainwayBroadcastScan2dScanner(context, settingsStore, ioDispatcher)
        }
    }

    private fun canOpenSoftScanner(context: Context): Boolean {
        if (Looper.getMainLooper().thread != Thread.currentThread()) {
            return false
        }
        return runCatching {
            val scanner = Barcode2DWithSoft.getInstance()
            val opened = scanner.open(context.applicationContext)
            if (opened) {
                runCatching { scanner.close() }
            }
            opened
        }.getOrDefault(false)
    }

    private const val SOFT_SCANNER_CLASS = "com.zebra.adc.decoder.Barcode2DWithSoft"
}
