package com.alexbomber12.memtag.data.batch

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.alexbomber12.memtag.db.MemTagDatabase
import com.alexbomber12.memtag.domain.batch.BatchInputItem
import com.alexbomber12.memtag.domain.batch.BatchSource
import com.alexbomber12.memtag.domain.batch.BatchStatus
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
class BatchRepositoryTest {
    private lateinit var database: MemTagDatabase
    private lateinit var repository: BatchRepository

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
            DefaultBatchRepository(
                database = database,
                batchDao = database.batchDao(),
                batchMetaDao = database.batchMetaDao(),
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun updateSessionTransitionsBatchItem() =
        runBlocking {
            val now = 1_000L
            repository.insertItems(
                listOf(
                    BatchInputItem(
                        epcNormalized = "ABCDEF12",
                        name = null,
                        note = null,
                    ),
                ),
                now,
            )

            repository.updateSession(
                epcNormalized = "ABCDEF12",
                status = BatchStatus.PRESENT,
                updatedAt = now + 100L,
                lastSeenAt = now + 50L,
                lastRssi = -45,
                source = BatchSource.SCAN,
            )

            val item = repository.getAll().first()
            assertEquals(BatchStatus.PRESENT, item.session.status)
            assertEquals(now + 50L, item.session.lastSeenAt)
            assertEquals(-45, item.session.lastRssi)
            assertEquals(BatchSource.SCAN, item.session.source)
        }

    @Test
    fun setCurrentEpcPersistsBatchMeta() =
        runBlocking {
            repository.setCurrentEpc("ABCDEF12")

            val meta = repository.observeMeta().first()
            assertEquals("ABCDEF12", meta?.currentEpcNormalized)
        }
}
