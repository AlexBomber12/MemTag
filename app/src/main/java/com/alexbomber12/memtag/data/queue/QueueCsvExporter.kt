package com.alexbomber12.memtag.data.queue

import com.alexbomber12.memtag.domain.queue.QueueItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object QueueCsvExporter {
    private val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun export(
        items: List<QueueItem>,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): String {
        val builder = StringBuilder()
        builder.append("EPC,Status,UpdatedAt,Note\n")
        items.forEach { item ->
            val updatedAt =
                if (item.updatedAt > 0L) {
                    formatter.format(Instant.ofEpochMilli(item.updatedAt).atZone(zoneId))
                } else {
                    ""
                }
            val row =
                listOf(
                    item.epcNormalized,
                    item.status.name,
                    updatedAt,
                    item.note.orEmpty(),
                )
                    .joinToString(",") { value -> escapeCsv(value) }
            builder.append(row).append("\n")
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
