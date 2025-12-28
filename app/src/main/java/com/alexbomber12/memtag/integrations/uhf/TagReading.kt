package com.alexbomber12.memtag.integrations.uhf

data class TagReading(
    val epcHex: String,
    val rssi: Int?,
    val timestampMs: Long,
    val rawEpc: String? = null,
    val rssiRaw: String? = null,
)
