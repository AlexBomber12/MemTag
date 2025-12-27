package com.alexbomber12.memtag.integrations.uhf

import android.content.Context
import android.util.Log
import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import com.alexbomber12.memtag.util.epc.EpcValidator
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.entity.UHFTAGInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ChainwayUhfReader(
    private val context: Context,
    private val settingsStore: SettingsStore,
) : UhfReader, UhfDiagnosticsSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val uhfMutex = Mutex()
    private val inventoryFlow =
        MutableSharedFlow<TagReading>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private val diagnosticsState = MutableStateFlow(UhfDiagnostics())
    override val diagnosticsFlow: StateFlow<UhfDiagnostics> = diagnosticsState.asStateFlow()
    private var reader: RFIDWithUHFUART? = null

    private var initialized = false
    private var initInProgress = false
    private var inventoryJob: Job? = null
    private var inventoryRunning = false

    override suspend fun initialize(): Result<Unit> =
        mutex.withLock {
            if (initialized) {
                return@withLock Result.success(Unit)
            }
            if (initInProgress) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            initInProgress = true
            try {
                val instance =
                    getReaderLocked()
                        .getOrElse { error ->
                            return@withLock Result.failure(
                                UhfError.VendorError(error.message ?: "UHF init error")
                                    .asException(cause = error),
                            )
                        }
                withContext(Dispatchers.IO) {
                    Log.i(LOG_TAG, "init start")
                    uhfMutex.withLock { setPowerOnBySystemIfSupported(instance) }
                    val initNoArgResult = uhfMutex.withLock { runCatching { instance.init() } }
                    if (initNoArgResult.getOrNull() == true) {
                        initialized = true
                        Log.i(LOG_TAG, "init success (path=no-arg)")
                        val applyResult = applyUhfConfigLocked("post-init")
                        logApplyResult(applyResult.getOrNull())
                        return@withContext Result.success(Unit)
                    }
                    val initContextResult = uhfMutex.withLock { runCatching { instance.init(context) } }
                    if (initContextResult.getOrNull() == true) {
                        initialized = true
                        Log.i(LOG_TAG, "init success (path=context)")
                        val applyResult = applyUhfConfigLocked("post-init")
                        logApplyResult(applyResult.getOrNull())
                        return@withContext Result.success(Unit)
                    }
                    val fallbackApplied = disableSystemPowerOnFallback()
                    val fallbackNoArgResult =
                        if (fallbackApplied) uhfMutex.withLock { runCatching { instance.init() } } else null
                    if (fallbackNoArgResult?.getOrNull() == true) {
                        initialized = true
                        Log.i(LOG_TAG, "init success (path=fallback-no-arg)")
                        val applyResult = applyUhfConfigLocked("post-init")
                        logApplyResult(applyResult.getOrNull())
                        return@withContext Result.success(Unit)
                    }
                    val fallbackContextResult =
                        if (fallbackApplied) uhfMutex.withLock { runCatching { instance.init(context) } } else null
                    if (fallbackContextResult?.getOrNull() == true) {
                        initialized = true
                        Log.i(LOG_TAG, "init success (path=fallback-context)")
                        val applyResult = applyUhfConfigLocked("post-init")
                        logApplyResult(applyResult.getOrNull())
                        return@withContext Result.success(Unit)
                    }
                    val message =
                        buildString {
                            append("UHF initialization failed: init()=")
                            append(formatInitResult(initNoArgResult))
                            append(", init(context)=")
                            append(formatInitResult(initContextResult))
                            if (fallbackApplied) {
                                append(", init(afterDisable)=")
                                append(formatInitResult(fallbackNoArgResult))
                                append(", init(context afterDisable)=")
                                append(formatInitResult(fallbackContextResult))
                            }
                        }
                    Log.e(LOG_TAG, message)
                    Result.failure(UhfError.HardwareUnavailable.asException(message = message))
                }
            } finally {
                initInProgress = false
            }
        }

    override suspend fun close(): Result<Unit> {
        stopInventory()
        return mutex.withLock {
            val instance = reader
            if (instance == null) {
                initialized = false
                inventoryRunning = false
                diagnosticsState.update { it.copy(inventoryRunning = false) }
                return@withLock Result.success(Unit)
            }
            withContext(Dispatchers.IO) {
                val freeResult = uhfMutex.withLock { runCatching { instance.free() } }
                freeResult.fold(
                    onSuccess = {
                        initialized = false
                        inventoryRunning = false
                        diagnosticsState.update { it.copy(inventoryRunning = false) }
                        reader = null
                        Result.success(Unit)
                    },
                    onFailure = { error ->
                        Result.failure(
                            UhfError.VendorError(error.message ?: "UHF close error")
                                .asException(cause = error),
                        )
                    },
                )
            }
        }
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
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        var startedInventory = false
        return try {
            val epc =
                withTimeout(timeoutMs) {
                    var resolved: String? = null
                    while (resolved.isNullOrBlank()) {
                        val reading =
                            withContext(Dispatchers.IO) {
                                uhfMutex.withLock { singleReadOnceLocked(instance) }
                            }
                        if (reading != null) {
                            recordParsedTag(reading)
                        }
                        val candidate = reading?.epc
                        if (!candidate.isNullOrBlank()) {
                            resolved = candidate
                        } else {
                            delay(SINGLE_READ_RETRY_DELAY_MS)
                        }
                    }
                    requireNotNull(resolved)
                }
            Result.success(epc)
        } catch (_: TimeoutCancellationException) {
            Result.failure(UhfError.Timeout.asException())
        } catch (error: Throwable) {
            Result.failure(
                UhfError.VendorError(error.message ?: "UHF read error")
                    .asException(cause = error),
            )
        } finally {
            if (startedInventory) {
                withContext(Dispatchers.IO) {
                    uhfMutex.withLock { runCatching { instance.stopInventory() } }
                }
            }
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
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            val normalized =
                runCatching { EpcNormalizer.normalize(epcHex) }.getOrElse { error ->
                    return@withLock Result.failure(
                        UhfError.VendorError(error.message ?: "Invalid EPC").asException(cause = error),
                    )
                }
            if (normalized.length % EPC_WORD_HEX_LENGTH != 0) {
                return@withLock Result.failure(
                    UhfError.VendorError("EPC must be a multiple of 4 hex characters.")
                        .asException(),
                )
            }
            val wordCount = normalized.length / EPC_WORD_HEX_LENGTH
            if (wordCount > EPC_MAX_WORDS) {
                return@withLock Result.failure(
                    UhfError.VendorError("EPC length exceeds maximum supported size.")
                        .asException(),
                )
            }
            try {
                withTimeout(timeoutMs) {
                    val pcWord =
                        withContext(Dispatchers.IO) {
                            uhfMutex.withLock {
                                instance.readData(DEFAULT_ACCESS_PASSWORD, EPC_MEMORY_BANK, EPC_PC_WORD, 1)
                            }
                        }
                    val normalizedPc =
                        runCatching { EpcNormalizer.normalize(pcWord.orEmpty()) }.getOrElse { error ->
                            return@withTimeout Result.failure(
                                UhfError.VendorError(error.message ?: "Failed to read PC word.")
                                    .asException(cause = error),
                            )
                        }
                    if (normalizedPc.length != EPC_WORD_HEX_LENGTH) {
                        return@withTimeout Result.failure(
                            UhfError.VendorError("Failed to read PC word.")
                                .asException(),
                        )
                    }
                    val pcValue =
                        runCatching { normalizedPc.toInt(HEX_RADIX) }.getOrElse { error ->
                            return@withTimeout Result.failure(
                                UhfError.VendorError("Invalid PC word.")
                                    .asException(cause = error),
                            )
                        }
                    val updatedPc =
                        (pcValue and EPC_PC_LENGTH_MASK) or
                            ((wordCount and EPC_MAX_WORDS) shl EPC_PC_LENGTH_SHIFT)
                    val updatedPcHex =
                        updatedPc
                            .toString(HEX_RADIX)
                            .uppercase()
                            .padStart(EPC_WORD_HEX_LENGTH, '0')
                    val payload = updatedPcHex + normalized
                    val totalWords = wordCount + 1
                    val success =
                        withContext(Dispatchers.IO) {
                            uhfMutex.withLock {
                                instance.writeData(
                                    DEFAULT_ACCESS_PASSWORD,
                                    EPC_MEMORY_BANK,
                                    EPC_PC_WORD,
                                    totalWords,
                                    payload,
                                )
                            }
                        }
                    if (success) {
                        Result.success(Unit)
                    } else {
                        Result.failure(UhfError.VendorError("Failed to write EPC.").asException())
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Result.failure(UhfError.Timeout.asException())
            } catch (error: Throwable) {
                Result.failure(
                    UhfError.VendorError(error.message ?: "UHF write error")
                        .asException(cause = error),
                )
            }
        }

    override suspend fun verifyEpc(
        expectedEpcHex: String,
        timeoutMs: Long,
    ): Result<Boolean> =
        mutex.withLock {
            val normalizedExpected =
                runCatching { EpcNormalizer.normalize(expectedEpcHex) }.getOrElse { error ->
                    return@withLock Result.failure(
                        UhfError.VendorError(error.message ?: "Invalid EPC").asException(cause = error),
                    )
                }
            val readResult = readSingleLocked(timeoutMs)
            if (readResult.isFailure) {
                return@withLock Result.failure(
                    readResult.exceptionOrNull() ?: UhfError.VendorError("Verify failed").asException(),
                )
            }
            val normalizedRead =
                runCatching { EpcNormalizer.normalize(readResult.getOrNull().orEmpty()) }.getOrElse { error ->
                    return@withLock Result.failure(
                        UhfError.VendorError(error.message ?: "Invalid EPC").asException(cause = error),
                    )
                }
            Result.success(normalizedRead == normalizedExpected)
        }

    override suspend fun startInventory(filterEpcHex: String?): Flow<TagReading> {
        stopInventory()
        return mutex.withLock {
            if (!initialized) {
                return@withLock flow { throw UhfError.NotInitialized.asException() }
            }
            if (inventoryJob == null) {
                val instance = reader ?: return@withLock flow { throw UhfError.HardwareUnavailable.asException() }
                Log.i(LOG_TAG, "startInventoryTag called")
                val started =
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock { runCatching { instance.startInventoryTag() } }
                    }.getOrElse { error ->
                        Log.w(LOG_TAG, "startInventoryTag result=false error=${error.message}")
                        inventoryRunning = false
                        diagnosticsState.update {
                            it.copy(startInventoryOk = false, inventoryRunning = false)
                        }
                        return@withLock flow {
                            throw UhfError.VendorError(error.message ?: "UHF start error")
                                .asException(cause = error)
                        }
                    }
                Log.i(LOG_TAG, "startInventoryTag result=$started")
                diagnosticsState.update { it.copy(startInventoryOk = started) }
                if (!started) {
                    inventoryRunning = false
                    diagnosticsState.update { it.copy(inventoryRunning = false) }
                    return@withLock flow { throw UhfError.VendorError("Failed to start inventory").asException() }
                }
                inventoryRunning = true
                diagnosticsState.update { it.copy(inventoryRunning = true) }
                val job =
                    scope.launch {
                        val self = coroutineContext[Job]
                        try {
                            while (isActive && inventoryRunning) {
                                val parsed =
                                    withContext(Dispatchers.IO) {
                                        uhfMutex.withLock { readBufferOnceLocked(instance) }
                                    }
                                diagnosticsState.update {
                                    it.copy(bufferReadsCount = it.bufferReadsCount + 1)
                                }
                                if (parsed != null) {
                                    recordParsedTag(parsed)
                                    if (!parsed.epc.isNullOrBlank()) {
                                        inventoryFlow.emit(
                                            TagReading(
                                                epcHex = parsed.epc,
                                                rssi = parsed.rssi,
                                                timestampMs = System.currentTimeMillis(),
                                            ),
                                        )
                                    }
                                }
                                delay(INVENTORY_POLL_DELAY_MS)
                            }
                        } finally {
                            mutex.withLock {
                                if (inventoryJob == self) {
                                    inventoryJob = null
                                    inventoryRunning = false
                                    diagnosticsState.update { it.copy(inventoryRunning = false) }
                                }
                            }
                        }
                    }
                inventoryJob = job
            }
            val baseFlow = inventoryFlow.asSharedFlow()
            // Apply EPC filtering in-app; add SDK hardware filtering here if supported.
            if (filterEpcHex.isNullOrBlank()) {
                baseFlow
            } else {
                baseFlow.filter { it.epcHex.equals(filterEpcHex, ignoreCase = true) }
            }
        }
    }

    override suspend fun stopInventory(): Result<Unit> {
        val instance: RFIDWithUHFUART?
        val wasRunning: Boolean
        val jobToCancel: Job?
        mutex.withLock {
            jobToCancel = inventoryJob
            inventoryJob = null
            jobToCancel?.cancel()
            wasRunning = inventoryRunning || jobToCancel != null
            inventoryRunning = false
            diagnosticsState.update { it.copy(inventoryRunning = false) }
            instance = reader
        }
        if (!wasRunning) {
            return Result.success(Unit)
        }
        if (instance == null) {
            return Result.success(Unit)
        }
        Log.i(LOG_TAG, "stopInventory called")
        var stopOk = false
        var lastError: Throwable? = null
        for (attempt in 0 until STOP_RETRY_ATTEMPTS) {
            val stopResult =
                withContext(Dispatchers.IO) {
                    uhfMutex.withLock { runCatching { instance.stopInventory() } }
                }
            val ok = stopResult.getOrNull() == true
            Log.i(LOG_TAG, "stopInventory attempt=${attempt + 1} ok=$ok")
            if (ok) {
                stopOk = true
                break
            }
            lastError = stopResult.exceptionOrNull()
            if (attempt < STOP_RETRY_ATTEMPTS - 1) {
                delay(STOP_RETRY_DELAY_MS)
            }
        }
        diagnosticsState.update { it.copy(stopInventoryOk = stopOk) }
        if (stopOk) {
            return Result.success(Unit)
        }
        val errCode =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { getErrCodeIfSupported(instance) }
            }
        if (errCode != null) {
            Log.w(LOG_TAG, "stopInventory failed errCode=$errCode")
        }
        recoverAfterStopFailure(instance)
        val suffix = if (errCode != null) " (errCode=$errCode)" else ""
        return Result.failure(
            UhfError.VendorError("Failed to stop inventory$suffix").asException(cause = lastError),
        )
    }

    override suspend fun setPower(dbm: Int): Result<Unit> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { setPowerOnBySystemIfSupported(instance) }
                val result: Any =
                    uhfMutex.withLock { runCatching { instance.setPower(dbm) } }
                        .getOrElse { error ->
                            return@withContext Result.failure(
                                UhfError.VendorError(error.message ?: "UHF power error")
                                    .asException(cause = error),
                            )
                        }
                Log.i(LOG_TAG, "setPower($dbm) -> $result")
                val failureMessage =
                    when (result) {
                        is Boolean -> if (result) null else "setPower failed: false"
                        is Int -> if (result >= 0) null else "setPower failed: $result"
                        else -> null
                    }
                val currentPower = uhfMutex.withLock { runCatching { instance.getPower() }.getOrNull() }
                Log.i(LOG_TAG, "getPower -> $currentPower")
                if (failureMessage == null ||
                    (currentPower != null && (currentPower == dbm || currentPower == dbm * POWER_SCALE_FACTOR))
                ) {
                    Result.success(Unit)
                } else {
                    val errCode = uhfMutex.withLock { getErrCodeIfSupported(instance) }
                    val suffix = if (errCode != null) " (errCode=$errCode)" else ""
                    Result.failure(UhfError.VendorError("$failureMessage$suffix").asException())
                }
            }
        }

    override suspend fun getPower(): Result<Int> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { runCatching { instance.getPower() } }
                    .fold(
                        onSuccess = {
                            Log.i(LOG_TAG, "getPower -> $it")
                            Result.success(it)
                        },
                        onFailure = { error ->
                            Result.failure(
                                UhfError.VendorError(error.message ?: "UHF get power error")
                                    .asException(cause = error),
                            )
                        },
                    )
            }
        }

    override suspend fun getFrequencyMode(): Result<Int> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .fold(
                        onSuccess = {
                            Log.i(LOG_TAG, "getFrequencyMode -> $it")
                            Result.success(it)
                        },
                        onFailure = { error ->
                            Result.failure(
                                UhfError.VendorError(error.message ?: "UHF get frequency mode error")
                                    .asException(cause = error),
                            )
                        },
                    )
            }
        }

    override suspend fun setRegion(region: UhfRegion): Result<Unit> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            val mode =
                region.toFrequencyMode()
                    ?: return@withLock Result.failure(UhfError.VendorError("Region not supported by SDK").asException())
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { setPowerOnBySystemIfSupported(instance) }
                val result: Any =
                    uhfMutex.withLock { runCatching { instance.setFrequencyMode(mode) } }
                        .getOrElse { error ->
                            return@withContext Result.failure(
                                UhfError.VendorError(error.message ?: "UHF region error")
                                    .asException(cause = error),
                            )
                        }
                Log.i(LOG_TAG, "setFrequencyMode($mode) -> $result")
                val failureMessage =
                    when (result) {
                        is Boolean -> if (result) null else "setRegion failed: false"
                        is Int -> if (result >= 0) null else "setRegion failed: $result"
                        else -> null
                    }
                val currentMode = uhfMutex.withLock { runCatching { instance.getFrequencyMode() }.getOrNull() }
                Log.i(LOG_TAG, "getFrequencyMode -> $currentMode")
                if (failureMessage == null || currentMode == mode) {
                    Result.success(Unit)
                } else {
                    val errCode = uhfMutex.withLock { getErrCodeIfSupported(instance) }
                    val suffix = if (errCode != null) " (errCode=$errCode)" else ""
                    Result.failure(UhfError.VendorError("$failureMessage$suffix").asException())
                }
            }
        }

    override suspend fun getRegion(): Result<UhfRegion> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .fold(
                        onSuccess = {
                            Log.i(LOG_TAG, "getFrequencyMode -> $it")
                            Result.success(UhfRegion.fromFrequencyMode(it))
                        },
                        onFailure = { error ->
                            Result.failure(
                                UhfError.VendorError(error.message ?: "UHF get region error")
                                    .asException(cause = error),
                            )
                        },
                    )
            }
        }

    override suspend fun applyUhfConfig(reason: String): Result<UhfApplyResult> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock configBusyResult(op = "apply", reason = reason)
            }
            applyUhfConfigLocked(reason)
        }

    private suspend fun applyUhfConfigLocked(reason: String): Result<UhfApplyResult> {
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return withContext(Dispatchers.IO) {
            uhfMutex.withLock { setPowerOnBySystemIfSupported(instance) }
            val desired = resolveDesiredConfig(instance)
            val beforeMode =
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get frequency mode error")
                                .asException(cause = error),
                        )
                    }
            val beforePower =
                uhfMutex.withLock { runCatching { instance.getPower() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get power error")
                                .asException(cause = error),
                        )
                    }
            Log.i(LOG_TAG, "getFrequencyMode -> $beforeMode")
            Log.i(LOG_TAG, "getPower -> $beforePower")
            val setModeOk =
                uhfMutex.withLock { runCatching { instance.setFrequencyMode(desired.frequencyMode) } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF set frequency mode error")
                                .asException(cause = error),
                        )
                    }
            val setPowerOk =
                uhfMutex.withLock { runCatching { instance.setPower(desired.power) } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF set power error")
                                .asException(cause = error),
                        )
                    }
            Log.i(LOG_TAG, "setFrequencyMode(${desired.frequencyMode}) -> $setModeOk")
            Log.i(LOG_TAG, "setPower(${desired.power}) -> $setPowerOk")
            val afterMode =
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get frequency mode error")
                                .asException(cause = error),
                        )
                    }
            val afterPower =
                uhfMutex.withLock { runCatching { instance.getPower() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get power error")
                                .asException(cause = error),
                        )
                    }
            Log.i(LOG_TAG, "getFrequencyMode -> $afterMode")
            Log.i(LOG_TAG, "getPower -> $afterPower")
            val modeApplied = afterMode == desired.frequencyMode
            val powerApplied = afterPower == desired.power
            val result =
                UhfApplyResult(
                    reason = reason,
                    beforeMode = beforeMode,
                    beforePower = beforePower,
                    desiredMode = desired.frequencyMode,
                    desiredPower = desired.power,
                    setModeOk = setModeOk,
                    setPowerOk = setPowerOk,
                    afterMode = afterMode,
                    afterPower = afterPower,
                    modeApplied = modeApplied,
                    powerApplied = powerApplied,
                )
            Log.i(
                LOG_TAG,
                "applyUhfConfig(" +
                    "reason=$reason " +
                    "beforeMode=$beforeMode " +
                    "beforePower=$beforePower " +
                    "desiredMode=${desired.frequencyMode} " +
                    "desiredPower=${desired.power} " +
                    "setModeOk=$setModeOk " +
                    "setPowerOk=$setPowerOk " +
                    "afterMode=$afterMode " +
                    "afterPower=$afterPower " +
                    "modeApplied=$modeApplied " +
                    "powerApplied=$powerApplied" +
                    ")",
            )
            Result.success(result)
        }
    }

    override suspend fun applyUhfConfigIfNeeded(reason: String): Result<UhfApplyResult?> =
        mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryRunning) {
                return@withLock configBusyResult(op = "apply", reason = reason)
            }
            applyUhfConfigIfNeededLocked(reason)
        }

    private suspend fun applyUhfConfigIfNeededLocked(reason: String): Result<UhfApplyResult?> {
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return withContext(Dispatchers.IO) {
            val desired = resolveDesiredConfig(instance)
            val currentMode =
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get frequency mode error")
                                .asException(cause = error),
                        )
                    }
            val currentPower =
                uhfMutex.withLock { runCatching { instance.getPower() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get power error")
                                .asException(cause = error),
                        )
                    }
            if (currentMode == desired.frequencyMode && currentPower == desired.power) {
                Log.i(
                    LOG_TAG,
                    "applyUhfConfigIfNeeded(reason=$reason) config already OK " +
                        "(mode=$currentMode power=$currentPower)",
                )
                Result.success(null)
            } else {
                Log.i(
                    LOG_TAG,
                    "applyUhfConfigIfNeeded(reason=$reason) mismatch " +
                        "(mode=$currentMode power=$currentPower desiredMode=${desired.frequencyMode} " +
                        "desiredPower=${desired.power})",
                )
                applyUhfConfigLocked(reason)
            }
        }
    }

    override suspend fun runMatrixProbe(): List<MatrixProbeResult> =
        mutex.withLock {
            if (!initialized) {
                throw UhfError.NotInitialized.asException()
            }
            val instance = reader ?: throw UhfError.HardwareUnavailable.asException()
            diagnosticsState.update { it.copy(matrixProbeRunning = true, matrixProbeCurrent = null) }
            try {
                runMatrixProbeLocked(instance)
            } finally {
                inventoryRunning = false
                diagnosticsState.update {
                    it.copy(
                        inventoryRunning = false,
                        matrixProbeRunning = false,
                        matrixProbeCurrent = null,
                    )
                }
            }
        }

    private suspend fun runMatrixProbeLocked(instance: RFIDWithUHFUART): List<MatrixProbeResult> {
        val results = mutableListOf<MatrixProbeResult>()
        results +=
            runMatrixProbeStepLocked(
                instance = instance,
                name = "A: UID inventory",
                note = null,
                startAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock {
                            startInventoryLocked(instance, "startInventory", byteArrayOf(0, 0))
                        }
                    }
                },
                readAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock { readUidFromBufferLocked(instance) }
                    }
                },
            )
        results +=
            runMatrixProbeStepLocked(
                instance = instance,
                name = "B: TAG inventory (cnt=0)",
                note = null,
                startAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock {
                            startInventoryLocked(instance, "startInventoryTag", byteArrayOf(0, 0, 0))
                        }
                    }
                },
                readAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock { readTagFromBufferDirectLocked(instance) }
                    }
                },
            )
        results +=
            runMatrixProbeStepLocked(
                instance = instance,
                name = "C: TAG inventory (cnt=6)",
                note = null,
                startAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock {
                            startInventoryLocked(instance, "startInventoryTag", byteArrayOf(0, 0, 6))
                        }
                    }
                },
                readAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock { readTagFromBufferDirectLocked(instance) }
                    }
                },
            )
        val protocolNote = applyProtocolRflinkLocked(instance)
        results +=
            runMatrixProbeStepLocked(
                instance = instance,
                name = "D: TAG inventory (cnt=0 + protocol/rflink)",
                note = protocolNote,
                startAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock {
                            startInventoryLocked(instance, "startInventoryTag", byteArrayOf(0, 0, 0))
                        }
                    }
                },
                readAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock { readTagFromBufferDirectLocked(instance) }
                    }
                },
            )
        return results
    }

    private suspend fun runMatrixProbeStepLocked(
        instance: RFIDWithUHFUART,
        name: String,
        note: String?,
        startAction: suspend () -> Boolean,
        readAction: suspend () -> ProbeRead?,
    ): MatrixProbeResult {
        diagnosticsState.update { it.copy(matrixProbeCurrent = name) }
        stopInventoryBestEffortLocked(instance)
        var startOk = false
        var stopOk = false
        var nonNullReads = 0
        var firstRaw0: String? = null
        var firstRaw1: String? = null
        var firstRssi: String? = null
        var capturedFirst = false
        try {
            startOk = startAction()
            if (startOk) {
                inventoryRunning = true
                diagnosticsState.update { it.copy(inventoryRunning = true) }
                repeat(MATRIX_READS) { index ->
                    val read = readAction()
                    if (read != null) {
                        nonNullReads += 1
                        if (!capturedFirst) {
                            firstRaw0 = read.raw0
                            firstRaw1 = read.raw1
                            firstRssi = read.rssi
                            capturedFirst = true
                        }
                    }
                    if (index < MATRIX_READS - 1) {
                        delay(MATRIX_READ_DELAY_MS)
                    }
                }
            }
        } finally {
            inventoryRunning = false
            diagnosticsState.update { it.copy(inventoryRunning = false) }
            stopOk = stopInventoryBestEffortLocked(instance)
        }
        val result =
            MatrixProbeResult(
                name = name,
                startOk = startOk,
                stopOk = stopOk,
                reads = MATRIX_READS,
                nonNullReads = if (startOk) nonNullReads else 0,
                firstRaw0 = if (startOk) firstRaw0 else null,
                firstRaw1 = if (startOk) firstRaw1 else null,
                firstRssi = if (startOk) firstRssi else null,
                note = note,
            )
        logMatrixProbeResult(result)
        return result
    }

    private suspend fun applyProtocolRflinkLocked(instance: RFIDWithUHFUART): String? {
        val stopOk = stopInventoryBestEffortLocked(instance)
        if (!stopOk) {
            Log.w(MATRIX_LOG_TAG, "matrixProbe protocol/rflink skipped: stopInventory failed")
            return "protocol=? rflink=?"
        }
        val protocolSetOk =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock {
                    setProtocolLocked(instance, MATRIX_PROTOCOL_ISO_18000_6C.toByte())
                }
            }
        val rflinkSetOk =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock {
                    setRFLinkLocked(instance, MATRIX_RFLINK_DSB_ASK.toByte())
                }
            }
        val protocol =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { getProtocolLocked(instance) }
            }
        val rflink =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { getRFLinkLocked(instance) }
            }
        Log.i(
            MATRIX_LOG_TAG,
            "matrixProbe protocolSet=$protocolSetOk rflinkSet=$rflinkSetOk " +
                "protocol=${protocol ?: "?"} rflink=${rflink ?: "?"}",
        )
        val protocolNote = protocol?.toString() ?: "?"
        val rflinkNote = rflink?.toString() ?: "?"
        return "protocol=$protocolNote rflink=$rflinkNote"
    }

    private suspend fun stopInventoryBestEffortLocked(instance: RFIDWithUHFUART?): Boolean {
        val wasRunning = inventoryRunning || inventoryJob != null
        val jobToCancel = inventoryJob
        inventoryJob = null
        jobToCancel?.cancel()
        inventoryRunning = false
        diagnosticsState.update { it.copy(inventoryRunning = false) }
        if (instance == null) {
            return !wasRunning
        }
        var stopOk = false
        var lastError: Throwable? = null
        for (attempt in 0 until STOP_RETRY_ATTEMPTS) {
            val stopResult =
                withContext(Dispatchers.IO) {
                    uhfMutex.withLock { runCatching { instance.stopInventory() } }
                }
            val ok = stopResult.getOrNull() == true
            Log.i(LOG_TAG, "stopInventory(bestEffort) attempt=${attempt + 1} ok=$ok")
            if (ok) {
                stopOk = true
                break
            }
            lastError = stopResult.exceptionOrNull()
            if (attempt < STOP_RETRY_ATTEMPTS - 1) {
                delay(STOP_RETRY_DELAY_MS)
            }
        }
        if (!stopOk && lastError != null) {
            Log.w(LOG_TAG, "stopInventory(bestEffort) failed: ${lastError.message}")
        }
        return stopOk || !wasRunning
    }

    private data class ProbeRead(
        val raw0: String?,
        val raw1: String?,
        val rssi: String?,
    )

    private fun startInventoryLocked(
        instance: RFIDWithUHFUART,
        methodName: String,
        params: ByteArray,
    ): Boolean {
        val method =
            instance.javaClass.methods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == params.size
            }
        if (method == null) {
            Log.w(LOG_TAG, "matrixProbe $methodName not supported (params=${params.size})")
            return false
        }
        val coercedArgs =
            params.mapIndexed { index, value ->
                coerceByteArg(value, method.parameterTypes[index])
            }.toTypedArray()
        val result = runCatching { method.invoke(instance, *coercedArgs) }.getOrNull()
        return resultToBoolean(result)
    }

    private fun setProtocolLocked(
        instance: RFIDWithUHFUART,
        protocol: Byte,
    ): Boolean {
        val method =
            instance.javaClass.methods.firstOrNull {
                it.name == "setProtocol" && it.parameterTypes.size == 1
            }
        if (method == null) {
            Log.w(LOG_TAG, "matrixProbe setProtocol not supported")
            return false
        }
        val arg = coerceByteArg(protocol, method.parameterTypes[0])
        val result = runCatching { method.invoke(instance, arg) }.getOrNull()
        return resultToBoolean(result)
    }

    private fun getProtocolLocked(instance: RFIDWithUHFUART): Int? {
        val method =
            instance.javaClass.methods.firstOrNull {
                it.name == "getProtocol" && it.parameterTypes.isEmpty()
            } ?: return null
        val result = runCatching { method.invoke(instance) }.getOrNull()
        return resultToInt(result)
    }

    private fun setRFLinkLocked(
        instance: RFIDWithUHFUART,
        rflink: Byte,
    ): Boolean {
        val method =
            instance.javaClass.methods.firstOrNull {
                it.name == "setRFLink" && it.parameterTypes.size == 1
            }
        if (method == null) {
            Log.w(LOG_TAG, "matrixProbe setRFLink not supported")
            return false
        }
        val arg = coerceByteArg(rflink, method.parameterTypes[0])
        val result = runCatching { method.invoke(instance, arg) }.getOrNull()
        return resultToBoolean(result)
    }

    private fun getRFLinkLocked(instance: RFIDWithUHFUART): Int? {
        val method =
            instance.javaClass.methods.firstOrNull {
                it.name == "getRFLink" && it.parameterTypes.isEmpty()
            } ?: return null
        val result = runCatching { method.invoke(instance) }.getOrNull()
        return resultToInt(result)
    }

    private fun normalizeProbeField(value: String?): String? {
        return value?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun coerceByteArg(
        value: Byte,
        target: Class<*>,
    ): Any {
        return when (target) {
            java.lang.Byte.TYPE,
            java.lang.Byte::class.java,
            -> value
            java.lang.Short.TYPE,
            java.lang.Short::class.java,
            -> value.toShort()
            java.lang.Integer.TYPE,
            java.lang.Integer::class.java,
            -> value.toInt()
            else -> value
        }
    }

    private fun resultToBoolean(result: Any?): Boolean {
        return when (result) {
            is Boolean -> result
            is Number -> result.toInt() >= 0
            else -> false
        }
    }

    private fun resultToInt(result: Any?): Int? {
        return when (result) {
            is Number -> result.toInt()
            is Boolean -> if (result) 1 else 0
            is String -> result.toIntOrNull()
            else -> null
        }
    }

    private fun logMatrixProbeResult(result: MatrixProbeResult) {
        val probeCode = result.name.substringBefore(":").trim()
        val first0 = result.firstRaw0 ?: "--"
        val first1 = result.firstRaw1 ?: "--"
        val note = result.note ?: "--"
        Log.i(
            MATRIX_LOG_TAG,
            "probe=$probeCode " +
                "startOk=${result.startOk} " +
                "stopOk=${result.stopOk} " +
                "reads=${result.reads} " +
                "nonNull=${result.nonNullReads} " +
                "first0=$first0 " +
                "first1=$first1 " +
                "note=$note",
        )
    }

    private data class ParsedTag(
        val raw: Array<String>,
        val epc: String?,
        val rssi: Int?,
        val uiiIndex: Int?,
    )

    private data class UiiSelection(
        val index: Int?,
        val uii: String?,
        val epc: String?,
    )

    private fun readBufferOnceLocked(instance: RFIDWithUHFUART): ParsedTag? {
        val raw = readTagFromAnyBufferLocked(instance) ?: return null
        return parseRawLocked(instance, raw, source = "buffer")
    }

    private fun singleReadOnceLocked(instance: RFIDWithUHFUART): ParsedTag? {
        val r2000Raw = inventorySingleTagWithR2000Locked(instance)
        if (r2000Raw != null) {
            return parseRawLocked(instance, r2000Raw, source = "single-r2000")
        }
        val legacy = runCatching { instance.inventorySingleTag() }.getOrNull() ?: return null
        return parseRawLocked(instance, tagInfoToRaw(legacy), source = "single")
    }

    private fun readTagFromAnyBufferLocked(instance: RFIDWithUHFUART): Array<String>? {
        val r2000Raw = readTagFromR2000BufferLocked(instance)
        if (r2000Raw != null) {
            return r2000Raw
        }
        val legacy = runCatching { instance.readTagFromBuffer() }.getOrNull() ?: return null
        return when (legacy) {
            is UHFTAGInfo -> tagInfoToRaw(legacy)
            is Array<*> -> castStringArray(legacy)
            else -> null
        }
    }

    private fun readTagFromBufferDirectLocked(instance: RFIDWithUHFUART): ProbeRead? {
        val legacy = runCatching { instance.readTagFromBuffer() }.getOrNull() ?: return null
        return when (legacy) {
            is UHFTAGInfo ->
                ProbeRead(
                    raw0 = normalizeProbeField(legacy.epc),
                    raw1 = normalizeProbeField(legacy.tid),
                    rssi = normalizeProbeField(legacy.rssi),
                )
            is Array<*> -> {
                val raw0 = normalizeProbeField(legacy.getOrNull(0) as? String)
                val raw1 = normalizeProbeField(legacy.getOrNull(1) as? String)
                val raw2 = normalizeProbeField(legacy.getOrNull(2) as? String)
                ProbeRead(raw0 = raw0, raw1 = raw1, rssi = raw2)
            }
            else -> null
        }
    }

    private fun readUidFromBufferLocked(instance: RFIDWithUHFUART): ProbeRead? {
        val result =
            runCatching {
                val method = instance.javaClass.getMethod("readUidFromBuffer")
                method.invoke(instance)
            }.getOrNull() ?: return null
        return when (result) {
            is String ->
                ProbeRead(
                    raw0 = normalizeProbeField(result),
                    raw1 = null,
                    rssi = null,
                )
            is Array<*> -> {
                val raw0 = normalizeProbeField(result.getOrNull(0) as? String)
                val rssi = normalizeProbeField(result.getOrNull(1) as? String)
                ProbeRead(
                    raw0 = raw0,
                    raw1 = null,
                    rssi = rssi,
                )
            }
            is UHFTAGInfo ->
                ProbeRead(
                    raw0 = normalizeProbeField(result.epc),
                    raw1 = null,
                    rssi = normalizeProbeField(result.rssi),
                )
            else -> null
        }
    }

    private fun readTagFromR2000BufferLocked(instance: RFIDWithUHFUART): Array<String>? {
        val result =
            runCatching {
                val method = instance.javaClass.getMethod("readTagFromR2000Buffer")
                method.invoke(instance)
            }.getOrNull()
        return castStringArray(result)
    }

    private fun inventorySingleTagWithR2000Locked(instance: RFIDWithUHFUART): Array<String>? {
        val result =
            runCatching {
                val method = instance.javaClass.getMethod("inventorySingleTagWithR2000")
                method.invoke(instance)
            }.getOrNull()
        return castStringArray(result)
    }

    private fun castStringArray(value: Any?): Array<String>? {
        val array = value as? Array<*> ?: return null
        val strings = array.map { it as? String ?: "" }.toTypedArray()
        return strings.takeIf { it.isNotEmpty() }
    }

    private fun parseRawLocked(
        instance: RFIDWithUHFUART,
        raw: Array<String>,
        source: String,
    ): ParsedTag {
        val raw0 = raw.getOrNull(0)
        val raw1 = raw.getOrNull(1)
        val raw2 = raw.getOrNull(2)
        Log.i(
            LOG_TAG,
            "tagRead source=$source " +
                "raw0=$raw0 len0=${raw0?.length ?: 0} " +
                "raw1=$raw1 len1=${raw1?.length ?: 0} " +
                "raw2=$raw2 len2=${raw2?.length ?: 0}",
        )
        val selection = selectUiiCandidateLocked(instance, raw)
        Log.i(
            LOG_TAG,
            "tagRead source=$source uiiIndex=${selection.index} epc=${selection.epc ?: "(null)"}",
        )
        val rssi = parseRssi(raw)
        return ParsedTag(
            raw = raw,
            epc = selection.epc,
            rssi = rssi,
            uiiIndex = selection.index,
        )
    }

    private fun selectUiiCandidateLocked(
        instance: RFIDWithUHFUART,
        raw: Array<String>,
    ): UiiSelection {
        val maxIndex = minOf(raw.size, MAX_RAW_FIELDS)
        for (index in 0 until maxIndex) {
            val candidate = raw[index].trim()
            if (candidate.isBlank()) {
                continue
            }
            val converted = convertUiiToEpcLocked(instance, candidate)
            if (!converted.isNullOrBlank() && EpcValidator.isValidEpcHex(converted)) {
                return UiiSelection(index = index, uii = candidate, epc = converted)
            }
        }
        for (index in 0 until maxIndex) {
            val candidate = raw[index].trim()
            if (candidate.isBlank()) {
                continue
            }
            if (EpcValidator.isValidEpcHex(candidate)) {
                return UiiSelection(index = index, uii = candidate, epc = candidate)
            }
        }
        val fallbackIndex = (0 until maxIndex).firstOrNull { raw[it].trim().isNotBlank() }
        val fallback = fallbackIndex?.let { raw[it].trim() }
        return UiiSelection(
            index = fallbackIndex,
            uii = fallback,
            epc = fallback?.takeIf { it.isNotBlank() },
        )
    }

    private fun convertUiiToEpcLocked(
        instance: RFIDWithUHFUART,
        uii: String,
    ): String? {
        val result =
            runCatching {
                val method = instance.javaClass.getMethod("convertUiiToEPC", String::class.java)
                method.invoke(instance, uii) as? String
            }.getOrNull()
        return result?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseRssi(raw: Array<String>): Int? {
        val candidates = listOf(raw.getOrNull(2), raw.getOrNull(1), raw.getOrNull(0))
        return candidates.firstNotNullOfOrNull { it?.trim()?.toIntOrNull() }
    }

    private fun recordParsedTag(parsed: ParsedTag) {
        diagnosticsState.update { current ->
            val tagFound = !parsed.epc.isNullOrBlank()
            current.copy(
                lastRaw0 = parsed.raw.getOrNull(0),
                lastRaw1 = parsed.raw.getOrNull(1),
                lastRssi = parsed.rssi ?: current.lastRssi,
                lastReadEpc = if (tagFound) parsed.epc else current.lastReadEpc,
                tagsSeenCount = if (tagFound) current.tagsSeenCount + 1 else current.tagsSeenCount,
            )
        }
    }

    private fun tagInfoToRaw(tag: UHFTAGInfo): Array<String> = arrayOf(tag.epc.orEmpty(), tag.tid.orEmpty(), tag.rssi.orEmpty())

    private suspend fun recoverAfterStopFailure(instance: RFIDWithUHFUART) {
        val initOk =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock {
                    val freeOk = runCatching { instance.free() }.getOrNull()
                    Log.w(LOG_TAG, "stopInventory recovery free ok=$freeOk")
                    val initR2000Ok = runCatching { instance.init_R2000() }.getOrNull()
                    Log.w(LOG_TAG, "stopInventory recovery init_R2000 ok=$initR2000Ok")
                    if (initR2000Ok == true) {
                        true
                    } else {
                        val initOk = runCatching { instance.init() }.getOrNull()
                        Log.w(LOG_TAG, "stopInventory recovery init ok=$initOk")
                        initOk == true
                    }
                }
            }
        mutex.withLock {
            initialized = initOk
        }
        if (initOk) {
            val shouldApply = mutex.withLock { !inventoryRunning }
            if (shouldApply) {
                val applyResult = applyUhfConfigLocked("stop-recover")
                if (applyResult.isFailure) {
                    Log.w(
                        LOG_TAG,
                        "stopInventory recovery apply failed: ${applyResult.exceptionOrNull()?.message}",
                    )
                }
            }
        }
    }

    private fun <T> configBusyResult(
        op: String,
        reason: String,
    ): Result<T> {
        Log.i(LOG_TAG, "configOp skipped (busy): inventoryRunning=true op=$op reason=$reason")
        return Result.failure(UhfError.OperationInProgress.asException())
    }

    private suspend fun resolveDesiredConfig(instance: RFIDWithUHFUART): UhfConfig {
        val settings = settingsStore.settingsFlow.first()
        val storedMode = settings.uhfFrequencyMode
        if (storedMode != null) {
            return UhfConfig(storedMode, settings.uhfPower)
        }
        val currentMode = uhfMutex.withLock { runCatching { instance.getFrequencyMode() }.getOrNull() }
        val currentPower = uhfMutex.withLock { runCatching { instance.getPower() }.getOrNull() }
        if (currentMode != null && currentPower != null) {
            val mappedRegion = UhfRegion.fromFrequencyMode(currentMode)
            settingsStore.update {
                it.copy(
                    uhfFrequencyMode = currentMode,
                    uhfPower = currentPower,
                    uhfRegion = mappedRegion.settingsValue,
                )
            }
            Log.i(LOG_TAG, "desired config initialized from device (mode=$currentMode power=$currentPower)")
            return UhfConfig(currentMode, currentPower)
        }
        val regionMode = UhfRegion.fromSettings(settings.uhfRegion).toFrequencyMode()
        val fallbackMode = regionMode ?: DEFAULT_FREQUENCY_MODE
        return UhfConfig(fallbackMode, settings.uhfPower)
    }

    private suspend fun getReaderLocked(): Result<RFIDWithUHFUART> {
        reader?.let { return Result.success(it) }
        return uhfMutex.withLock { runCatching { RFIDWithUHFUART.getInstance() } }
            .fold(
                onSuccess = { instance ->
                    reader = instance
                    Result.success(instance)
                },
                onFailure = { error ->
                    Result.failure(error)
                },
            )
    }

    private fun formatInitResult(result: Result<Boolean>?): String {
        return when {
            result == null -> "skipped"
            result.getOrNull() == true -> "true"
            result.exceptionOrNull() != null ->
                result.exceptionOrNull()?.message?.ifBlank { null } ?: "exception"
            else -> "false"
        }
    }

    private fun logApplyResult(result: UhfApplyResult?) {
        if (result == null) {
            return
        }
        if (!result.success) {
            Log.w(
                LOG_TAG,
                "applyUhfConfig verify failed (reason=${result.reason} modeApplied=${result.modeApplied} " +
                    "powerApplied=${result.powerApplied})",
            )
        }
    }

    private fun disableSystemPowerOnFallback(): Boolean {
        // Clear the SDK system-power flag so init can use DeviceAPI.UHFInit instead.
        return runCatching {
            val iuhfField = RFIDWithUHFUART::class.java.getDeclaredField("iuhf")
            iuhfField.isAccessible = true
            val iuhfInstance = iuhfField.get(null) ?: return@runCatching false
            val flagField = iuhfInstance.javaClass.getDeclaredField("isPowerOnBySystem")
            flagField.isAccessible = true
            flagField.setBoolean(iuhfInstance, false)
            true
        }.getOrDefault(false)
    }

    private fun setPowerOnBySystemIfSupported(instance: RFIDWithUHFUART) {
        runCatching {
            val method = instance.javaClass.getMethod("setPowerOnBySystem", Context::class.java)
            method.invoke(instance, context)
        }
    }

    private fun getErrCodeIfSupported(instance: RFIDWithUHFUART): Int? {
        return runCatching {
            val method = instance.javaClass.getMethod("getErrCode")
            val value = method.invoke(instance)
            (value as? Number)?.toInt()
        }.getOrNull()
    }

    private companion object {
        const val EPC_MEMORY_BANK = 1
        const val EPC_PC_WORD = 1
        const val EPC_WORD_HEX_LENGTH = 4
        const val EPC_PC_LENGTH_SHIFT = 11
        const val EPC_PC_LENGTH_MASK = 0x07FF
        const val EPC_MAX_WORDS = 31
        const val HEX_RADIX = 16
        const val DEFAULT_ACCESS_PASSWORD = "00000000"
        const val POWER_SCALE_FACTOR = 100
        const val MAX_RAW_FIELDS = 3
        const val SINGLE_READ_RETRY_DELAY_MS = 75L
        const val INVENTORY_POLL_DELAY_MS = 50L
        const val STOP_RETRY_ATTEMPTS = 3
        const val STOP_RETRY_DELAY_MS = 100L
        const val MATRIX_READS = 10
        const val MATRIX_READ_DELAY_MS = 100L
        const val MATRIX_PROTOCOL_ISO_18000_6C = 0x00
        const val MATRIX_RFLINK_DSB_ASK = 0
        const val MATRIX_LOG_TAG = "memtag-uhf-matrix"
        val DEFAULT_FREQUENCY_MODE: Int =
            UhfRegion.fromSettings(AppDefaults.UHF_REGION).toFrequencyMode() ?: 2
    }
}
