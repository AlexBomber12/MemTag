package com.alexbomber12.memtag.integrations.scan2d

import com.alexbomber12.memtag.util.epc.EpcNormalizer

object Scan2dPayloadParser {
    fun parse(raw: String?): Result<String> {
        if (raw.isNullOrBlank()) {
            return Result.failure(
                Scan2dError.InvalidPayload("QR scan was empty.").asException(),
            )
        }
        val normalized =
            runCatching { EpcNormalizer.normalize(raw) }.getOrElse { error ->
                return Result.failure(
                    Scan2dError.InvalidPayload("QR must contain EPC hex only.").asException(cause = error),
                )
            }
        return Result.success(normalized)
    }
}
