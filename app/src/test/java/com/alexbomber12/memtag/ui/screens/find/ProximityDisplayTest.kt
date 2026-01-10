package com.alexbomber12.memtag.ui.screens.find

import org.junit.Assert.assertEquals
import org.junit.Test

class ProximityDisplayTest {
    @Test
    fun returnsZeroWhenNotSeenRecently() {
        assertEquals(0, computeDisplayProximity(proximity = 50, seenRecently = false))
    }

    @Test
    fun clampsToOneWhenSeenRecentlyAndZero() {
        assertEquals(1, computeDisplayProximity(proximity = 0, seenRecently = true))
    }

    @Test
    fun passesThroughWhenSeenRecentlyAndAboveZero() {
        assertEquals(7, computeDisplayProximity(proximity = 7, seenRecently = true))
    }

    @Test
    fun passesThroughMaxValue() {
        assertEquals(100, computeDisplayProximity(proximity = 100, seenRecently = true))
    }
}
