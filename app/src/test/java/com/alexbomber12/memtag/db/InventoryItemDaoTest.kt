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
    fun upsertAndLookupByEpc() =
        runBlocking {
            val item =
                InventoryItemEntity(
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
                )
            dao.upsertAll(listOf(item))

            val loaded = dao.getByEpc("ABC123")
            assertNotNull(loaded)
            assertEquals("entry-1", loaded?.entryId)
        }

    @Test
    fun uniqueEpcReplacesExistingRow() =
        runBlocking {
            val first =
                InventoryItemEntity(
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
                )
            val second =
                InventoryItemEntity(
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
                )

            dao.upsertAll(listOf(first))
            dao.upsertAll(listOf(second))

            val loaded = dao.getByEpc("ABC123")
            assertEquals("entry-2", loaded?.entryId)
            assertEquals("Second", loaded?.name)
        }
}
