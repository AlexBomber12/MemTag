package com.alexbomber12.memtag.domain.repair

import com.alexbomber12.memtag.util.epc.EpcNormalizer

class RepairDecisionUseCase {
    fun evaluate(
        expectedEpc: String?,
        currentEpc: String?,
    ): RepairComparison {
        if (expectedEpc.isNullOrBlank() || currentEpc.isNullOrBlank()) {
            return RepairComparison.NotReady
        }
        val normalizedExpected =
            runCatching { EpcNormalizer.normalize(expectedEpc) }.getOrElse { error ->
                return RepairComparison.Invalid(error.message ?: "Expected EPC is invalid.")
            }
        val normalizedCurrent =
            runCatching { EpcNormalizer.normalize(currentEpc) }.getOrElse { error ->
                return RepairComparison.Invalid(error.message ?: "Read EPC is invalid.")
            }
        return if (normalizedExpected == normalizedCurrent) {
            RepairComparison.Match(expectedEpc = normalizedExpected, currentEpc = normalizedCurrent)
        } else {
            RepairComparison.Mismatch(expectedEpc = normalizedExpected, currentEpc = normalizedCurrent)
        }
    }
}
