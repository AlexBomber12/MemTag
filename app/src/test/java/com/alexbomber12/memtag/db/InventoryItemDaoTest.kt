package com.alexbomber12.memtag.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class InventoryItemDaoTest {
    private lateinit var database: MemTagDatabase
    private lateinit var dao: InventoryItemDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                MemTagDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        dao = database.inventoryItemDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertAndLookupByEpcIsLibraryScoped() =
        runBlocking {
            val item =
                InventoryItemEntity(
                    libraryId = "lib-01",
                    entryId = "entry-1",
                    epcNormalized = "ABC123",
                    name = "Widget",
                    content = null,
                    locationPath = null,
                    status = null,
                    category = null,
                    comment = null,
                    labelRev = null,
                    toPrint = null,
                    um = null,
                    qrRaw = null,
                    photoThumbUrlOrRef = null,
                    updatedAt = null,
                    syncRunId = 10L,
                )
            val otherLibraryItem =
                InventoryItemEntity(
                    libraryId = "lib-02",
                    entryId = "entry-2",
                    epcNormalized = "ABC123",
                    name = "Other",
                    content = null,
                    locationPath = null,
                    status = null,
                    category = null,
                    comment = null,
                    labelRev = null,
                    toPrint = null,
                    um = null,
                    qrRaw = null,
                    photoThumbUrlOrRef = null,
                    updatedAt = null,
                    syncRunId = 11L,
                )
            dao.upsertAll(listOf(item, otherLibraryItem))

            val loaded = dao.getByEpc("lib-01", "ABC123")
            assertNotNull(loaded)
            assertEquals("entry-1", loaded?.entryId)
            val loadedOther = dao.getByEpc("lib-02", "ABC123")
            assertNotNull(loadedOther)
            assertEquals("entry-2", loadedOther?.entryId)
        }

    @Test
    fun uniqueEpcReplacesExistingRow() =
        runBlocking {
            val first =
                InventoryItemEntity(
                    libraryId = "lib-01",
                    entryId = "entry-1",
                    epcNormalized = "ABC123",
                    name = "First",
                    content = null,
                    locationPath = null,
                    status = null,
                    category = null,
                    comment = null,
                    labelRev = null,
                    toPrint = null,
                    um = null,
                    qrRaw = null,
                    photoThumbUrlOrRef = null,
                    updatedAt = null,
                    syncRunId = 100L,
                )
            val second =
                InventoryItemEntity(
                    libraryId = "lib-01",
                    entryId = "entry-2",
                    epcNormalized = "ABC123",
                    name = "Second",
                    content = null,
                    locationPath = null,
                    status = null,
                    category = null,
                    comment = null,
                    labelRev = null,
                    toPrint = null,
                    um = null,
                    qrRaw = null,
                    photoThumbUrlOrRef = null,
                    updatedAt = null,
                    syncRunId = 101L,
                )

            dao.upsertAll(listOf(first))
            dao.upsertAll(listOf(second))

            val loaded = dao.getByEpc("lib-01", "ABC123")
            assertEquals("entry-2", loaded?.entryId)
            assertEquals("Second", loaded?.name)
        }
}
