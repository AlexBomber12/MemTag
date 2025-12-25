package com.alexbomber12.memtag.util.epc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun validatorAcceptsHex() {
        assertTrue(EpcValidator.isValidEpcHex("0a1b2c3d4e5f"))
    }

    @Test
    fun validatorRejectsNonHex() {
        assertFalse(EpcValidator.isValidEpcHex("GGGG1234"))
    }

    @Test
    fun validatorRejectsEmpty() {
        assertFalse(EpcValidator.isValidEpcHex(" \n\t"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeRejectsNonHex() {
        EpcNormalizer.normalize("GGGG1234")
    }
}
