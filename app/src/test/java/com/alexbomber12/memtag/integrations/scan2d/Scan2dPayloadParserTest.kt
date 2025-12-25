package com.alexbomber12.memtag.integrations.scan2d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Scan2dPayloadParserTest {
    @Test
    fun parseNormalizesUppercase() {
        val result = Scan2dPayloadParser.parse("abcd1234").getOrThrow()
        assertEquals("ABCD1234", result)
    }

    @Test
    fun parseRemovesWhitespace() {
        val result = Scan2dPayloadParser.parse("ab cd\n12 34").getOrThrow()
        assertEquals("ABCD1234", result)
    }

    @Test
    fun parseRejectsNonHex() {
        val error = Scan2dPayloadParser.parse("GGGG1234").exceptionOrNull()
        assertTrue(error is Scan2dException)
        assertTrue((error as Scan2dException).error is Scan2dError.InvalidPayload)
    }

    @Test
    fun parseRejectsEmpty() {
        val error = Scan2dPayloadParser.parse(" \n\t").exceptionOrNull()
        assertTrue(error is Scan2dException)
        assertTrue((error as Scan2dException).error is Scan2dError.InvalidPayload)
    }

    @Test
    fun parseRejectsNull() {
        val error = Scan2dPayloadParser.parse(null).exceptionOrNull()
        assertTrue(error is Scan2dException)
        assertTrue((error as Scan2dException).error is Scan2dError.InvalidPayload)
    }
}
