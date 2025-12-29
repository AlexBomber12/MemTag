package com.alexbomber12.memtag.integrations.uhf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class UhfEpcWriteTest {
    @Test
    fun decodeHexToBytesPreservesLeadingZeros() {
        val bytes = decodeHexToBytes("00A1")
        assertArrayEquals(byteArrayOf(0x00.toByte(), 0xA1.toByte()), bytes)
    }

    @Test
    fun buildEpcWriteParamsCalculatesWordLength() {
        val params = buildEpcWriteParams("E2000017221101441890ABCD")
        assertEquals(12, params.payloadBytes.size)
        assertEquals(6, params.wordCount)
    }

    @Test
    fun writeParamsUseEpcBankAndOffsets() {
        val params = buildEpcWriteParams("E2000017221101441890ABCD")
        assertEquals(1, EPC_MEMORY_BANK)
        assertEquals(2, params.wordPtr)
        assertEquals(32, EPC_SELECT_PTR_BITS)
    }
}
