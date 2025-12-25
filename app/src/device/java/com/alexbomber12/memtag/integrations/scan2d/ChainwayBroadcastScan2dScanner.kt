package com.alexbomber12.memtag.integrations.scan2d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.rscja.barcode.BarcodeUtility
import com.rscja.deviceapi.Barcode2D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ChainwayBroadcastScan2dScanner(
    context: Context,
    private val settingsStore: SettingsStore,
) : Scan2dScanner {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var scanInProgress = false

    override suspend fun scanOnce(timeoutMs: Long): Result<String> {
        synchronized(lock) {
            if (scanInProgress) {
                return Result.failure(Scan2dError.OperationInProgress.asException())
            }
            scanInProgress = true
        }

        var receiver: BroadcastReceiver? = null
        var receiverRegistered = false
        var barcode2d: Barcode2D? = null
        var utility: BarcodeUtility? = null

        try {
            utility =
                runCatching { BarcodeUtility.getInstance() }.getOrElse { error ->
                    return Result.failure(
                        Scan2dError.HardwareUnavailable.asException(
                            message = "2D scanner unavailable.",
                            cause = error,
                        ),
                    )
                }
            val resolvedUtility =
                utility ?: return Result.failure(
                    Scan2dError.HardwareUnavailable.asException(message = "2D scanner unavailable."),
                )

            val settings = settingsStore.settingsFlow.first()
            val action =
                settings.scan2dAction.trim().ifBlank {
                    AppDefaults.SCAN2D_ACTION
                }
            val extraKey =
                settings.scan2dExtraKey.trim().ifBlank {
                    AppDefaults.SCAN2D_EXTRA_KEY
                }

            barcode2d =
                runCatching { Barcode2D.getInstance() }.getOrElse { error ->
                    return Result.failure(
                        Scan2dError.HardwareUnavailable.asException(
                            message = "2D scanner unavailable.",
                            cause = error,
                        ),
                    )
                }
            val opened =
                runCatching { barcode2d.open(appContext) }.getOrElse { error ->
                    return Result.failure(
                        Scan2dError.VendorError("QR scanner init failed.").asException(cause = error),
                    )
                }
            if (!opened) {
                return Result.failure(
                    Scan2dError.HardwareUnavailable.asException(message = "2D scanner unavailable."),
                )
            }

            runCatching {
                resolvedUtility.open(appContext, BarcodeUtility.ModuleType.BARCODE_2D)
                configureScanner(resolvedUtility, action, extraKey)
            }.getOrElse { error ->
                return Result.failure(
                    Scan2dError.VendorError("QR scanner configuration failed.").asException(cause = error),
                )
            }

            val rawResult =
                try {
                    withTimeout(timeoutMs) {
                        suspendCancellableCoroutine<String?> { continuation ->
                            val scanReceiver =
                                object : BroadcastReceiver() {
                                    override fun onReceive(
                                        context: Context?,
                                        intent: Intent?,
                                    ) {
                                        if (intent == null || !continuation.isActive) {
                                            return
                                        }
                                        val payload = extractPayload(intent, extraKey)
                                        continuation.resume(payload)
                                    }
                                }
                            receiver = scanReceiver
                            registerReceiver(scanReceiver, IntentFilter(action))
                            receiverRegistered = true
                            continuation.invokeOnCancellation {
                                runCatching {
                                    resolvedUtility.stopScan(appContext, BarcodeUtility.ModuleType.BARCODE_2D)
                                }
                                runCatching { barcode2d?.stopScan() }
                                if (receiverRegistered) {
                                    runCatching { appContext.unregisterReceiver(scanReceiver) }
                                    receiverRegistered = false
                                }
                            }
                            try {
                                resolvedUtility.startScan(appContext, BarcodeUtility.ModuleType.BARCODE_2D)
                            } catch (error: Exception) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    return Result.failure(Scan2dError.Timeout.asException(message = "QR scan timed out."))
                } catch (_: CancellationException) {
                    return Result.failure(Scan2dError.Cancelled.asException(message = "QR scan cancelled."))
                } catch (error: Exception) {
                    return Result.failure(
                        Scan2dError.VendorError(error.message ?: "QR scan failed.").asException(cause = error),
                    )
                }

            return Scan2dPayloadParser.parse(rawResult)
        } finally {
            runCatching { utility?.stopScan(appContext, BarcodeUtility.ModuleType.BARCODE_2D) }
            runCatching { barcode2d?.stopScan() }
            if (receiverRegistered && receiver != null) {
                runCatching { appContext.unregisterReceiver(receiver) }
            }
            runCatching { utility?.close(appContext, BarcodeUtility.ModuleType.BARCODE_2D) }
            runCatching { barcode2d?.close() }
            synchronized(lock) {
                scanInProgress = false
            }
        }
    }

    private fun configureScanner(
        utility: BarcodeUtility,
        action: String,
        extraKey: String,
    ) {
        utility.setOutputMode(appContext, OUTPUT_MODE_BROADCAST)
        utility.setScanResultBroadcast(appContext, action, extraKey)
        utility.enableEnter(appContext, false)
        utility.enableTAB(appContext, false)
        utility.setPrefix(appContext, "")
        utility.setSuffix(appContext, "")
        utility.enableContinuousScan(appContext, false)
    }

    private fun extractPayload(
        intent: Intent,
        extraKey: String,
    ): String? {
        return intent.getStringExtra(extraKey)
            ?: intent.getByteArrayExtra(extraKey)?.toString(Charsets.UTF_8)
            ?: intent.extras?.get(extraKey)?.toString()
    }

    private fun registerReceiver(
        receiver: BroadcastReceiver,
        filter: IntentFilter,
    ) {
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val OUTPUT_MODE_BROADCAST = 2
    }
}
