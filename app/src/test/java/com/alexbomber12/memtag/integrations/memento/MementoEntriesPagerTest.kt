package com.alexbomber12.memtag.integrations.memento

import com.alexbomber12.memtag.core.logging.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MementoEntriesPagerTest {
    @Test
    fun stopsWhenSinglePageUsesZeroBasedIndex() {
        val config =
            MementoConfig(
                baseUrl = "https://example.com",
                token = "token-123",
                libraryId = "lib-01",
            )
        val page =
            MementoEntriesPage(
                entries = listOf(MementoEntry(entryId = "1", fieldValues = emptyMap(), updatedAt = null)),
                nextPageToken = null,
                nextUrl = null,
                page = 0,
                pageCount = 1,
            )
        val client = FakeMementoClient(listOf(page))
        val pager = MementoEntriesPager(client, NoopLogger())
        val pages = mutableListOf<MementoEntriesPage>()

        val summary =
            runBlocking {
                pager.pageThroughEntries(config, 100) { fetchedPage ->
                    pages.add(fetchedPage)
                }
            }

        assertEquals(1, pages.size)
        assertEquals(1, summary.pagesFetched)
        assertEquals(PagingStrategy.PAGE_COUNT, summary.strategy)
        assertEquals(1, client.requestCount)
    }

    private class FakeMementoClient(
        private val pages: List<MementoEntriesPage>,
    ) : MementoClient {
        var requestCount: Int = 0
            private set

        override suspend fun fetchLibrarySchema(config: MementoConfig): MementoLibrarySchema {
            error("fetchLibrarySchema should not be called in this test.")
        }

        override suspend fun fetchEntriesPage(
            config: MementoConfig,
            request: MementoEntriesRequest,
        ): MementoEntriesPage {
            if (requestCount >= pages.size) {
                error("Unexpected page request.")
            }
            return pages[requestCount++]
        }
    }

    private class NoopLogger : Logger {
        override fun d(
            tag: String,
            msg: String,
        ) {
        }

        override fun i(
            tag: String,
            msg: String,
        ) {
        }

        override fun w(
            tag: String,
            msg: String,
            tr: Throwable?,
        ) {
        }

        override fun e(
            tag: String,
            msg: String,
            tr: Throwable?,
        ) {
        }
    }
}
