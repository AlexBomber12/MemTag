package com.alexbomber12.memtag.data.batch

import com.alexbomber12.memtag.domain.batch.BatchInputItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchCsvParserTest {
    @Test
    fun parsesHeaderAndNormalizesEpc() {
        val csv = "\uFEFFEPC,Name,Note\r\nab cd 12 34,Widget,Fragile\r\n"

        val result = BatchCsvParser.parse(csv)

        assertEquals(
            listOf(
                BatchInputItem(
                    epcNormalized = "ABCD1234",
                    name = "Widget",
                    note = "Fragile",
                ),
            ),
            result.items,
        )
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

        val result = BatchCsvParser.parse(csv)

        assertEquals(listOf(BatchInputItem(epcNormalized = "ABCDEF12", name = null, note = null)), result.items)
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

        val result = BatchCsvParser.parse(csv)

        assertEquals(listOf(BatchInputItem(epcNormalized = "ABCDEF12", name = null, note = null)), result.items)
        assertEquals(listOf(2, 3), result.invalidRows)
    }
}
