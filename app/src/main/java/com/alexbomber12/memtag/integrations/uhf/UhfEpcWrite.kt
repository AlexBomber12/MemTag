package com.alexbomber12.memtag.integrations.uhf

import com.alexbomber12.memtag.util.epc.EpcNormalizer

internal const val EPC_MEMORY_BANK = 1
internal const val EPC_WRITE_WORD_PTR = 2
internal const val EPC_SELECT_PTR_BITS = EPC_WRITE_WORD_PTR * 16

internal data class EpcWriteParams(
    val normalizedEpc: String,
    val wordPtr: Int,
    val wordCount: Int,
    val payloadBytes: ByteArray,
)

internal fun buildEpcWriteParams(expectedEpcHex: String): EpcWriteParams {
    val normalized = EpcNormalizer.normalize(expectedEpcHex)
    val payloadBytes = decodeHexToBytes(normalized)
    require(payloadBytes.size % 2 == 0) { "EPC byte length must be even." }
    val wordCount = payloadBytes.size / 2
    return EpcWriteParams(
        normalizedEpc = normalized,
        wordPtr = EPC_WRITE_WORD_PTR,
        wordCount = wordCount,
        payloadBytes = payloadBytes,
    )
}

internal fun decodeHexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "Hex string must have even length." }
    val bytes = ByteArray(hex.length / 2)
    var index = 0
    var offset = 0
    while (offset < hex.length) {
        val high = hexCharToNibble(hex[offset])
        val low = hexCharToNibble(hex[offset + 1])
        bytes[index] = ((high shl 4) or low).toByte()
        index += 1
        offset += 2
    }
    return bytes
}

internal fun bytesToHexPrefix(
    bytes: ByteArray,
    limit: Int,
): String {
    val count = minOf(limit, bytes.size)
    val builder = StringBuilder(count * 2)
    for (i in 0 until count) {
        val value = bytes[i].toInt() and 0xFF
        builder.append(value.toString(16).uppercase().padStart(2, '0'))
    }
    return builder.toString()
}

private fun hexCharToNibble(char: Char): Int {
    val digit = Character.digit(char, 16)
    require(digit >= 0) { "Invalid hex character: $char" }
    return digit
}
