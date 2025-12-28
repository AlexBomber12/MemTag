package com.alexbomber12.memtag.domain.batch

import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfError
import com.alexbomber12.memtag.integrations.uhf.UhfException
import com.alexbomber12.memtag.integrations.uhf.UhfLogger
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.asException
import com.alexbomber12.memtag.integrations.uhf.toErrorMessage
import com.alexbomber12.memtag.util.epc.EpcNormalizer
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

    data class SweepResult(
        val entries: Map<String, SweepEntry>,
    )

    suspend fun runSweep(durationMs: Long): SweepResult {
        val startAt = clock()
        UhfLogger.i("ScanRFID start (screen=batch source=sweep usedMethod=inventory)")
        ensureReady("batch-sweep")
        val entries = mutableMapOf<String, SweepEntry>()
        try {
            withTimeout(durationMs) {
                uhfReader
                    .startInventory()
                    .collect { reading ->
                        val normalized =
                            runCatching { EpcNormalizer.normalize(reading.epcHex) }.getOrNull() ?: return@collect
                        val existing = entries[normalized]
                        val bestRssi = bestRssi(existing?.bestRssi, reading.rssi)
                        entries[normalized] =
                            SweepEntry(
                                epcNormalized = normalized,
                                lastSeenAt = reading.timestampMs,
                                bestRssi = bestRssi,
                            )
                    }
            }
        } catch (_: TimeoutCancellationException) {
            // Expected when the sweep duration is reached.
        } finally {
            uhfReader.stopInventory()
            UhfLogger.i(
                "ScanRFID end (screen=batch result=sweep durationMs=${clock() - startAt})",
            )
        }
        return SweepResult(entries = entries)
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
        val initResult = uhfReader.initialize()
        if (initResult.isFailure) {
            throw initResult.exceptionOrNull() ?: UhfError.NotInitialized.asException()
        }
        uhfReader.stopInventory()
        val applyResult = uhfReader.applyDesiredConfigBestEffort(reason)
        if (applyResult.isFailure) {
            throw applyResult.exceptionOrNull() ?: UhfError.VendorError("Apply config failed").asException()
        }
        val applied = applyResult.getOrNull()
        if (applied != null && !applied.success) {
            throw UhfError.VendorError(applied.toErrorMessage()).asException()
        }
    }

    private fun bestRssi(
        current: Int?,
        candidate: Int?,
    ): Int? {
        return when {
            current == null -> candidate
            candidate == null -> current
            else -> maxOf(current, candidate)
        }
    }
}
