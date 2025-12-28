package com.alexbomber12.memtag.domain.repair

enum class RepairActionType {
    VERIFY_WRITE_SCAN,
    VERIFY_MATCH,
    VERIFY_MISMATCH,
    VERIFY_LOOKUP_FOUND,
    VERIFY_LOOKUP_NOT_FOUND,
    REPAIR_WRITE_ATTEMPT,
    REPAIR_WRITE_SUCCESS,
    REPAIR_WRITE_FAILED,
    REPAIR_WRITE_CANCELLED,
}

enum class RepairActionResult {
    SUCCESS,
    FAILURE,
    CANCELLED,
}

data class RepairActionLog(
    val id: Long,
    val createdAtEpochMs: Long,
    val actionType: RepairActionType,
    val expectedEpc: String?,
    val currentEpc: String?,
    val result: RepairActionResult,
    val message: String?,
)

sealed class RepairComparison {
    data object NotReady : RepairComparison()

    data class Match(
        val expectedEpc: String,
        val currentEpc: String,
    ) : RepairComparison()

    data class Mismatch(
        val expectedEpc: String,
        val currentEpc: String,
    ) : RepairComparison()

    data class Invalid(
        val message: String,
    ) : RepairComparison()
}
