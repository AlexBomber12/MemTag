package com.alexbomber12.memtag.util.epc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun validatorRejectsTooShort() {
        assertFalse(EpcValidator.isValidEpcHex("ABC1234"))
    }

    @Test
    fun validatorRejectsTooLong() {
        assertFalse(EpcValidator.isValidEpcHex("A".repeat(65)))
    }

    @Test
    fun validatorRejectsOddLength() {
        assertFalse(EpcValidator.isValidEpcHex("ABC12345F"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeRejectsNonHex() {
        EpcNormalizer.normalize("GGGG1234")
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeRejectsEmpty() {
        EpcNormalizer.normalize(" \n\t")
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeRejectsTooShort() {
        EpcNormalizer.normalize("ABC1234")
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeRejectsTooLong() {
        EpcNormalizer.normalize("A".repeat(65))
    }

    @Test(expected = IllegalArgumentException::class)
    fun normalizeRejectsOddLength() {
        EpcNormalizer.normalize("ABC12345F")
    }

    @Test
    fun normalizeUhfEpcHandlesPrefixesAndWhitespace() {
        val result = normalizeUhfEpc("  epc: 3008 33b2 ddd9 0140 0000 0000 ")
        assertEquals("300833B2DDD9014000000000", result)
    }

    @Test
    fun normalizeUhfEpcRejectsNonHexCharacters() {
        val result = normalizeUhfEpc("EPC=E2000017-2211-01441890ABCD")
        assertNull(result)
    }

    @Test
    fun normalizeUhfEpcRejectsInvalidLength() {
        assertNull(normalizeUhfEpc("EPC:1234"))
    }
}
