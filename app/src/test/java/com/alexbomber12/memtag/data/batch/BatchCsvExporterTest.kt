package com.alexbomber12.memtag.data.batch

import com.alexbomber12.memtag.domain.batch.BatchExportRow
import com.alexbomber12.memtag.domain.batch.BatchStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class BatchCsvExporterTest {
    @Test
    fun exportsHeaderAndCyrillicName() {
        val rows =
            listOf(
                BatchExportRow(
                    epc = "ABCDEF12",
                    name = "Пример",
                    status = BatchStatus.UNKNOWN,
                    updatedAt = null,
                ),
            )

        val csv = BatchCsvExporter.export(rows, ZoneId.of("UTC"))
        val lines = csv.trimEnd().split("\n")

        assertEquals("Name,Status,EPC,UpdatedAt", lines[0])
        assertEquals("Пример,Unknown,ABCDEF12,", lines[1])
    }

    @Test
    fun exportsUpdatedAtForFoundOnly() {
        val rows =
            listOf(
                BatchExportRow(
                    epc = "ABCDEF01",
                    name = "Found tag",
                    status = BatchStatus.FOUND,
                    updatedAt = 1_000L,
                ),
                BatchExportRow(
                    epc = "ABCDEF02",
                    name = "Unknown tag",
                    status = BatchStatus.UNKNOWN,
                    updatedAt = 1_000L,
                ),
                BatchExportRow(
                    epc = "ABCDEF03",
                    name = "Not found tag",
                    status = BatchStatus.NOT_FOUND,
                    updatedAt = 1_000L,
                ),
            )

        val csv = BatchCsvExporter.export(rows, ZoneId.of("UTC"))
        val lines = csv.trimEnd().split("\n")

        assertEquals("Name,Status,EPC,UpdatedAt", lines[0])
        assertEquals("Found tag,Found,ABCDEF01,1970-01-01T00:00:01Z", lines[1])
        assertEquals("Unknown tag,Unknown,ABCDEF02,", lines[2])
        assertEquals("Not found tag,NotFound,ABCDEF03,", lines[3])
    }
}
