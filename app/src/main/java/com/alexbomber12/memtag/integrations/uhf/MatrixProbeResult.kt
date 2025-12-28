package com.alexbomber12.memtag.integrations.uhf

data class MatrixProbeResult(
    val name: String,
    val startOk: Boolean,
    val stopOk: Boolean,
    val reads: Int,
    val nonNullReads: Int,
    val firstRaw0: String?,
    val firstRaw1: String?,
    val firstRssi: String?,
    val note: String?,
)
