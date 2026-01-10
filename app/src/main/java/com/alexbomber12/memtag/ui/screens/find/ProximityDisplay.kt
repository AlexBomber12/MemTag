package com.alexbomber12.memtag.ui.screens.find

import kotlin.math.max

fun computeDisplayProximity(
    proximity: Int,
    seenRecently: Boolean,
): Int {
    return if (seenRecently) max(1, proximity) else 0
}
