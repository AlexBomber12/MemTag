package com.alexbomber12.memtag.util.epc

fun normalizeUhfEpc(raw: String?): String? {
    val candidate = raw?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { EpcNormalizer.normalize(candidate) }.getOrNull()
}
