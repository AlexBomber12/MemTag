package com.alexbomber12.memtag.integrations.scan2d

import com.alexbomber12.memtag.app.HardwareModeCoordinator
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class CoordinatedScan2dScanner(
    private val delegate: Scan2dScanner,
    private val rawUhfReader: UhfReader,
    private val coordinator: HardwareModeCoordinator,
    private val ioDispatcher: CoroutineDispatcher,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : Scan2dScanner {
    override suspend fun scanOnce(
        timeoutMs: Long,
        source: String,
    ): Result<String> =
        coordinator.runQrSession(reason = "scan2d:$source") {
            Scan2dLogger.i("scanOnce start source=$source thread=${Thread.currentThread().name}")
            val closeStatus =
                withContext(ioDispatcher) {
                    Scan2dLogger.i("before stopInventory")
                    val stopResult =
                        runCatching {
                            withTimeoutOrNull(UHF_STOP_TIMEOUT_MS) {
                                rawUhfReader.stopInventory()
                            }
                        }
                    Scan2dLogger.i("after stopInventory")
                    logUhfResult("stopInventory", stopResult)

                    Scan2dLogger.i("before UHF.close")
                    val closeResult =
                        runCatching {
                            withTimeoutOrNull(UHF_CLOSE_TIMEOUT_MS) {
                                rawUhfReader.close()
                            }
                        }
                    Scan2dLogger.i("after UHF.close")
                    val status = logUhfResult("close", closeResult)
                    Scan2dLogger.i("UHF closed before QR: ok=${status == UhfOpStatus.Success}")
                    delay(UHF_SETTLE_DELAY_MS)
                    status
                }
            Scan2dLogger.i("start delegate")
            val result =
                runCatching {
                    withContext(mainDispatcher) {
                        Scan2dLogger.i(
                            "delegate scanOnce invoked on thread=${Thread.currentThread().name} source=$source",
                        )
                        delegate.scanOnce(timeoutMs, source)
                    }
                }.getOrElse { error -> Result.failure(error) }
            Scan2dLogger.i("end delegate")
            if (closeStatus != UhfOpStatus.Success) {
                val scanError = (result.exceptionOrNull() as? Scan2dException)?.error
                if (scanError == Scan2dError.Timeout) {
                    Scan2dLogger.w("UHF close timed out source=$source")
                }
            }
            Scan2dLogger.i("scanOnce result=${formatResult(result)}")
            result
        }

    private fun logUhfResult(
        label: String,
        result: Result<Result<Unit>?>,
    ): UhfOpStatus {
        result.exceptionOrNull()?.let { error ->
            Scan2dLogger.w("$label failed: ${error.message}", error)
            return UhfOpStatus.Failure
        }
        val inner = result.getOrNull()
        if (inner == null) {
            Scan2dLogger.w("$label timed out")
            return UhfOpStatus.Timeout
        }
        inner.exceptionOrNull()?.let { error ->
            Scan2dLogger.w("$label failed: ${error.message}", error)
            return UhfOpStatus.Failure
        }
        return UhfOpStatus.Success
    }

    private fun formatResult(result: Result<String>): String {
        return result.fold(
            onSuccess = { "ok" },
            onFailure = { error ->
                val message = error.message?.takeIf { it.isNotBlank() }
                if (message != null) {
                    "error:${error::class.java.simpleName} message=$message"
                } else {
                    "error:${error::class.java.simpleName}"
                }
            },
        )
    }

    private enum class UhfOpStatus {
        Success,
        Timeout,
        Failure,
    }

    private companion object {
        const val UHF_STOP_TIMEOUT_MS = 700L
        const val UHF_CLOSE_TIMEOUT_MS = 900L
        const val UHF_SETTLE_DELAY_MS = 150L
    }
}
