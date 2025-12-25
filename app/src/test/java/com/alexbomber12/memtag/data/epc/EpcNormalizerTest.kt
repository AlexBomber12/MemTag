package com.alexbomber12.memtag.data.epc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EpcNormalizerTest {
    @Test
    fun normalizesLowercaseToUppercase() {
        val result = EpcNormalizer.normalize("abcd1234")
        assertEquals("ABCD1234", result)
    }

    @Test
    fun removesSpacesAndNewlines() {
        val result = EpcNormalizer.normalize("ab cd\n12 34")
        assertEquals("ABCD1234", result)
    }

    @Test
    fun preservesHexCharacters() {
        val result = EpcNormalizer.normalize("0a1b2c3d4e5f")
        assertEquals("0A1B2C3D4E5F", result)
    }

    @Test
    fun isLikelyHexEpcRejectsNonHex() {
        assertFalse(EpcNormalizer.isLikelyHexEpc("GGGG1234"))
    }
}
