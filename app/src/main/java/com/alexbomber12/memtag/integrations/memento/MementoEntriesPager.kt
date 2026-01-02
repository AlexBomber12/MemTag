package com.alexbomber12.memtag.integrations.memento

import com.alexbomber12.memtag.core.logging.Logger

class MementoEntriesPager(
    private val client: MementoClient,
    private val logger: Logger,
) {
    suspend fun pageThroughEntries(
        config: MementoConfig,
        pageSize: Int,
        onPage: suspend (MementoEntriesPage) -> Unit,
    ): PagingSummary {
        var request = MementoEntriesRequest(pageSize = pageSize)
        var strategy = PagingStrategy.UNKNOWN
        var pagesFetched = 0
        var nextPageIndex = 1
        while (true) {
            val page = client.fetchEntriesPage(config, request)
            pagesFetched += 1
            if (strategy == PagingStrategy.UNKNOWN) {
                strategy = determineStrategy(page)
                logger.i(TAG, "Paging strategy: $strategy")
                if (strategy == PagingStrategy.UNKNOWN) {
                    throw MementoPagingException("Unable to determine paging strategy for entries.")
                }
            }
            onPage(page)
            when (strategy) {
                PagingStrategy.NEXT_PAGE_TOKEN -> {
                    val token = page.nextPageToken
                    if (token.isNullOrBlank()) {
                        break
                    }
                    request = request.copy(pageToken = token, pageIndex = null, nextUrl = null)
                }

                PagingStrategy.NEXT_URL -> {
                    val nextUrl = page.nextUrl
                    if (nextUrl.isNullOrBlank()) {
                        break
                    }
                    request = request.copy(pageToken = null, pageIndex = null, nextUrl = nextUrl)
                }

                PagingStrategy.PAGE_COUNT -> {
                    val current = page.page ?: nextPageIndex
                    val total =
                        page.pageCount
                            ?: throw MementoPagingException("Missing pageCount in paging response.")
                    // Some APIs return 0-based page indices; adjust the last page accordingly.
                    val lastPageIndex = if (current == 0) total - 1 else total
                    if (current >= lastPageIndex) {
                        break
                    }
                    nextPageIndex = current + 1
                    request = request.copy(pageToken = null, pageIndex = nextPageIndex, nextUrl = null)
                }

                PagingStrategy.PAGE_INDEX -> {
                    val current = page.page ?: nextPageIndex
                    if (page.entries.isEmpty()) {
                        break
                    }
                    if (pagesFetched >= MAX_PAGE_INDEX_PAGES) {
                        throw MementoPagingException("Paging exceeded safety cap of $MAX_PAGE_INDEX_PAGES pages.")
                    }
                    nextPageIndex = current + 1
                    request = request.copy(pageToken = null, pageIndex = nextPageIndex, nextUrl = null)
                }

                PagingStrategy.SINGLE_PAGE -> break
                PagingStrategy.UNKNOWN -> throw MementoPagingException("Unknown paging strategy.")
            }
        }
        return PagingSummary(strategy = strategy, pagesFetched = pagesFetched)
    }

    private fun determineStrategy(page: MementoEntriesPage): PagingStrategy {
        return when {
            !page.nextPageToken.isNullOrBlank() -> PagingStrategy.NEXT_PAGE_TOKEN
            !page.nextUrl.isNullOrBlank() -> PagingStrategy.NEXT_URL
            page.page != null && page.pageCount != null -> PagingStrategy.PAGE_COUNT
            page.page != null -> PagingStrategy.PAGE_INDEX
            else -> PagingStrategy.SINGLE_PAGE
        }
    }

    private companion object {
        const val TAG = "MementoEntriesPager"
        const val MAX_PAGE_INDEX_PAGES = 1_000
    }
}
