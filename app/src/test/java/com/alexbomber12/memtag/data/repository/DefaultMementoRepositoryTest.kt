package com.alexbomber12.memtag.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alexbomber12.memtag.core.logging.Logger
import com.alexbomber12.memtag.data.settings.AppSettings
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.db.MemTagDatabase
import com.alexbomber12.memtag.domain.SyncStatus
import com.alexbomber12.memtag.integrations.memento.MementoClient
import com.alexbomber12.memtag.integrations.memento.MementoConfig
import com.alexbomber12.memtag.integrations.memento.MementoEntriesPage
import com.alexbomber12.memtag.integrations.memento.MementoEntriesRequest
import com.alexbomber12.memtag.integrations.memento.MementoEntry
import com.alexbomber12.memtag.integrations.memento.MementoField
import com.alexbomber12.memtag.integrations.memento.MementoLibrarySchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class DefaultMementoRepositoryTest {
    private lateinit var database: MemTagDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                MemTagDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun syncLibraryStoresLocationFromMapName() =
        runBlocking {
            val settings =
                AppSettings(
                    mementoBaseUrl = "https://example.com",
                    mementoToken = "token-123",
                    mementoLibraryId = "lib-01",
                )
            val settingsStore = FakeSettingsStore(settings)
            val schema =
                MementoLibrarySchema(
                    fields =
                        listOf(
                            MementoField(id = "f_epc", name = "EPC"),
                            MementoField(id = "f_location", name = "Location"),
                        ),
                )
            val entry =
                MementoEntry(
                    entryId = "entry-1",
                    fieldValues =
                        mapOf(
                            "f_epc" to "ABC12345",
                            "f_location" to mapOf("name" to "Aisle 3"),
                        ),
                    updatedAt = null,
                )
            val page =
                MementoEntriesPage(
                    entries = listOf(entry),
                    nextPageToken = null,
                    nextUrl = null,
                    page = null,
                    pageCount = null,
                )
            val repository =
                DefaultMementoRepository(
                    settingsStore = settingsStore,
                    database = database,
                    inventoryItemDao = database.inventoryItemDao(),
                    syncStateDao = database.syncStateDao(),
                    mementoClient = FakeMementoClient(schema, page),
                    logger = NoopLogger(),
                    ioDispatcher = Dispatchers.Unconfined,
                )

            val result = repository.syncLibrary("lib-01") { }

            assertEquals(SyncStatus.SUCCESS, result.status)
            val stored = database.inventoryItemDao().getByEpc("lib-01", "ABC12345")
            assertNotNull(stored)
            assertEquals("Aisle 3", stored?.locationPath)
        }

    @Test
    fun syncLibraryStoresLocationFromDisplayValue() =
        runBlocking {
            val settings =
                AppSettings(
                    mementoBaseUrl = "https://example.com",
                    mementoToken = "token-123",
                    mementoLibraryId = "lib-01",
                )
            val settingsStore = FakeSettingsStore(settings)
            val schema =
                MementoLibrarySchema(
                    fields =
                        listOf(
                            MementoField(id = "f_epc", name = "EPC"),
                            MementoField(id = "f_location", name = "Location"),
                        ),
                )
            val entry =
                MementoEntry(
                    entryId = "entry-1",
                    fieldValues =
                        mapOf(
                            "f_epc" to "ABC12346",
                            "f_location" to mapOf("displayValue" to "Building A/Floor 2"),
                        ),
                    updatedAt = null,
                )
            val page =
                MementoEntriesPage(
                    entries = listOf(entry),
                    nextPageToken = null,
                    nextUrl = null,
                    page = null,
                    pageCount = null,
                )
            val repository =
                DefaultMementoRepository(
                    settingsStore = settingsStore,
                    database = database,
                    inventoryItemDao = database.inventoryItemDao(),
                    syncStateDao = database.syncStateDao(),
                    mementoClient = FakeMementoClient(schema, page),
                    logger = NoopLogger(),
                    ioDispatcher = Dispatchers.Unconfined,
                )

            val result = repository.syncLibrary("lib-01") { }

            assertEquals(SyncStatus.SUCCESS, result.status)
            val stored = database.inventoryItemDao().getByEpc("lib-01", "ABC12346")
            assertNotNull(stored)
            assertEquals("Building A/Floor 2", stored?.locationPath)
        }

    @Test
    fun syncLibraryStoresLocationFromPathList() =
        runBlocking {
            val settings =
                AppSettings(
                    mementoBaseUrl = "https://example.com",
                    mementoToken = "token-123",
                    mementoLibraryId = "lib-01",
                )
            val settingsStore = FakeSettingsStore(settings)
            val schema =
                MementoLibrarySchema(
                    fields =
                        listOf(
                            MementoField(id = "f_epc", name = "EPC"),
                            MementoField(id = "f_location", name = "Location path"),
                        ),
                )
            val entry =
                MementoEntry(
                    entryId = "entry-2",
                    fieldValues =
                        mapOf(
                            "f_epc" to "DEF67890",
                            "f_location" to
                                mapOf(
                                    "path" to listOf("Apartment Novara", "Living Room", "livingroom"),
                                ),
                        ),
                    updatedAt = null,
                )
            val page =
                MementoEntriesPage(
                    entries = listOf(entry),
                    nextPageToken = null,
                    nextUrl = null,
                    page = null,
                    pageCount = null,
                )
            val repository =
                DefaultMementoRepository(
                    settingsStore = settingsStore,
                    database = database,
                    inventoryItemDao = database.inventoryItemDao(),
                    syncStateDao = database.syncStateDao(),
                    mementoClient = FakeMementoClient(schema, page),
                    logger = NoopLogger(),
                    ioDispatcher = Dispatchers.Unconfined,
                )

            val result = repository.syncLibrary("lib-01") { }

            assertEquals(SyncStatus.SUCCESS, result.status)
            val stored = database.inventoryItemDao().getByEpc("lib-01", "DEF67890")
            assertNotNull(stored)
            assertEquals("Apartment Novara/Living Room/livingroom", stored?.locationPath)
        }

    @Test
    fun syncLibraryDeduplicatesByEntryId() =
        runBlocking {
            val settings =
                AppSettings(
                    mementoBaseUrl = "https://example.com",
                    mementoToken = "token-123",
                    mementoLibraryId = "lib-01",
                )
            val settingsStore = FakeSettingsStore(settings)
            val schema =
                MementoLibrarySchema(
                    fields =
                        listOf(
                            MementoField(id = "f_epc", name = "EPC"),
                        ),
                )
            val page1 =
                MementoEntriesPage(
                    entries =
                        listOf(
                            MementoEntry(
                                entryId = "entry-1",
                                fieldValues = mapOf("f_epc" to "ABC12345"),
                                updatedAt = null,
                            ),
                            MementoEntry(
                                entryId = "entry-2",
                                fieldValues = mapOf("f_epc" to "DEF67890"),
                                updatedAt = null,
                            ),
                        ),
                    nextPageToken = "page-2",
                    nextUrl = null,
                    page = null,
                    pageCount = null,
                )
            val page2 =
                MementoEntriesPage(
                    entries =
                        listOf(
                            MementoEntry(
                                entryId = "entry-2",
                                fieldValues = mapOf("f_epc" to "DEF67890"),
                                updatedAt = null,
                            ),
                            MementoEntry(
                                entryId = "entry-3",
                                fieldValues = mapOf("f_epc" to "AAAABBBB"),
                                updatedAt = null,
                            ),
                        ),
                    nextPageToken = null,
                    nextUrl = null,
                    page = null,
                    pageCount = null,
                )
            val repository =
                DefaultMementoRepository(
                    settingsStore = settingsStore,
                    database = database,
                    inventoryItemDao = database.inventoryItemDao(),
                    syncStateDao = database.syncStateDao(),
                    mementoClient = FakePagedMementoClient(schema, listOf(page1, page2)),
                    logger = NoopLogger(),
                    ioDispatcher = Dispatchers.Unconfined,
                )

            val result = repository.syncLibrary("lib-01") { }

            assertEquals(3, result.downloadedCount)
            assertEquals(3, result.savedCount)
            assertEquals(0, result.ignoredCount)
            val count = database.inventoryItemDao().observeCount("lib-01").first()
            assertEquals(3, count)
        }

    private class FakeMementoClient(
        private val schema: MementoLibrarySchema,
        private val page: MementoEntriesPage,
    ) : MementoClient {
        override suspend fun fetchLibrarySchema(config: MementoConfig): MementoLibrarySchema = schema

        override suspend fun fetchEntriesPage(
            config: MementoConfig,
            request: MementoEntriesRequest,
        ): MementoEntriesPage = page
    }

    private class FakePagedMementoClient(
        private val schema: MementoLibrarySchema,
        private val pages: List<MementoEntriesPage>,
    ) : MementoClient {
        private var pageIndex = 0

        override suspend fun fetchLibrarySchema(config: MementoConfig): MementoLibrarySchema = schema

        override suspend fun fetchEntriesPage(
            config: MementoConfig,
            request: MementoEntriesRequest,
        ): MementoEntriesPage {
            if (pageIndex >= pages.size) {
                error("Unexpected page request.")
            }
            return pages[pageIndex++]
        }
    }

    private class FakeSettingsStore(initial: AppSettings) : SettingsStore {
        private val state = MutableStateFlow(initial)

        override val settingsFlow = state

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            state.value = transform(state.value)
        }

        override suspend fun setMemento(
            baseUrl: String,
            token: String,
            libraryId: String,
        ) {
            update {
                it.copy(
                    mementoBaseUrl = baseUrl,
                    mementoToken = token,
                    mementoLibraryId = libraryId,
                )
            }
        }

        override suspend fun setUhf(
            region: String,
            power: Int,
        ) {
            update { it.copy(uhfRegion = region, uhfPower = power) }
        }

        override suspend fun setScan2d(
            action: String,
            extraKey: String,
        ) {
            update { it.copy(scan2dAction = action, scan2dExtraKey = extraKey) }
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
