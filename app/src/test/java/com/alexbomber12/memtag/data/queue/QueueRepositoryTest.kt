package com.alexbomber12.memtag.data.queue

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alexbomber12.memtag.db.MemTagDatabase
import com.alexbomber12.memtag.domain.queue.QueueItemStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class QueueRepositoryTest {
    private lateinit var database: MemTagDatabase
    private lateinit var repository: QueueRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                MemTagDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        repository =
            DefaultQueueRepository(
                database = database,
                queueDao = database.queueDao(),
                queueMetaDao = database.queueMetaDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun updateStatusTransitionsQueueItem() =
        runBlocking {
            val now = 1_000L
            repository.insertItems(listOf("ABCDEF12"), now)

            repository.updateStatus("ABCDEF12", QueueItemStatus.FOUND, now + 100L)

            val item = repository.getAll().first()
            assertEquals(QueueItemStatus.FOUND, item.status)
        }

    @Test
    fun setCurrentEpcPersistsQueueMeta() =
        runBlocking {
            repository.setCurrentEpc("ABCDEF12")

            val meta = repository.observeMeta().first()
            assertEquals("ABCDEF12", meta?.currentEpcNormalized)
        }
}
