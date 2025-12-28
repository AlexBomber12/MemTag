package com.alexbomber12.memtag.data.batch

import com.alexbomber12.memtag.domain.batch.BatchExportRow
import com.alexbomber12.memtag.domain.batch.BatchStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object BatchCsvExporter {
    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun export(
        rows: List<BatchExportRow>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val builder = StringBuilder()
        builder.append("Name,Status,EPC,UpdatedAt\n")
        rows.forEach { row ->
            val updatedAt =
                if (row.status == BatchStatus.FOUND && row.updatedAt != null && row.updatedAt > 0) {
                    formatter.format(Instant.ofEpochMilli(row.updatedAt).atZone(zoneId))
                } else {
                    ""
                }
            val values =
                listOf(
                    row.name,
                    statusLabel(row.status),
                    row.epc,
                    updatedAt,
                )
            val line = values.joinToString(",") { value -> escapeCsv(value) }
            builder.append(line).append("\n")
        }
        return builder.toString()
    }

    private fun statusLabel(status: BatchStatus): String {
        return when (status) {
            BatchStatus.UNKNOWN -> "Unknown"
            BatchStatus.FOUND -> "Found"
            BatchStatus.NOT_FOUND -> "NotFound"
            BatchStatus.EXTRA -> "Extra"
        }
    }

    private fun escapeCsv(value: String): String {
        if (value.isEmpty()) {
            return ""
        }
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            value
        }
    }
}
