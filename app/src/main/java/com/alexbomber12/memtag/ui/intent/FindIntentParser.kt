package com.alexbomber12.memtag.ui.intent

import android.content.Intent
import android.net.Uri

private val EPC_QUERY_KEYS = setOf("epc", "expectedepc")
private val EPC_EXTRA_KEYS = setOf("epc", "expectedepc", "expected_epc")
private val WHITESPACE_REGEX = Regex("\\s+")

internal fun extractFindEpc(intent: Intent?): String? {
    if (intent == null) {
        return null
    }
    val fromUri = intent.data?.let { extractFromUri(it) }
    if (fromUri != null) {
        return fromUri
    }
    return extractFromExtras(intent)
}

private fun extractFromUri(uri: Uri): String? {
    val key =
        uri.queryParameterNames.firstOrNull { name ->
            EPC_QUERY_KEYS.contains(name.lowercase())
        } ?: return null
    val raw = uri.getQueryParameter(key) ?: return null
    return normalizeAndValidate(raw)
}

private fun extractFromExtras(intent: Intent): String? {
    val extras = intent.extras ?: return null
    val key =
        extras.keySet().firstOrNull { name ->
            EPC_EXTRA_KEYS.contains(name.lowercase())
        } ?: return null
    val raw = extras.getString(key) ?: extras.getCharSequence(key)?.toString() ?: return null
    return normalizeAndValidate(raw)
}

private fun normalizeAndValidate(raw: String): String? {
    val candidate = normalizeCandidate(raw)
    if (candidate.isBlank()) {
        return null
    }
    return if (isHexLike(candidate)) candidate else null
}

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

private fun isHexLike(value: String): Boolean {
    return value.all { char ->
        char in '0'..'9' || char in 'A'..'F'
    }
}
