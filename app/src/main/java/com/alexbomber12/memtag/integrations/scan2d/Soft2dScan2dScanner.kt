package com.alexbomber12.memtag.integrations.scan2d

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

internal interface Soft2dScanCallback {
    fun onScanComplete(
        symbology: Int,
        length: Int,
        data: ByteArray?,
    )
}

internal interface Soft2dScannerAdapter {
    fun open(): Boolean

    fun setDefaultParameters()

    fun enableAllCodeTypes()

    fun setTimeOut(seconds: Int)

    fun setScanCallback(callback: Soft2dScanCallback)

    fun scan()

    fun stopScan()

    fun close(): Boolean
}

internal class Soft2dScan2dScanner(
    private val adapter: Soft2dScannerAdapter,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : Scan2dScanner {
    private val scanInProgress = AtomicBoolean(false)
    private val noOpCallback =
        object : Soft2dScanCallback {
            override fun onScanComplete(
                symbology: Int,
                length: Int,
                data: ByteArray?,
            ) = Unit
        }

    override suspend fun scanOnce(
        timeoutMs: Long,
        source: String,
    ): Result<String> {
        if (!scanInProgress.compareAndSet(false, true)) {
            return Result.failure(Scan2dError.OperationInProgress.asException())
        }

        var result: Result<String> =
            Result.failure(
                Scan2dError.VendorError("QR scan failed.").asException(),
            )
        var outcome = Soft2dOutcome.Error
        val resultDeferred = CompletableDeferred<String?>()
        val callback =
            object : Soft2dScanCallback {
                override fun onScanComplete(
                    symbology: Int,
                    length: Int,
                    data: ByteArray?,
                ) {
                    val payload = decodePayload(data, length)
                    Scan2dLogger.i(
                        "soft2d callback sym=$symbology len=$length textLen=${payload.length}",
                    )
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(payload)
                    }
                }
            }

        try {
            val opened = onMain { adapter.open() }
            Scan2dLogger.i("soft2d open ok=$opened")
            if (opened) {
                val timeoutSeconds = (timeoutMs / 1_000L).toInt().coerceAtLeast(1)
                onMain {
                    adapter.setDefaultParameters()
                    adapter.enableAllCodeTypes()
                    adapter.setTimeOut(timeoutSeconds)
                    adapter.setScanCallback(callback)
                    adapter.scan()
                }
                Scan2dLogger.i("soft2d scan started")
                try {
                    val payload =
                        withTimeout(timeoutMs) {
                            resultDeferred.await()
                        }
                    result = Scan2dPayloadParser.parse(payload)
                    outcome = if (result.isSuccess) Soft2dOutcome.Ok else Soft2dOutcome.Error
                } catch (_: TimeoutCancellationException) {
                    outcome = Soft2dOutcome.Timeout
                    result = Result.failure(Scan2dError.Timeout.asException(message = "QR scan timed out."))
                } catch (_: CancellationException) {
                    outcome = Soft2dOutcome.Cancelled
                    result = Result.failure(Scan2dError.Cancelled.asException(message = "QR scan cancelled."))
                }
            } else {
                result =
                    Result.failure(
                        Scan2dError.HardwareUnavailable.asException(message = "2D scanner unavailable."),
                    )
                outcome = Soft2dOutcome.Error
            }
        } catch (error: Exception) {
            result =
                Result.failure(
                    Scan2dError.VendorError("QR scan failed.").asException(cause = error),
                )
            outcome = Soft2dOutcome.Error
        } finally {
            withContext(NonCancellable) {
                runCatching { onMain { adapter.stopScan() } }
                runCatching { onMain { adapter.setScanCallback(noOpCallback) } }
                runCatching { onMain { adapter.close() } }
            }
            scanInProgress.set(false)
            Scan2dLogger.i("soft2d end result=${outcome.label}")
        }

        return result
    }

    private suspend fun <T> onMain(block: () -> T): T {
        return withContext(mainDispatcher) {
            block()
        }
    }

    private fun decodePayload(
        data: ByteArray?,
        length: Int,
    ): String {
        if (data == null || length <= 0) {
            return ""
        }
        val safeLength = min(length.coerceAtLeast(0), data.size)
        if (safeLength <= 0) {
            return ""
        }
        val slice = data.copyOfRange(0, safeLength)
        val decoded =
            runCatching { String(slice, Charsets.UTF_8) }
                .getOrElse { String(slice, Charsets.ISO_8859_1) }
        return decoded.trimEnd { it == '\u0000' || it.isWhitespace() }
    }

    private enum class Soft2dOutcome(
        val label: String,
    ) {
        Ok("ok"),
        Timeout("timeout"),
        Cancelled("cancel"),
        Error("error"),
    }
}
