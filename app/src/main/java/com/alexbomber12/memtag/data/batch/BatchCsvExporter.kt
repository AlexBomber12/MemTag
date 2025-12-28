package com.alexbomber12.memtag.data.batch

import com.alexbomber12.memtag.domain.batch.BatchExportRow
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
        builder.append("epc,name,status,lastSeenAt,lastRssi,source,note\n")
        rows.forEach { row ->
            val lastSeen =
                if (row.lastSeenAt != null) {
                    formatter.format(Instant.ofEpochMilli(row.lastSeenAt).atZone(zoneId))
                } else {
                    ""
                }
            val lastRssi = row.lastRssi?.toString().orEmpty()
            val source = row.source?.name.orEmpty()
            val values =
                listOf(
                    row.epc,
                    row.name.orEmpty(),
                    row.status.name,
                    lastSeen,
                    lastRssi,
                    source,
                    row.note.orEmpty(),
                )
            val line = values.joinToString(",") { value -> escapeCsv(value) }
            builder.append(line).append("\n")
        }
        return builder.toString()
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
