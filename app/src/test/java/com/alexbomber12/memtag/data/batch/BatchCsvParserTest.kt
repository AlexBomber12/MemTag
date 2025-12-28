package com.alexbomber12.memtag.data.batch

import com.alexbomber12.memtag.domain.batch.BatchInputItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCsvParserTest {
    @Test
    fun parsesMementoCsvWithCyrillicName() {
        val csv = "\uFEFFName,EPC\r\nПример,ab cd 12 34\r\n"

        val result = BatchCsvParser.parse(csv)

        assertEquals(
            listOf(
                BatchInputItem(
                    epcNormalized = "ABCD1234",
                    name = "Пример",
                ),
            ),
            result.items,
        )
        assertTrue(result.invalidRows.isEmpty())
        assertEquals(0, result.duplicateCount)
    }

    @Test
    fun ignoresExtraColumns() {
        val csv = "Name,EPC,Ignored\r\nWidget,ABCDEF12,extra\r\n"

        val result = BatchCsvParser.parse(csv)

        assertEquals(listOf(BatchInputItem(epcNormalized = "ABCDEF12", name = "Widget")), result.items)
        assertTrue(result.invalidRows.isEmpty())
        assertEquals(0, result.duplicateCount)
    }

    @Test
    fun tracksInvalidRows() {
        val csv =
            """
            Name,EPC
            NOTHEX
            Widget,1234
            Widget,ABCDEF12
            """.trimIndent()

        val result = BatchCsvParser.parse(csv)

        assertEquals(listOf(BatchInputItem(epcNormalized = "ABCDEF12", name = "Widget")), result.items)
        assertEquals(listOf(2, 3), result.invalidRows)
    }

    @Test
    fun failsWhenMissingEpcColumn() {
        val csv = "Name,Label\r\nWidget,ABCDEF12\r\n"

        val error =
            runCatching {
                BatchCsvParser.parse(csv)
            }.exceptionOrNull()

        assertEquals("CSV must contain columns: Name, EPC", error?.message)
    }

    @Test
    fun failsWhenMissingNameColumn() {
        val csv = "EPC,Label\r\nABCDEF12,Widget\r\n"

        val error =
            runCatching {
                BatchCsvParser.parse(csv)
            }.exceptionOrNull()

        assertEquals("CSV must contain columns: Name, EPC", error?.message)
    }
}
