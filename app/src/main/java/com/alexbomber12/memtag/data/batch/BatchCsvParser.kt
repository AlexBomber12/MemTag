package com.alexbomber12.memtag.data.batch

import com.alexbomber12.memtag.domain.batch.BatchInputItem
import com.alexbomber12.memtag.util.epc.EpcNormalizer

object BatchCsvParser {
    data class Result(
        val items: List<BatchInputItem>,
        val invalidRows: List<Int>,
        val duplicateCount: Int,
    )

    fun parse(text: String): Result {
        val seen = LinkedHashSet<String>()
        val items = mutableListOf<BatchInputItem>()
        val invalidRows = mutableListOf<Int>()
        var duplicateCount = 0
        var headerIndices: HeaderIndices? = null

        text.lineSequence().forEachIndexed { index, rawLine ->
            if (rawLine.isBlank()) {
                return@forEachIndexed
            }
            val cells = parseCsvLine(rawLine)
            if (cells.isEmpty()) {
                return@forEachIndexed
            }
            val trimmed = cells.map { it.trim() }
            val firstNonEmpty = trimmed.firstOrNull { it.isNotBlank() }
            if (firstNonEmpty == null) {
                return@forEachIndexed
            }
            if (headerIndices == null) {
                val header = trimmed.map { it.trim().trimStart('\uFEFF') }
                headerIndices =
                    parseHeader(header)
                        ?: throw IllegalArgumentException("CSV must contain columns: Name, EPC")
                return@forEachIndexed
            }
            val indices = headerIndices ?: return@forEachIndexed
            val epcCandidate = cellValue(trimmed, indices.epcIndex)
            if (epcCandidate.isBlank()) {
                invalidRows.add(index + 1)
                return@forEachIndexed
            }
            val normalized = runCatching { EpcNormalizer.normalize(epcCandidate) }.getOrNull()
            if (normalized == null) {
                invalidRows.add(index + 1)
                return@forEachIndexed
            }
            if (!seen.add(normalized)) {
                duplicateCount += 1
                return@forEachIndexed
            }
            val name = cellValue(trimmed, indices.nameIndex)
            items.add(
                BatchInputItem(
                    epcNormalized = normalized,
                    name = name,
                ),
            )
        }

        if (headerIndices == null) {
            throw IllegalArgumentException("CSV must contain columns: Name, EPC")
        }

        return Result(
            items = items,
            invalidRows = invalidRows,
            duplicateCount = duplicateCount,
        )
    }

    private data class HeaderIndices(
        val epcIndex: Int,
        val nameIndex: Int,
    )

    private fun parseHeader(cells: List<String>): HeaderIndices? {
        var epcIndex: Int? = null
        var nameIndex: Int? = null
        cells.forEachIndexed { index, value ->
            val normalized = value.trim().trimStart('\uFEFF').lowercase()
            when (normalized) {
                "epc" -> epcIndex = index
                "name" -> nameIndex = index
            }
        }
        val resolvedEpc = epcIndex ?: return null
        val resolvedName = nameIndex ?: return null
        return HeaderIndices(epcIndex = resolvedEpc, nameIndex = resolvedName)
    }

    private fun cellValue(
        cells: List<String>,
        index: Int,
    ): String {
        return cells.getOrNull(index).orEmpty().trim()
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
