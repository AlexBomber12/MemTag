package com.alexbomber12.memtag.integrations.memento

data class MementoConfig(
    val baseUrl: String,
    val token: String,
    val libraryId: String,
)

data class MementoField(
    val id: String,
    val name: String,
)

data class MementoLibrarySchema(
    val fields: List<MementoField>,
)

data class MementoEntry(
    val entryId: String?,
    val fieldValues: Map<String, Any?>,
    val updatedAt: Long?,
    val status: String? = null,
)

data class MementoEntriesPage(
    val entries: List<MementoEntry>,
    val nextPageToken: String?,
    val nextUrl: String?,
    val page: Int?,
    val pageCount: Int?,
)

data class MementoEntriesRequest(
    val pageSize: Int,
    val pageToken: String? = null,
    val pageIndex: Int? = null,
    val nextUrl: String? = null,
)

data class PagingSummary(
    val strategy: PagingStrategy,
    val pagesFetched: Int,
)

enum class PagingStrategy {
    NEXT_PAGE_TOKEN,
    NEXT_URL,
    PAGE_COUNT,
    PAGE_INDEX,
    SINGLE_PAGE,
    UNKNOWN,
}
