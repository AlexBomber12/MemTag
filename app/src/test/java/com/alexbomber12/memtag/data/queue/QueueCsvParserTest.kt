package com.alexbomber12.memtag.data.queue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCsvParserTest {
    @Test
    fun parsesHeaderAndNormalizesEpc() {
        val csv = "\uFEFFEPC,Name\r\nab cd 12 34,Widget\r\n"

        val result = QueueCsvParser.parse(csv)

        assertEquals(listOf("ABCD1234"), result.epcs)
        assertTrue(result.invalidRows.isEmpty())
        assertEquals(0, result.duplicateCount)
    }

    @Test
    fun usesFirstNonEmptyCellAndDedupes() {
        val csv =
            """
            ABCDEF12
            ABCDEF12
            , , abcdef12 , extra
            """.trimIndent()

        val result = QueueCsvParser.parse(csv)

        assertEquals(listOf("ABCDEF12"), result.epcs)
        assertEquals(2, result.duplicateCount)
        assertTrue(result.invalidRows.isEmpty())
    }

    @Test
    fun tracksInvalidRows() {
        val csv =
            """
            EPC
            NOTHEX
            1234
            ABCDEF12
            """.trimIndent()

        val result = QueueCsvParser.parse(csv)

        assertEquals(listOf("ABCDEF12"), result.epcs)
        assertEquals(listOf(2, 3), result.invalidRows)
    }
}
