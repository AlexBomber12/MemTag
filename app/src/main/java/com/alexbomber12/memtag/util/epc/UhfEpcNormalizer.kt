package com.alexbomber12.memtag.util.epc

private const val UHF_EPC_MIN_LENGTH = 8
private const val UHF_EPC_MAX_LENGTH = 64
private val UHF_WHITESPACE_REGEX = Regex("\\s+")

fun normalizeUhfEpc(raw: String?): String? {
    val trimmed = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    var candidate = trimmed.replace(UHF_WHITESPACE_REGEX, "").uppercase()
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
    val hexOnly =
        candidate.filter { char ->
            char in '0'..'9' || char in 'A'..'F'
        }
    if (hexOnly.length !in UHF_EPC_MIN_LENGTH..UHF_EPC_MAX_LENGTH) {
        return null
    }
    return hexOnly
}
