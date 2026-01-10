package com.alexbomber12.memtag.ui.screens.find

import org.junit.Assert.assertEquals
import org.junit.Test

class ProximityUiMapperTest {
    @Test
    fun rescalesWithinExpectedRange() {
        assertEquals(0, rescaleProximityUiScore(30))
        assertEquals(100, rescaleProximityUiScore(95))
        assertEquals(11, rescaleProximityUiScore(37))
        assertEquals(92, rescaleProximityUiScore(90))
    }

    @Test
    fun clampsOutsideRange() {
        assertEquals(0, rescaleProximityUiScore(10))
        assertEquals(100, rescaleProximityUiScore(120))
    }
}
