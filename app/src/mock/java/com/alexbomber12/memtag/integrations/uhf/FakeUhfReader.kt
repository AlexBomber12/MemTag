package com.alexbomber12.memtag.integrations.uhf

import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.util.epc.EpcNormalizer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.random.Random

class FakeUhfReader(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : UhfReader {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
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
    private var inventoryRunning = false
    private var powerDbm = AppDefaults.UHF_POWER
    private var region = UhfRegion.fromSettings(AppDefaults.UHF_REGION)
    private var frequencyMode = region.toFrequencyMode()
    private var protocol = UHF_PROTOCOL_ISO_18000_6C
    private var rfLink = UHF_RFLINK_DSB_ASK
    private var epcIndex = 0
    private var lastWrittenEpc: String? = null

    var nextReadResult: Result<String>? = null
    var writeResultOverride: Result<Unit>? = null
    var verifyResultOverride: Result<Boolean>? = null
    var writeCalls: Int = 0
        private set
    var verifyCalls: Int = 0
        private set

    override suspend fun initialize(): Result<Unit> =
        mutex.withLock {
            if (initialized) {
                return@withLock Result.success(Unit)
            }
            initialized = true
            Result.success(Unit)
        }

    override suspend fun close(): Result<Unit> =
        mutex.withLock {
            stopInventoryLocked()
            initialized = false
            Result.success(Unit)
        }

    override suspend fun readSingle(timeoutMs: Long): Result<String> =
        mutex.withLock {
            readSingleLocked(timeoutMs)
        }

    private suspend fun readSingleLocked(timeoutMs: Long): Result<String> {
        if (!initialized) {
            return Result.failure(UhfError.NotInitialized.asException())
        }
        if (inventoryRunning) {
            return Result.failure(UhfError.OperationInProgress.asException())
        }
        nextReadResult?.let { result ->
            nextReadResult = null
            return result
        }
        return try {
            withTimeout(timeoutMs) {
                delay(150)
                Result.success(nextEpcLocked())
            }
        } catch (_: TimeoutCancellationException) {
            Result.failure(UhfError.Timeout.asException())
        }
    }

    override suspend fun writeEpc(
        epcHex: String,
        timeoutMs: Long,
    ): Result<Unit> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            writeCalls += 1
            writeResultOverride?.let { return@withLock it }
            lastWrittenEpc = epcHex
            Result.success(Unit)
        }

    override suspend fun verifyEpc(
        expectedEpcHex: String,
        timeoutMs: Long,
    ): Result<Boolean> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            verifyCalls += 1
            verifyResultOverride?.let { return@withLock it }
            val normalizedExpected =
                runCatching { EpcNormalizer.normalize(expectedEpcHex) }.getOrElse {
                    return@withLock Result.failure(UhfError.VendorError("Invalid EPC").asException(cause = it))
                }
            val candidate = lastWrittenEpc
            if (candidate != null) {
                val normalizedCandidate =
                    runCatching { EpcNormalizer.normalize(candidate) }.getOrElse {
                        return@withLock Result.failure(UhfError.VendorError("Invalid EPC").asException(cause = it))
                    }
                return@withLock Result.success(normalizedCandidate == normalizedExpected)
            }
            readSingleLocked(timeoutMs).mapCatching { read ->
                val normalizedRead = EpcNormalizer.normalize(read)
                normalizedRead == normalizedExpected
            }
        }

    override suspend fun startInventory(filterEpcHex: String?): Flow<TagReading> =
        mutex.withLock {
            if (!initialized) {
                return@withLock flow { throw UhfError.NotInitialized.asException() }
            }
            if (inventoryRunning) {
                stopInventoryLocked()
            }
            if (inventoryJob == null) {
                inventoryRunning = true
                val job =
                    scope.launch {
                        val self = coroutineContext[Job]
                        try {
                            while (isActive) {
                                val reading =
                                    mutex.withLock {
                                        TagReading(
                                            epcHex = nextEpcLocked(),
                                            rssi = random.nextInt(-70, -35),
                                            timestampMs = System.currentTimeMillis(),
                                        )
                                    }
                                inventoryFlow.emit(reading)
                                delay(150)
                            }
                        } finally {
                            mutex.withLock {
                                if (inventoryJob == self) {
                                    inventoryJob = null
                                    inventoryRunning = false
                                }
                            }
                        }
                    }
                inventoryJob = job
            }
            val baseFlow = inventoryFlow.asSharedFlow()
            if (filterEpcHex.isNullOrBlank()) {
                baseFlow
            } else {
                baseFlow.filter { it.epcHex.equals(filterEpcHex, ignoreCase = true) }
            }
        }

    override suspend fun stopInventory(): Result<Unit> =
        mutex.withLock {
            stopInventoryLocked()
        }

    private suspend fun stopInventoryLocked(): Result<Unit> {
        val jobToCancel = inventoryJob
        inventoryJob = null
        jobToCancel?.cancel()
        inventoryRunning = false
        return Result.success(Unit)
    }

    override suspend fun setPower(dbm: Int): Result<Unit> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            powerDbm = dbm.coerceIn(AppDefaults.UHF_POWER_MIN, AppDefaults.UHF_POWER_MAX)
            Result.success(Unit)
        }

    override suspend fun getPower(): Result<Int> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            Result.success(powerDbm)
        }

    override suspend fun setRegion(region: UhfRegion): Result<Unit> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            this.region = region
            this.frequencyMode = region.toFrequencyMode()
            Result.success(Unit)
        }

    override suspend fun getRegion(): Result<UhfRegion> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            Result.success(region)
        }

    override suspend fun getFrequencyMode(): Result<Int> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            Result.success(frequencyMode)
        }

    override suspend fun getProtocol(): Result<Int> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            Result.success(protocol)
        }

    override suspend fun getRfLink(): Result<Int> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            Result.success(rfLink)
        }

    override suspend fun applyUhfConfig(reason: String): Result<UhfApplyResult> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            val result =
                UhfApplyResult(
                    reason = reason,
                    beforeMode = frequencyMode,
                    beforePower = powerDbm,
                    beforeProtocol = protocol,
                    beforeRfLink = rfLink,
                    desiredMode = frequencyMode,
                    desiredPower = powerDbm,
                    desiredProtocol = protocol,
                    desiredRfLink = rfLink,
                    setModeOk = true,
                    setPowerOk = true,
                    setProtocolOk = true,
                    setRfLinkOk = true,
                    afterMode = frequencyMode,
                    afterPower = powerDbm,
                    afterProtocol = protocol,
                    afterRfLink = rfLink,
                    protocolSupport = ProtocolSupport.Supported,
                    protocolAttempt = ProtocolAttempt(ok = true),
                    modeApplied = true,
                    powerApplied = true,
                    protocolApplied = true,
                    rfLinkApplied = true,
                )
            Result.success(result)
        }

    override suspend fun applyUhfConfigIfNeeded(reason: String): Result<UhfApplyResult?> =
        mutex.withLock {
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            Result.success(null)
        }

    override suspend fun runMatrixProbe(): List<MatrixProbeResult> =
        mutex.withLock {
            listOf(
                MatrixProbeResult(
                    name = "A: TAG inventory (no params)",
                    startOk = true,
                    stopOk = true,
                    reads = 10,
                    nonNullReads = 10,
                    firstRaw0 = "E2000017221101441890ABCD",
                    firstRaw1 = null,
                    firstRssi = null,
                    note = null,
                ),
                MatrixProbeResult(
                    name = "B: TAG inventory (cnt=0)",
                    startOk = true,
                    stopOk = true,
                    reads = 10,
                    nonNullReads = 10,
                    firstRaw0 = "E2000017221101441890ABCE",
                    firstRaw1 = null,
                    firstRssi = "-48",
                    note = null,
                ),
                MatrixProbeResult(
                    name = "C: TAG inventory (cnt=6)",
                    startOk = true,
                    stopOk = true,
                    reads = 10,
                    nonNullReads = 10,
                    firstRaw0 = "E2000017221101441890ABCF",
                    firstRaw1 = "112233445566",
                    firstRssi = "-46",
                    note = null,
                ),
                MatrixProbeResult(
                    name = "D: TAG inventory (cnt=0 + protocol/rflink)",
                    startOk = true,
                    stopOk = true,
                    reads = 10,
                    nonNullReads = 10,
                    firstRaw0 = "E2000017221101441890ABD0",
                    firstRaw1 = null,
                    firstRssi = "-44",
                    note = "protocol=0 rflink=0",
                ),
            )
        }

    private fun nextEpcLocked(): String {
        val epc = epcPool[epcIndex % epcPool.size]
        epcIndex += 1
        return epc
    }
}
