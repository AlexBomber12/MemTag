package com.alexbomber12.memtag.integrations.uhf

import com.alexbomber12.memtag.data.AppDefaults
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

class FakeUhfReader(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : UhfReader {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val inventoryFlow =
        MutableSharedFlow<TagReading>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val epcPool =
        listOf(
            "E2000017221101441890ABCD",
            "E2000017221101441890ABCE",
            "E2000017221101441890ABCF",
            "E2000017221101441890ABD0",
        )
    private val random = Random(1)

    private var initialized = false
    private var inventoryJob: Job? = null
    private var powerDbm = AppDefaults.UHF_POWER
    private var region = UhfRegion.fromSettings(AppDefaults.UHF_REGION)
    private var epcIndex = 0

    override suspend fun initialize(): Result<Unit> =
        synchronized(lock) {
            if (initialized) {
                return Result.success(Unit)
            }
            initialized = true
            Result.success(Unit)
        }

    override suspend fun close(): Result<Unit> {
        stopInventory()
        synchronized(lock) {
            initialized = false
        }
        return Result.success(Unit)
    }

    override suspend fun readSingle(timeoutMs: Long): Result<String> {
        synchronized(lock) {
            if (!initialized) {
                return Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryJob != null) {
                return Result.failure(UhfError.OperationInProgress.asException())
            }
        }
        return try {
            withTimeout(timeoutMs) {
                delay(150)
                Result.success(nextEpc())
            }
        } catch (_: TimeoutCancellationException) {
            Result.failure(UhfError.Timeout.asException())
        }
    }

    override fun startInventory(filterEpcHex: String?): Flow<TagReading> {
        synchronized(lock) {
            if (!initialized) {
                return flow { throw UhfError.NotInitialized.asException() }
            }
            if (inventoryJob == null) {
                inventoryJob =
                    scope.launch {
                        while (isActive) {
                            val epc = nextEpc()
                            val rssi = random.nextInt(-70, -35)
                            inventoryFlow.emit(
                                TagReading(
                                    epcHex = epc,
                                    rssi = rssi,
                                    timestampMs = System.currentTimeMillis(),
                                ),
                            )
                            delay(150)
                        }
                    }
            }
        }
        val baseFlow = inventoryFlow.asSharedFlow()
        return if (filterEpcHex.isNullOrBlank()) {
            baseFlow
        } else {
            baseFlow.filter { it.epcHex.equals(filterEpcHex, ignoreCase = true) }
        }
    }

    override suspend fun stopInventory(): Result<Unit> {
        val jobToCancel =
            synchronized(lock) {
                val job = inventoryJob
                inventoryJob = null
                job
            }
        jobToCancel?.cancel()
        return Result.success(Unit)
    }

    override suspend fun setPower(dbm: Int): Result<Unit> =
        synchronized(lock) {
            powerDbm = dbm.coerceIn(AppDefaults.UHF_POWER_MIN, AppDefaults.UHF_POWER_MAX)
            Result.success(Unit)
        }

    override suspend fun getPower(): Result<Int> =
        synchronized(lock) {
            Result.success(powerDbm)
        }

    override suspend fun setRegion(region: UhfRegion): Result<Unit> =
        synchronized(lock) {
            this.region = region
            Result.success(Unit)
        }

    override suspend fun getRegion(): Result<UhfRegion> =
        synchronized(lock) {
            Result.success(region)
        }

    private fun nextEpc(): String =
        synchronized(lock) {
            val epc = epcPool[epcIndex % epcPool.size]
            epcIndex += 1
            epc
        }
}
