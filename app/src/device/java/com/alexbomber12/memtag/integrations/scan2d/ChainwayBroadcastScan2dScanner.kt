package com.alexbomber12.memtag.integrations.scan2d

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.barcode.BarcodeUtility
import com.rscja.deviceapi.Barcode2D
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ChainwayBroadcastScan2dScanner(
    context: Context,
    private val settingsStore: SettingsStore,
    private val ioDispatcher: CoroutineDispatcher,
) : Scan2dScanner {
    private val appContext = context.applicationContext
    private val lock = Any()
    private var scanInProgress = false

    override suspend fun scanOnce(
        timeoutMs: Long,
        source: String,
    ): Result<String> {
        synchronized(lock) {
            if (scanInProgress) {
                return Result.failure(Scan2dError.OperationInProgress.asException())
            }
            scanInProgress = true
        }

        return try {
            val settings =
                withContext(ioDispatcher) {
                    settingsStore.settingsFlow.first()
                }
            val action =
                settings.scan2dAction.trim().ifBlank {
                    AppDefaults.SCAN2D_ACTION
                }
            val extraKey =
                settings.scan2dExtraKey.trim().ifBlank {
                    AppDefaults.SCAN2D_EXTRA_KEY
                }

            val utility =
                runCatching { onMain { BarcodeUtility.getInstance() } }.getOrElse { error ->
                    return Result.failure(
                        Scan2dError.HardwareUnavailable.asException(
                            message = "2D scanner unavailable.",
                            cause = error,
                        ),
                    )
                }
            val barcode2d =
                runCatching { onMain { Barcode2D.getInstance() } }.getOrElse { error ->
                    return Result.failure(
                        Scan2dError.HardwareUnavailable.asException(
                            message = "2D scanner unavailable.",
                            cause = error,
                        ),
                    )
                }

            val moduleTypes =
                listOf(
                    BarcodeUtility.ModuleType.BARCODE_2D,
                    BarcodeUtility.ModuleType.BARCODE_2D_H,
                )
            var finalOutcome: AttemptOutcome.Started? = null
            var lastStartError: Throwable? = null
            for ((index, moduleType) in moduleTypes.withIndex()) {
                val outcome =
                    attemptScan(
                        utility = utility,
                        barcode2d = barcode2d,
                        moduleType = moduleType,
                        action = action,
                        extraKey = extraKey,
                        timeoutMs = timeoutMs,
                        source = source,
                    )
                when (outcome) {
                    is AttemptOutcome.Started -> {
                        finalOutcome = outcome
                        break
                    }

                    is AttemptOutcome.ImmediateFailure -> {
                        lastStartError = outcome.error
                        if (!outcome.retryable || index == moduleTypes.lastIndex) {
                            break
                        }
                    }
                }
            }

            val result =
                finalOutcome?.result
                    ?: Result.failure(
                        Scan2dError.VendorError("QR scan failed to start.").asException(cause = lastStartError),
                    )
            logEndResult(finalOutcome?.outcome)
            result
        } finally {
            synchronized(lock) {
                scanInProgress = false
            }
        }
    }

    private suspend fun attemptScan(
        utility: BarcodeUtility,
        barcode2d: Barcode2D,
        moduleType: BarcodeUtility.ModuleType,
        action: String,
        extraKey: String,
        timeoutMs: Long,
        source: String,
    ): AttemptOutcome {
        var receiver: BroadcastReceiver? = null
        var receiverRegistered = false
        val payloadDeferred = CompletableDeferred<String?>()

        try {
            val opened =
                runCatching { onMain { barcode2d.open(appContext) } }.getOrElse { error ->
                    return AttemptOutcome.ImmediateFailure(error, retryable = false)
                }
            if (!opened) {
                Scan2dLogger.w("Barcode2D.open returned false source=$source module=${moduleType.name}")
                return AttemptOutcome.ImmediateFailure(
                    IllegalStateException("Barcode2D.open returned false"),
                    retryable = false,
                )
            }

            val configThread =
                runCatching {
                    onMain {
                        utility.open(appContext, moduleType)
                        configureScanner(utility, action, extraKey)
                        Thread.currentThread().name
                    }
                }.getOrElse { error ->
                    return AttemptOutcome.ImmediateFailure(error, retryable = true)
                }
            Scan2dLogger.i(
                "qr cfg output=broadcast action=$action extra=$extraKey module=${moduleType.name} " +
                    "thread=$configThread",
            )
            delay(OPEN_DELAY_MS)

            val scanReceiver =
                object : BroadcastReceiver() {
                    override fun onReceive(
                        context: Context?,
                        intent: Intent?,
                    ) {
                        if (intent == null || payloadDeferred.isCompleted) {
                            return
                        }
                        val payload = extractPayload(intent, extraKey)
                        val extrasKeys = intent.extras?.keySet()?.sorted()?.joinToString(",") ?: ""
                        Scan2dLogger.i(
                            "qr onReceive action=${intent.action} extrasKeys=[$extrasKeys] " +
                                "hasPayload=${!payload.isNullOrBlank()}",
                        )
                        payloadDeferred.complete(payload)
                    }
                }
            receiver = scanReceiver
            ContextCompat.registerReceiver(
                appContext,
                scanReceiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiverRegistered = true

            val startError =
                runCatching {
                    onMain { utility.startScan(appContext, moduleType) }
                    delay(START_DELAY_MS)
                }.exceptionOrNull()
            if (startError != null) {
                return AttemptOutcome.ImmediateFailure(startError, retryable = true)
            }

            val rawPayload =
                try {
                    withTimeout(timeoutMs) {
                        payloadDeferred.await()
                    }
                } catch (_: TimeoutCancellationException) {
                    return AttemptOutcome.Started(
                        Result.failure(Scan2dError.Timeout.asException(message = "QR scan timed out.")),
                        ScanOutcome.TIMEOUT,
                    )
                } catch (_: CancellationException) {
                    return AttemptOutcome.Started(
                        Result.failure(Scan2dError.Cancelled.asException(message = "QR scan cancelled.")),
                        ScanOutcome.CANCELLED,
                    )
                } catch (error: Exception) {
                    return AttemptOutcome.Started(
                        Result.failure(
                            Scan2dError.VendorError(error.message ?: "QR scan failed.").asException(cause = error),
                        ),
                        ScanOutcome.ERROR,
                    )
                }

            val parsed = Scan2dPayloadParser.parse(rawPayload)
            val outcome = if (parsed.isSuccess) ScanOutcome.OK else ScanOutcome.ERROR
            return AttemptOutcome.Started(parsed, outcome)
        } finally {
            withContext(NonCancellable) {
                runCatching {
                    onMain {
                        utility.stopScan(appContext, moduleType)
                        barcode2d.stopScan()
                    }
                }
                delay(CLOSE_DELAY_MS)
                if (receiverRegistered && receiver != null) {
                    runCatching { appContext.unregisterReceiver(receiver) }
                }
                runCatching {
                    onMain {
                        utility.close(appContext, moduleType)
                        barcode2d.close()
                    }
                }
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
        utility.setReleaseScan(appContext, false)
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

    private fun logEndResult(outcome: ScanOutcome?) {
        val label =
            when (outcome) {
                ScanOutcome.OK -> "ok"
                ScanOutcome.TIMEOUT -> "timeout"
                ScanOutcome.CANCELLED -> "cancel"
                ScanOutcome.ERROR -> "error"
                null -> "error"
            }
        Scan2dLogger.i("qr end result=$label")
    }

    private suspend fun <T> onMain(block: () -> T): T {
        return withContext(Dispatchers.Main.immediate) {
            block()
        }
    }

    private sealed class AttemptOutcome {
        data class Started(
            val result: Result<String>,
            val outcome: ScanOutcome,
        ) : AttemptOutcome()

        data class ImmediateFailure(
            val error: Throwable,
            val retryable: Boolean,
        ) : AttemptOutcome()
    }

    private enum class ScanOutcome {
        OK,
        TIMEOUT,
        CANCELLED,
        ERROR,
    }

    private companion object {
        const val OUTPUT_MODE_BROADCAST = 2
        const val OPEN_DELAY_MS = 50L
        const val START_DELAY_MS = 20L
        const val CLOSE_DELAY_MS = 50L
    }
}
