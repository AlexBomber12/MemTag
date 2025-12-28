package com.alexbomber12.memtag.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
class BatchDaoTest {
    private lateinit var database: MemTagDatabase
    private lateinit var dao: BatchDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                MemTagDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        dao = database.batchDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAllIgnoresDuplicates() =
        runBlocking {
            val now = 1_000L
            val item =
                BatchItemEntity(
                    epcNormalized = "ABCDEF12",
                    name = null,
                    status = "UNKNOWN",
                    createdAt = now,
                    updatedAt = now,
                    note = null,
                    lastProximity = null,
                    lastSeenAt = null,
                    source = null,
                )
            dao.insertAll(listOf(item))
            val secondInsert = dao.insertAll(listOf(item))

            assertEquals(-1L, secondInsert.first())
            assertNotNull(dao.getByEpc("ABCDEF12"))
        }

    @Test
    fun updateSessionUpdatesRow() =
        runBlocking {
            val now = 1_000L
            val item =
                BatchItemEntity(
                    epcNormalized = "ABCDEF12",
                    name = "Widget",
                    status = "UNKNOWN",
                    createdAt = now,
                    updatedAt = now,
                    note = "Fragile",
                    lastProximity = null,
                    lastSeenAt = null,
                    source = null,
                )
            dao.insertAll(listOf(item))

            dao.updateSession(
                epcNormalized = "ABCDEF12",
                status = "PRESENT",
                updatedAt = now + 500L,
                lastSeenAt = now + 250L,
                lastRssi = -43,
                source = "SCAN",
            )

            val loaded = dao.getByEpc("ABCDEF12")
            assertEquals("PRESENT", loaded?.status)
            assertEquals(now + 500L, loaded?.updatedAt)
            assertEquals(now + 250L, loaded?.lastSeenAt)
            assertEquals(-43, loaded?.lastProximity)
            assertEquals("SCAN", loaded?.source)
        }

    @Test
    fun ordersByCreatedAt() =
        runBlocking {
            val items =
                listOf(
                    BatchItemEntity(
                        epcNormalized = "A1",
                        name = null,
                        status = "UNKNOWN",
                        createdAt = 10L,
                        updatedAt = 10L,
                        note = null,
                        lastProximity = null,
                        lastSeenAt = null,
                        source = null,
                    ),
                    BatchItemEntity(
                        epcNormalized = "A2",
                        name = null,
                        status = "PRESENT",
                        createdAt = 5L,
                        updatedAt = 5L,
                        note = null,
                        lastProximity = null,
                        lastSeenAt = null,
                        source = null,
                    ),
                    BatchItemEntity(
                        epcNormalized = "A3",
                        name = null,
                        status = "MISSING",
                        createdAt = 20L,
                        updatedAt = 20L,
                        note = null,
                        lastProximity = null,
                        lastSeenAt = null,
                        source = null,
                    ),
                )
            dao.insertAll(items)

            val ordered = dao.getAllFlow().first()

            assertEquals(listOf("A2", "A1", "A3"), ordered.map { it.epcNormalized })
        }
}
