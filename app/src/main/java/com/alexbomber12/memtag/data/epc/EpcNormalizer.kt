package com.alexbomber12.memtag.data.epc

object EpcNormalizer {
    fun normalize(input: String): String {
        return input
            .trim()
            .replace(" ", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", "")
            .uppercase()
    }

    fun isLikelyHexEpc(value: String): Boolean {
        val normalized = normalize(value)
        if (normalized.length < 8 || normalized.length > 64) {
            return false
        }
        return normalized.all { char ->
            char in '0'..'9' || char in 'A'..'F'
        }
    }
}
