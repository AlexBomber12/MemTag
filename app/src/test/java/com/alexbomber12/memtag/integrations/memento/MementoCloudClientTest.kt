package com.alexbomber12.memtag.integrations.memento

import com.alexbomber12.memtag.core.logging.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException

class MementoCloudClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MementoCloudClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MementoCloudClient(TestLogger(), OkHttpClient.Builder().build())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesSchemaAndBuildsFieldMap() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "id": "lib-01",
                      "fields": [
                        {"id": "f_epc", "name": "EPC"},
                        {"id": "f_name", "name": "Name"},
                        {"id": "f_status", "name": "Status"}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val config =
            MementoConfig(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                token = "token-123",
                libraryId = "lib-01",
            )

        val schema = runBlocking { client.fetchLibrarySchema(config) }
        val fieldMap = FieldIdMap.fromSchema(schema)

        assertEquals("f_epc", fieldMap.epcId)
        assertEquals("f_name", fieldMap.nameId())
        assertEquals("f_status", fieldMap.statusId())

        val request = server.takeRequest()
        assertEquals("/v1/libraries/lib-01", request.requestUrl?.encodedPath)
        assertEquals("token-123", request.requestUrl?.queryParameter("token"))
    }

    @Test
    fun paginatesUsingNextPageToken() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "entries": [
                        {"id": "1", "fields": {"f_epc": "ABC123"}},
                        {"id": "2", "fields": {"f_epc": "DEF456"}}
                      ],
                      "nextPageToken": "page-2"
                    }
                    """.trimIndent(),
                ),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {
                      "entries": [
                        {"id": "3", "fields": {"f_epc": "XYZ999"}}
                      ]
                    }
                    """.trimIndent(),
                ),
        )

        val config =
            MementoConfig(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                token = "token-123",
                libraryId = "lib-01",
            )

        val pager = MementoEntriesPager(client, TestLogger())
        val pages = mutableListOf<MementoEntriesPage>()
        val summary =
            runBlocking {
                pager.pageThroughEntries(config, 2) { page ->
                    pages.add(page)
                }
            }

        assertEquals(PagingStrategy.NEXT_PAGE_TOKEN, summary.strategy)
        assertEquals(2, pages.size)
        assertEquals(3, pages.sumOf { it.entries.size })

        val firstRequest = server.takeRequest()
        assertEquals("/v1/libraries/lib-01/entries", firstRequest.requestUrl?.encodedPath)
        assertEquals("token-123", firstRequest.requestUrl?.queryParameter("token"))
        assertEquals("all", firstRequest.requestUrl?.queryParameter("fields"))
        assertEquals("2", firstRequest.requestUrl?.queryParameter("pageSize"))
        assertNotNull(firstRequest.requestUrl)

        val secondRequest = server.takeRequest()
        assertEquals("page-2", secondRequest.requestUrl?.queryParameter("pageToken"))
        assertEquals("2", secondRequest.requestUrl?.queryParameter("pageSize"))
    }

    @Test(expected = HttpException::class)
    fun unauthorizedReturnsHttpException() {
        server.enqueue(MockResponse().setResponseCode(401))
        val config =
            MementoConfig(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                token = "bad-token",
                libraryId = "lib-01",
            )
        runBlocking {
            client.fetchLibrarySchema(config)
        }
    }

    private class TestLogger : Logger {
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
