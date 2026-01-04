package com.alexbomber12.memtag.integrations.scan2d

import android.content.Context
import com.zebra.adc.decoder.Barcode2DWithSoft
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class ChainwaySoft2dScan2dScanner(
    context: Context,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : Scan2dScanner {
    private val delegate =
        Soft2dScan2dScanner(
            adapter = Barcode2DWithSoftAdapter(context.applicationContext),
            mainDispatcher = mainDispatcher,
        )

    override suspend fun scanOnce(
        timeoutMs: Long,
        source: String,
    ): Result<String> = delegate.scanOnce(timeoutMs, source)
}

private class Barcode2DWithSoftAdapter(
    context: Context,
) : Soft2dScannerAdapter {
    private val appContext = context.applicationContext
    private val scanner: Barcode2DWithSoft by lazy { Barcode2DWithSoft.getInstance() }

    override fun open(): Boolean = scanner.open(appContext)

    override fun setDefaultParameters() {
        scanner.setDefaultParameters()
    }

    override fun enableAllCodeTypes() {
        scanner.enableAllCodeTypes()
    }

    override fun setTimeOut(seconds: Int) {
        scanner.setTimeOut(seconds)
    }

    override fun setScanCallback(callback: Soft2dScanCallback) {
        scanner.setScanCallback { symbology, length, data ->
            callback.onScanComplete(symbology, length, data)
        }
    }

    override fun scan() {
        scanner.scan()
    }

    override fun stopScan() {
        scanner.stopScan()
    }

    override fun close(): Boolean = scanner.close()
}
