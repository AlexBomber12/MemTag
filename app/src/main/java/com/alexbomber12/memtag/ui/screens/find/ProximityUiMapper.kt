package com.alexbomber12.memtag.ui.screens.find

import kotlin.math.roundToInt

fun rescaleProximityUiScore(
    rawScore: Int,
    rawMin: Int = 30,
    rawMax: Int = 95,
): Int {
    return when {
        rawScore <= rawMin -> 0
        rawScore >= rawMax -> 100
        else -> {
            val scaled =
                ((rawScore - rawMin).toFloat() / (rawMax - rawMin).toFloat()) * 100f
            scaled.roundToInt().coerceIn(0, 100)
        }
    }
}
