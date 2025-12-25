package com.alexbomber12.memtag.util.epc

private fun normalizeCandidate(raw: String): String {
    return raw
        .trim()
        .replace(" ", "")
        .replace("\n", "")
        .replace("\r", "")
        .replace("\t", "")
        .uppercase()
}

private fun isHexOnly(value: String): Boolean {
    return value.all { char ->
        char in '0'..'9' || char in 'A'..'F'
    }
}

object EpcNormalizer {
    fun normalize(raw: String): String {
        val normalized = normalizeCandidate(raw)
        require(normalized.isNotEmpty()) { "EPC cannot be empty." }
        require(isHexOnly(normalized)) { "EPC must contain only hex characters." }
        return normalized
    }
}

object EpcValidator {
    fun isValidEpcHex(epc: String): Boolean {
        val normalized = normalizeCandidate(epc)
        return normalized.isNotEmpty() && isHexOnly(normalized)
    }
}
