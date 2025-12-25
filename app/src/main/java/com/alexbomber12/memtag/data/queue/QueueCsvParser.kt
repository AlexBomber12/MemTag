package com.alexbomber12.memtag.data.queue

import com.alexbomber12.memtag.util.epc.EpcNormalizer

object QueueCsvParser {
    data class Result(
        val epcs: List<String>,
        val invalidRows: List<Int>,
        val duplicateCount: Int,
    )

    fun parse(text: String): Result {
        val seen = LinkedHashSet<String>()
        val invalidRows = mutableListOf<Int>()
        var duplicateCount = 0
        var headerChecked = false

        text.lineSequence().forEachIndexed { index, rawLine ->
            if (rawLine.isBlank()) {
                return@forEachIndexed
            }
            val cells = parseCsvLine(rawLine)
            if (cells.isEmpty()) {
                return@forEachIndexed
            }
            val trimmedCells = cells.map { it.trim() }
            val firstNonEmpty = trimmedCells.firstOrNull { it.isNotBlank() }
            if (firstNonEmpty == null) {
                return@forEachIndexed
            }
            val firstCell = trimmedCells.firstOrNull().orEmpty().trimStart('\uFEFF')
            if (!headerChecked) {
                headerChecked = true
                if (firstCell.equals("EPC", ignoreCase = true)) {
                    return@forEachIndexed
                }
            }
            val candidate = firstNonEmpty.trimStart('\uFEFF')
            val normalized =
                runCatching { EpcNormalizer.normalize(candidate) }.getOrNull()
            if (normalized == null) {
                invalidRows.add(index + 1)
                return@forEachIndexed
            }
            if (!seen.add(normalized)) {
                duplicateCount += 1
            }
        }

        return Result(
            epcs = seen.toList(),
            invalidRows = invalidRows,
            duplicateCount = duplicateCount,
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val char = line[index]
            when (char) {
                '"' -> {
                    val next = if (index + 1 < line.length) line[index + 1] else null
                    if (inQuotes && next == '"') {
                        current.append('"')
                        index += 1
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                ',' -> {
                    if (inQuotes) {
                        current.append(char)
                    } else {
                        result.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
            index += 1
        }
        result.add(current.toString())
        return result
    }
}
