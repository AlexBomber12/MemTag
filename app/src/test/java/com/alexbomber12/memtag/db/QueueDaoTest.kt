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
class QueueDaoTest {
    private lateinit var database: MemTagDatabase
    private lateinit var dao: QueueDao

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                MemTagDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        dao = database.queueDao()
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
                QueueItemEntity(
                    epcNormalized = "ABCDEF12",
                    status = "PENDING",
                    createdAt = now,
                    updatedAt = now,
                    note = null,
                    lastProximity = null,
                )
            dao.insertAll(listOf(item))
            val secondInsert = dao.insertAll(listOf(item))

            assertEquals(-1L, secondInsert.first())
            assertNotNull(dao.getByEpc("ABCDEF12"))
        }

    @Test
    fun updateStatusUpdatesRow() =
        runBlocking {
            val now = 1_000L
            val item =
                QueueItemEntity(
                    epcNormalized = "ABCDEF12",
                    status = "PENDING",
                    createdAt = now,
                    updatedAt = now,
                    note = null,
                    lastProximity = null,
                )
            dao.insertAll(listOf(item))

            dao.updateStatus("ABCDEF12", "FOUND", now + 500L)

            val loaded = dao.getByEpc("ABCDEF12")
            assertEquals("FOUND", loaded?.status)
            assertEquals(now + 500L, loaded?.updatedAt)
        }

    @Test
    fun ordersByStatusThenCreatedAt() =
        runBlocking {
            val items =
                listOf(
                    QueueItemEntity(
                        epcNormalized = "P1",
                        status = "PENDING",
                        createdAt = 10L,
                        updatedAt = 10L,
                        note = null,
                        lastProximity = null,
                    ),
                    QueueItemEntity(
                        epcNormalized = "F1",
                        status = "FOUND",
                        createdAt = 20L,
                        updatedAt = 20L,
                        note = null,
                        lastProximity = null,
                    ),
                    QueueItemEntity(
                        epcNormalized = "P2",
                        status = "PENDING",
                        createdAt = 30L,
                        updatedAt = 30L,
                        note = null,
                        lastProximity = null,
                    ),
                    QueueItemEntity(
                        epcNormalized = "S1",
                        status = "SKIPPED",
                        createdAt = 15L,
                        updatedAt = 15L,
                        note = null,
                        lastProximity = null,
                    ),
                    QueueItemEntity(
                        epcNormalized = "N1",
                        status = "NOT_FOUND",
                        createdAt = 12L,
                        updatedAt = 12L,
                        note = null,
                        lastProximity = null,
                    ),
                )
            dao.insertAll(items)

            val ordered = dao.getAllFlow().first()

            assertEquals(listOf("P1", "P2", "F1", "S1", "N1"), ordered.map { it.epcNormalized })
        }
}
