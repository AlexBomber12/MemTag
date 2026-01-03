package com.alexbomber12.memtag.integrations.uhf

import android.util.Log
import kotlinx.coroutines.delay

private const val RECOVERY_DELAY_MS = 200L
private const val RECOVERY_LOG_TAG = "ChainwayUhfReader"

suspend fun UhfReader.ensureConfiguredWithRecovery(reason: String): Result<UhfApplyResult> {
    val initResult = initialize()
    if (initResult.isFailure) {
        return Result.failure(initResult.exceptionOrNull() ?: UhfError.NotInitialized.asException())
    }
    val firstResult = applyDesiredConfigBestEffort(reason)
    val firstApply = firstResult.getOrNull()
    if (firstResult.isSuccess && firstApply?.success == true) {
        return firstResult.withRecoveryAttempted(false)
    }

    runCatching { close() }
    delay(RECOVERY_DELAY_MS)
    val retryInit = initialize()
    if (retryInit.isFailure) {
        if (firstApply != null) {
            logApplyFailure(firstApply, recoveryAttempted = true)
        }
        return Result.failure(retryInit.exceptionOrNull() ?: UhfError.NotInitialized.asException())
    }
    val secondResult = applyDesiredConfigBestEffort("$reason-recover").withRecoveryAttempted(true)
    val secondApply = secondResult.getOrNull()
    if (secondResult.isSuccess && secondApply != null && !secondApply.success) {
        logApplyFailure(secondApply, recoveryAttempted = true)
    }
    return secondResult
}

private fun Result<UhfApplyResult>.withRecoveryAttempted(attempted: Boolean): Result<UhfApplyResult> =
    map { it.copy(recoveryAttempted = attempted) }

private fun logApplyFailure(
    result: UhfApplyResult,
    recoveryAttempted: Boolean,
) {
    if (result.success) {
        return
    }
    val message =
        "configApply failed " +
            "reason=${result.reason} " +
            "setPowerOk=${result.setPowerOk} " +
            "readBackPower=${result.afterPower} " +
            "desiredPower=${result.desiredPower} " +
            "powerApplied=${result.powerApplied} " +
            "recoveryAttempted=$recoveryAttempted"
    runCatching { Log.i(RECOVERY_LOG_TAG, message) }
}
