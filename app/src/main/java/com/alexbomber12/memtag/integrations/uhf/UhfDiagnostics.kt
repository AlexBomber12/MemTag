package com.alexbomber12.memtag.integrations.uhf

import kotlinx.coroutines.flow.StateFlow

data class UhfDiagnostics(
    val inventoryRunning: Boolean = false,
    val matrixProbeRunning: Boolean = false,
    val matrixProbeCurrent: String? = null,
    val startInventoryOk: Boolean? = null,
    val stopInventoryOk: Boolean? = null,
    val bufferReadsCount: Long = 0,
    val tagsSeenCount: Long = 0,
    val lastRaw0: String? = null,
    val lastRaw1: String? = null,
    val lastRssi: Int? = null,
    val lastReadEpc: String? = null,
)

interface UhfDiagnosticsSource {
    val diagnosticsFlow: StateFlow<UhfDiagnostics>
}
