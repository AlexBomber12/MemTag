package com.alexbomber12.memtag.domain.batch

import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfLogger
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.integrations.uhf.ensureConfiguredWithRecovery
import com.alexbomber12.memtag.integrations.uhf.toErrorMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout

class BatchUhfUseCase(
    private val uhfReader: UhfReader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class SweepEntry(
        val epcNormalized: String,
        val lastSeenAt: Long,
        val bestRssi: Int?,
    )

    suspend fun collectSweep(onReading: (TagReading) -> Unit) {
        val startAt = clock()
        UhfLogger.i("ScanRFID start (screen=batch source=sweep usedMethod=inventory)")
        ensureReady("batch-sweep")
        try {
            uhfReader
                .startInventory()
                .collect { reading ->
                    if (reading.epcHex.isNotBlank()) {
                        onReading(reading)
                    }
                }
        } finally {
            uhfReader.stopInventory()
            UhfLogger.i(
                "ScanRFID end (screen=batch result=sweep durationMs=${clock() - startAt})",
            )
        }
    }

    suspend fun scanOnce(timeoutMs: Long): TagReading? {
        val startAt = clock()
        UhfLogger.i("ScanRFID start (screen=batch source=manual usedMethod=single)")
        ensureReady("batch-scan")
        val single = uhfReader.readSingle(timeoutMs)
        if (single.isSuccess) {
            val epc = single.getOrNull().orEmpty()
            val reading =
                if (epc.isBlank()) {
                    null
                } else {
                    TagReading(epcHex = epc, rssi = null, timestampMs = clock())
                }
            UhfLogger.i(
                "ScanRFID end (screen=batch result=${reading?.epcHex ?: "none"} durationMs=${clock() - startAt})",
            )
            return reading
        }
        val error = single.exceptionOrNull()
        val fallback = runInventoryOnce(timeoutMs)
        if (fallback != null) {
            UhfLogger.i(
                "ScanRFID end (screen=batch result=${fallback.epcHex} durationMs=${clock() - startAt})",
            )
            return fallback
        }
        if (error is UhfException && error.error == UhfError.Timeout) {
            UhfLogger.i("ScanRFID end (screen=batch result=timeout durationMs=${clock() - startAt})")
            return null
        }
        UhfLogger.i("ScanRFID end (screen=batch result=error durationMs=${clock() - startAt})")
        throw error ?: UhfError.VendorError("Scan failed").asException()
    }

    private suspend fun runInventoryOnce(timeoutMs: Long): TagReading? {
        return try {
            withTimeout(timeoutMs) {
                uhfReader
                    .startInventory()
                    .firstOrNull { reading -> reading.epcHex.isNotBlank() }
            }
        } catch (_: TimeoutCancellationException) {
            null
        } finally {
            uhfReader.stopInventory()
        }
    }

    private suspend fun ensureReady(reason: String) {
        uhfReader.stopInventory()
        val applyResult = uhfReader.ensureConfiguredWithRecovery(reason)
        if (applyResult.isFailure) {
            throw applyResult.exceptionOrNull() ?: UhfError.VendorError("Apply config failed").asException()
        }
        val applied = applyResult.getOrNull()
        if (applied != null && !applied.success) {
            throw UhfError.VendorError(applied.toErrorMessage()).asException()
        }
    }
}
