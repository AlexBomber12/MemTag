package com.alexbomber12.memtag.util.epc

private const val MIN_EPC_LENGTH = 8
private const val MAX_EPC_LENGTH = 64
private val WHITESPACE_REGEX = Regex("\\s+")

private fun normalizeCandidate(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return ""
    }
    var candidate = trimmed.replace(WHITESPACE_REGEX, "").uppercase()
    candidate =
        when {
            candidate.startsWith("EPC:") -> candidate.removePrefix("EPC:")
            candidate.startsWith("EPC=") -> candidate.removePrefix("EPC=")
            candidate.startsWith("EPC") -> candidate.removePrefix("EPC")
            else -> candidate
        }
    if (candidate.startsWith("0X")) {
        candidate = candidate.removePrefix("0X")
    }
    return candidate
}

private fun isHexOnly(value: String): Boolean {
    return value.all { char ->
        char in '0'..'9' || char in 'A'..'F'
    }
}

private fun isValidLength(value: String): Boolean {
    return value.length in MIN_EPC_LENGTH..MAX_EPC_LENGTH && value.length % 2 == 0
}

object EpcNormalizer {
    fun normalize(raw: String): String {
        val normalized = normalizeCandidate(raw)
        require(normalized.isNotEmpty()) { "EPC cannot be empty." }
        require(isValidLength(normalized)) { "EPC must be 8-64 hex characters." }
        require(isHexOnly(normalized)) { "EPC must contain only hex characters." }
        return normalized
    }
}

object EpcValidator {
    fun isValidEpcHex(epc: String): Boolean {
        val normalized = normalizeCandidate(epc)
        return normalized.isNotEmpty() && isValidLength(normalized) && isHexOnly(normalized)
    }
}
