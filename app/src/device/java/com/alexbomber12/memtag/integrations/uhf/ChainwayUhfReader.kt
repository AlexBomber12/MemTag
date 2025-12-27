package com.alexbomber12.memtag.integrations.uhf

import android.content.Context
import android.util.Log
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.util.epc.EpcNormalizer
import com.rscja.deviceapi.RFIDWithUHFUART
import com.rscja.deviceapi.entity.UHFTAGInfo
import com.rscja.deviceapi.interfaces.IUHF
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
    private var reader: IUHF? = null

    private var initialized = false
    private var initInProgress = false
    private var inventoryJob: Job? = null
    @Volatile
    private var inventoryRunning = false
    @Volatile
    private var scanRunning = false
    private var inventoryLogCount = 0
    private var protocolSupport: ProtocolSupport = ProtocolSupport.Unknown
    private var lastProtocolAttempt: ProtocolAttempt? = null
    private val busyLogLock = Any()
    private val busyLogTimestamps = mutableMapOf<String, Long>()

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
                    val initR2000Result = uhfMutex.withLock { initR2000IfSupported(instance) }
                    if (initR2000Result?.getOrNull() == true) {
                        initialized = true
                        Log.i(LOG_TAG, "init success (path=init_R2000)")
                        val applyResult = applyDesiredConfigBestEffortLocked("post-init")
                        logApplyResult(applyResult.getOrNull())
                        return@withContext Result.success(Unit)
                    }
                    val initContextResult = uhfMutex.withLock { runCatching { instance.init(context) } }
                    if (initContextResult.getOrNull() == true) {
                        initialized = true
                        Log.i(LOG_TAG, "init success (path=context)")
                        val applyResult = applyDesiredConfigBestEffortLocked("post-init")
                        logApplyResult(applyResult.getOrNull())
                        return@withContext Result.success(Unit)
                    }
                    val fallbackApplied = uhfMutex.withLock { disableSystemPowerOnFallback() }
                    val fallbackR2000Result =
                        if (fallbackApplied) uhfMutex.withLock { initR2000IfSupported(instance) } else null
                    if (fallbackR2000Result?.getOrNull() == true) {
                        initialized = true
                        Log.i(LOG_TAG, "init success (path=fallback-init_R2000)")
                        val applyResult = applyDesiredConfigBestEffortLocked("post-init")
                        logApplyResult(applyResult.getOrNull())
                        return@withContext Result.success(Unit)
                    }
                    val fallbackContextResult =
                        if (fallbackApplied) uhfMutex.withLock { runCatching { instance.init(context) } } else null
                    if (fallbackContextResult?.getOrNull() == true) {
                        initialized = true
                        Log.i(LOG_TAG, "init success (path=fallback-context)")
                        val applyResult = applyDesiredConfigBestEffortLocked("post-init")
                        logApplyResult(applyResult.getOrNull())
                        return@withContext Result.success(Unit)
                    }
                    val message =
                        buildString {
                            append("UHF initialization failed (UART). Module unavailable or busy: ")
                            append("init_R2000=")
                            append(formatInitResult(initR2000Result))
                            append(", init(context)=")
                            append(formatInitResult(initContextResult))
                            if (fallbackApplied) {
                                append(", init_R2000(afterDisable)=")
                                append(formatInitResult(fallbackR2000Result))
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
        if (scanRunning) {
            return Result.failure(UhfError.OperationInProgress.asException())
        }
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        Log.i(LOG_TAG, "singleScan start (timeoutMs=$timeoutMs)")
        scanRunning = true
        return try {
            val parsed =
                withTimeout(timeoutMs) {
                    var resolved: ParsedTag? = null
                    while (resolved?.epc.isNullOrBlank()) {
                        val reading =
                            withContext(Dispatchers.IO) {
                                uhfMutex.withLock { singleReadOnceLocked(instance) }
                            }
                        if (reading != null) {
                            recordParsedTag(reading)
                        }
                        if (reading?.epc.isNullOrBlank()) {
                            delay(SINGLE_READ_RETRY_DELAY_MS)
                        } else {
                            resolved = reading
                        }
                    }
                    requireNotNull(resolved)
                }
            Log.i(
                LOG_TAG,
                "singleScan end (result=${parsed.epc ?: "--"} rssi=${parsed.rssi ?: "--"} tid=${parsed.tid ?: "--"})",
            )
            Result.success(parsed.epc.orEmpty())
        } catch (_: TimeoutCancellationException) {
            Result.failure(UhfError.Timeout.asException())
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "singleScan end (result=error message=${error.message})")
            Result.failure(
                UhfError.VendorError(error.message ?: "UHF read error")
                    .asException(cause = error),
            )
        } finally {
            scanRunning = false
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
            if (isUhfBusy()) {
                return@withLock configBusyResult(op = "setPower", reason = "manual")
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
            if (inventoryJob == null && inventoryRunning) {
                return@withLock flow { throw UhfError.OperationInProgress.asException() }
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
                inventoryRunning = started
                inventoryLogCount = 0
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
        val instance: IUHF?
        val wasRunning: Boolean
        val jobToCancel: Job?
        mutex.withLock {
            jobToCancel = inventoryJob
            inventoryJob = null
            jobToCancel?.cancel()
            wasRunning = inventoryRunning || jobToCancel != null
            instance = reader
        }
        if (!wasRunning) {
            return Result.success(Unit)
        }
        if (instance == null) {
            mutex.withLock {
                inventoryRunning = false
                diagnosticsState.update { it.copy(inventoryRunning = false) }
            }
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
            mutex.withLock {
                inventoryRunning = false
                diagnosticsState.update { it.copy(inventoryRunning = false) }
            }
            return Result.success(Unit)
        }
        val errCode =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { getErrCodeIfSupported(instance) }
            }
        if (errCode != null) {
            Log.w(LOG_TAG, "stopInventory failed errCode=$errCode")
        }
        val recovered = recoverAfterStopFailure(instance)
        if (recovered) {
            mutex.withLock {
                inventoryRunning = false
                diagnosticsState.update { it.copy(inventoryRunning = false) }
            }
        }
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
            if (isUhfBusy()) {
                return@withLock configBusyResult(op = "setRegion", reason = "manual")
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { setPowerOnBySystemIfSupported(instance) }
                val result =
                    uhfMutex.withLock { runCatching { instance.setPower(dbm) } }
                        .getOrElse { error ->
                            return@withContext Result.failure(
                                UhfError.VendorError(error.message ?: "UHF power error")
                                    .asException(cause = error),
                            )
                        }
                Log.i(LOG_TAG, "setPower($dbm) -> $result")
                val failureMessage = if (result) null else "setPower failed: false"
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

    override suspend fun getPower(reason: String): Result<Int> {
        if (isUhfBusy()) {
            return configGetterBusy(op = "getPower", reason = reason)
        }
        return mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (isUhfBusy()) {
                return@withLock configGetterBusy(op = "getPower", reason = reason)
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { runCatching { instance.getPower() } }
                    .fold(
                        onSuccess = {
                            Log.i(LOG_TAG, "getPower(reason=$reason) -> $it")
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
    }

    override suspend fun getFrequencyMode(reason: String): Result<Int> {
        if (isUhfBusy()) {
            return configGetterBusy(op = "getFrequencyMode", reason = reason)
        }
        return mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (isUhfBusy()) {
                return@withLock configGetterBusy(op = "getFrequencyMode", reason = reason)
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .fold(
                        onSuccess = {
                            Log.i(LOG_TAG, "getFrequencyMode(reason=$reason) -> $it")
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
    }

    override suspend fun getProtocol(reason: String): Result<Int> {
        if (isUhfBusy()) {
            return configGetterBusy(op = "getProtocol", reason = reason)
        }
        return mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (isUhfBusy()) {
                return@withLock configGetterBusy(op = "getProtocol", reason = reason)
            }
            if (protocolSupport == ProtocolSupport.Unsupported) {
                Log.i(LOG_TAG, "getProtocol skipped (unsupported reason=$reason)")
                return@withLock Result.success(UHF_PROTOCOL_UNSUPPORTED)
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { runCatching { instance.getProtocol() } }
                    .fold(
                        onSuccess = {
                            updateProtocolSupportFromGet(it)
                            Log.i(LOG_TAG, "getProtocol(reason=$reason) -> $it")
                            Result.success(it)
                        },
                        onFailure = { error ->
                            Result.failure(
                                UhfError.VendorError(error.message ?: "UHF get protocol error")
                                    .asException(cause = error),
                            )
                        },
                    )
            }
        }
    }

    override suspend fun getRfLink(reason: String): Result<Int> {
        if (isUhfBusy()) {
            return configGetterBusy(op = "getRFLink", reason = reason)
        }
        return mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (isUhfBusy()) {
                return@withLock configGetterBusy(op = "getRFLink", reason = reason)
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { runCatching { instance.getRFLink() } }
                    .fold(
                        onSuccess = {
                            Log.i(LOG_TAG, "getRFLink(reason=$reason) -> $it")
                            Result.success(it)
                        },
                        onFailure = { error ->
                            Result.failure(
                                UhfError.VendorError(error.message ?: "UHF get rflink error")
                                    .asException(cause = error),
                            )
                        },
                    )
            }
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
            val mode = region.toFrequencyMode()
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { setPowerOnBySystemIfSupported(instance) }
                val result =
                    uhfMutex.withLock { runCatching { instance.setFrequencyMode(mode) } }
                        .getOrElse { error ->
                            return@withContext Result.failure(
                                UhfError.VendorError(error.message ?: "UHF region error")
                                    .asException(cause = error),
                            )
                        }
                Log.i(LOG_TAG, "setFrequencyMode($mode) -> $result")
                val failureMessage = if (result) null else "setRegion failed: false"
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

    override suspend fun getRegion(reason: String): Result<UhfRegion> {
        if (isUhfBusy()) {
            logBusySkip(op = "getRegion", reason = reason)
            return Result.failure(UhfError.OperationInProgress.asException())
        }
        return mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (isUhfBusy()) {
                logBusySkip(op = "getRegion", reason = reason)
                return@withLock Result.failure(UhfError.OperationInProgress.asException())
            }
            val instance = reader ?: return@withLock Result.failure(UhfError.HardwareUnavailable.asException())
            withContext(Dispatchers.IO) {
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .fold(
                        onSuccess = {
                            Log.i(LOG_TAG, "getRegion(reason=$reason) -> $it")
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
    }

    override suspend fun applyDesiredConfigBestEffort(reason: String): Result<UhfApplyResult> {
        if (isUhfBusy()) {
            return configBusyResult(op = "apply", reason = reason)
        }
        return mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (isUhfBusy()) {
                return@withLock configBusyResult(op = "apply", reason = reason)
            }
            applyDesiredConfigBestEffortLocked(reason)
        }
    }

    private suspend fun applyDesiredConfigBestEffortLocked(reason: String): Result<UhfApplyResult> {
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return withContext(Dispatchers.IO) {
            uhfMutex.withLock { setPowerOnBySystemIfSupported(instance) }
            val desired = resolveDesiredConfig()
            val setModeOk =
                uhfMutex.withLock { runCatching { instance.setFrequencyMode(desired.frequencyMode) } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF set frequency mode error")
                                .asException(cause = error),
                        )
                    }
            val setRfLinkOk =
                uhfMutex.withLock { runCatching { instance.setRFLink(desired.rfLink) } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF set rflink error")
                                .asException(cause = error),
                        )
                    }
            val setPowerOk =
                uhfMutex.withLock { runCatching { instance.setPower(desired.powerDbm) } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF set power error")
                                .asException(cause = error),
                        )
                    }
            val result =
                UhfApplyResult(
                    reason = reason,
                    beforeMode = null,
                    beforePower = null,
                    beforeProtocol = null,
                    beforeRfLink = null,
                    desiredMode = desired.frequencyMode,
                    desiredPower = desired.powerDbm,
                    desiredProtocol = desired.protocol,
                    desiredRfLink = desired.rfLink,
                    setModeOk = setModeOk,
                    setPowerOk = setPowerOk,
                    setProtocolOk = null,
                    setRfLinkOk = setRfLinkOk,
                    afterMode = null,
                    afterPower = null,
                    afterProtocol = null,
                    afterRfLink = null,
                    protocolSupport = protocolSupport,
                    protocolAttempt = null,
                    modeApplied = setModeOk == true,
                    powerApplied = setPowerOk == true,
                    protocolApplied = null,
                    rfLinkApplied = setRfLinkOk == true,
                )
            Log.i(
                LOG_TAG,
                "applyUhfConfigBestEffort(reason=$reason setModeOk=$setModeOk setRfLinkOk=$setRfLinkOk " +
                    "setPowerOk=$setPowerOk protocolSupport=$protocolSupport)",
            )
            Result.success(result)
        }
    }

    override suspend fun applyDesiredConfigWithReadback(reason: String): Result<UhfApplyResult> {
        if (isUhfBusy()) {
            return configBusyResult(op = "apply", reason = reason)
        }
        return mutex.withLock {
            if (!initialized) {
                return@withLock Result.failure(UhfError.NotInitialized.asException())
            }
            if (isUhfBusy()) {
                return@withLock configBusyResult(op = "apply", reason = reason)
            }
            applyDesiredConfigWithReadbackLocked(reason)
        }
    }

    private suspend fun applyDesiredConfigWithReadbackLocked(reason: String): Result<UhfApplyResult> {
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return withContext(Dispatchers.IO) {
            uhfMutex.withLock { setPowerOnBySystemIfSupported(instance) }
            val desired = resolveDesiredConfig()
            val beforeMode =
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get frequency mode error")
                                .asException(cause = error),
                        )
                    }
            val beforeRfLink =
                uhfMutex.withLock { runCatching { instance.getRFLink() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get rflink error")
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
            val beforeProtocol =
                if (protocolSupport != ProtocolSupport.Unsupported) {
                    val value =
                        uhfMutex.withLock { runCatching { instance.getProtocol() } }
                            .getOrElse { error ->
                                return@withContext Result.failure(
                                    UhfError.VendorError(error.message ?: "UHF get protocol error")
                                        .asException(cause = error),
                                )
                            }
                    updateProtocolSupportFromGet(value)
                    value
                } else {
                    null
                }
            val setModeOk =
                uhfMutex.withLock { runCatching { instance.setFrequencyMode(desired.frequencyMode) } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF set frequency mode error")
                                .asException(cause = error),
                        )
                    }
            val canAttemptProtocolSet =
                when (protocolSupport) {
                    ProtocolSupport.Supported -> true
                    ProtocolSupport.Unknown -> lastProtocolAttempt == null
                    ProtocolSupport.Unsupported -> false
                }
            var setProtocolOk: Boolean? = null
            var protocolAttempt: ProtocolAttempt? = null
            if (canAttemptProtocolSet) {
                val protocolResult =
                    uhfMutex.withLock { runCatching { instance.setProtocol(desired.protocol) } }
                val ok = protocolResult.getOrNull() == true
                val errCode =
                    if (ok) {
                        null
                    } else {
                        uhfMutex.withLock { getErrCodeIfSupported(instance) }
                    }
                protocolAttempt = ProtocolAttempt(ok = ok, errorCode = errCode)
                recordProtocolAttempt(protocolAttempt)
                setProtocolOk = ok
                if (ok) {
                    updateProtocolSupport(ProtocolSupport.Supported)
                } else {
                    updateProtocolSupport(ProtocolSupport.Unsupported)
                }
            }
            val setRfLinkOk =
                uhfMutex.withLock { runCatching { instance.setRFLink(desired.rfLink) } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF set rflink error")
                                .asException(cause = error),
                        )
                    }
            val setPowerOk =
                uhfMutex.withLock { runCatching { instance.setPower(desired.powerDbm) } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF set power error")
                                .asException(cause = error),
                        )
                    }
            val afterMode =
                uhfMutex.withLock { runCatching { instance.getFrequencyMode() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get frequency mode error")
                                .asException(cause = error),
                        )
                    }
            val afterRfLink =
                uhfMutex.withLock { runCatching { instance.getRFLink() } }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get rflink error")
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
            val afterProtocol =
                if (protocolSupport != ProtocolSupport.Unsupported) {
                    val value =
                        uhfMutex.withLock { runCatching { instance.getProtocol() } }
                            .getOrElse { error ->
                                return@withContext Result.failure(
                                    UhfError.VendorError(error.message ?: "UHF get protocol error")
                                        .asException(cause = error),
                                )
                            }
                    updateProtocolSupportFromGet(value)
                    value
                } else {
                    null
                }
            val modeApplied = afterMode == desired.frequencyMode
            val rfLinkApplied = afterRfLink == desired.rfLink
            val powerApplied = afterPower == desired.powerDbm
            val protocolApplied =
                if (protocolSupport == ProtocolSupport.Supported) {
                    afterProtocol == desired.protocol
                } else {
                    null
                }
            val result =
                UhfApplyResult(
                    reason = reason,
                    beforeMode = beforeMode,
                    beforePower = beforePower,
                    beforeProtocol = beforeProtocol,
                    beforeRfLink = beforeRfLink,
                    desiredMode = desired.frequencyMode,
                    desiredPower = desired.powerDbm,
                    desiredProtocol = desired.protocol,
                    desiredRfLink = desired.rfLink,
                    setModeOk = setModeOk,
                    setPowerOk = setPowerOk,
                    setProtocolOk = setProtocolOk,
                    setRfLinkOk = setRfLinkOk,
                    afterMode = afterMode,
                    afterPower = afterPower,
                    afterProtocol = afterProtocol,
                    afterRfLink = afterRfLink,
                    protocolSupport = protocolSupport,
                    protocolAttempt = protocolAttempt,
                    modeApplied = modeApplied,
                    powerApplied = powerApplied,
                    protocolApplied = protocolApplied,
                    rfLinkApplied = rfLinkApplied,
                )
            val protocolAppliedLabel = protocolApplied?.toString() ?: "N/A"
            val protocolAction =
                if (protocolAttempt == null) {
                    "skipped"
                } else {
                    "attempted"
                }
            Log.i(
                LOG_TAG,
                "applyUhfConfigWithReadback(" +
                    "reason=$reason " +
                    "beforeMode=$beforeMode " +
                    "beforeProtocol=$beforeProtocol " +
                    "beforeRfLink=$beforeRfLink " +
                    "beforePower=$beforePower " +
                    "desiredMode=${desired.frequencyMode} " +
                    "desiredProtocol=${desired.protocol} " +
                    "desiredRfLink=${desired.rfLink} " +
                    "desiredPower=${desired.powerDbm} " +
                    "protocolSupport=$protocolSupport " +
                    "setProtocol=$protocolAction " +
                    "setModeOk=$setModeOk " +
                    "setProtocolOk=$setProtocolOk " +
                    "setRfLinkOk=$setRfLinkOk " +
                    "setPowerOk=$setPowerOk " +
                    "afterMode=$afterMode " +
                    "afterProtocol=$afterProtocol " +
                    "afterRfLink=$afterRfLink " +
                    "afterPower=$afterPower " +
                    "modeApplied=$modeApplied " +
                    "protocolApplied=$protocolAppliedLabel " +
                    "rfLinkApplied=$rfLinkApplied " +
                    "powerApplied=$powerApplied" +
                    ")",
            )
            Result.success(result)
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
                diagnosticsState.update {
                    it.copy(
                        matrixProbeRunning = false,
                        matrixProbeCurrent = null,
                    )
                }
            }
        }

    private suspend fun runMatrixProbeLocked(instance: IUHF): List<MatrixProbeResult> {
        val results = mutableListOf<MatrixProbeResult>()
        results +=
            runMatrixProbeStepLocked(
                instance = instance,
                name = "A: TAG inventory (no params)",
                note = null,
                startAction = {
                    withContext(Dispatchers.IO) {
                        uhfMutex.withLock {
                            startInventoryLocked(instance, "startInventoryTag", byteArrayOf())
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
        instance: IUHF,
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

    private suspend fun applyProtocolRflinkLocked(instance: IUHF): String? {
        val stopOk = stopInventoryBestEffortLocked(instance)
        if (!stopOk) {
            Log.w(LOG_TAG, "matrixProbe protocol/rflink skipped: stopInventory failed")
            return "protocol=? rflink=?"
        }
        val rflinkSetOk =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock {
                    runCatching { instance.setRFLink(UHF_RFLINK_DSB_ASK) }.getOrNull() == true
                }
            }
        val protocolNote =
            when (protocolSupport) {
                ProtocolSupport.Unsupported -> "unsupported"
                ProtocolSupport.Supported -> "supported"
                ProtocolSupport.Unknown -> "unknown"
            }
        val rflinkNote = if (rflinkSetOk) UHF_RFLINK_DSB_ASK.toString() else "?"
        Log.i(
            LOG_TAG,
            "matrixProbe protocolSet=skipped rflinkSet=$rflinkSetOk " +
                "protocol=$protocolNote rflink=$rflinkNote protocolSupport=$protocolSupport",
        )
        return "protocol=$protocolNote rflink=$rflinkNote"
    }

    private suspend fun stopInventoryBestEffortLocked(instance: IUHF?): Boolean {
        val wasRunning = inventoryRunning || inventoryJob != null
        val jobToCancel = inventoryJob
        inventoryJob = null
        jobToCancel?.cancel()
        if (instance == null) {
            if (wasRunning) {
                inventoryRunning = false
                diagnosticsState.update { it.copy(inventoryRunning = false) }
            }
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
        if (stopOk || !wasRunning) {
            inventoryRunning = false
            diagnosticsState.update { it.copy(inventoryRunning = false) }
        }
        return stopOk || !wasRunning
    }

    private data class ProbeRead(
        val raw0: String?,
        val raw1: String?,
        val rssi: String?,
    )

    private fun startInventoryLocked(
        instance: IUHF,
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

    private fun logMatrixProbeResult(result: MatrixProbeResult) {
        val probeCode = result.name.substringBefore(":").trim()
        val first0 = result.firstRaw0 ?: "--"
        val first1 = result.firstRaw1 ?: "--"
        val note = result.note ?: "--"
        Log.i(
            LOG_TAG,
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
        val epc: String?,
        val tid: String?,
        val rssi: Int?,
        val rssiRaw: String?,
    )

    private fun readBufferOnceLocked(instance: IUHF): ParsedTag? {
        val info = runCatching { instance.readTagFromBuffer() }.getOrNull() ?: return null
        return parseTagInfo(info, source = "buffer")
    }

    private fun singleReadOnceLocked(instance: IUHF): ParsedTag? {
        val info = runCatching { instance.inventorySingleTag() }.getOrNull() ?: return null
        return parseTagInfo(info, source = "single")
    }

    private fun readTagFromBufferDirectLocked(instance: IUHF): ProbeRead? {
        val info = runCatching { instance.readTagFromBuffer() }.getOrNull() ?: return null
        return ProbeRead(
            raw0 = normalizeProbeField(info.getEPC()),
            raw1 = normalizeProbeField(info.getTid()),
            rssi = normalizeProbeField(info.getRssi()),
        )
    }

    private fun parseTagInfo(
        tagInfo: UHFTAGInfo,
        source: String,
    ): ParsedTag {
        val epc = normalizeProbeField(tagInfo.getEPC())
        val tid = normalizeProbeField(tagInfo.getTid())
        val rssiRaw = normalizeProbeField(tagInfo.getRssi())
        val rssi = rssiRaw?.toIntOrNull()
        if (!epc.isNullOrBlank() && inventoryRunning && inventoryLogCount < INVENTORY_LOG_LIMIT) {
            Log.i(
                LOG_TAG,
                "inventory tag source=$source epc=$epc rssi=${rssi ?: "--"} tid=${tid ?: "--"}",
            )
            inventoryLogCount += 1
        }
        return ParsedTag(
            epc = epc,
            tid = tid,
            rssi = rssi,
            rssiRaw = rssiRaw,
        )
    }

    private fun recordParsedTag(parsed: ParsedTag) {
        diagnosticsState.update { current ->
            val tagFound = !parsed.epc.isNullOrBlank()
            current.copy(
                lastRaw0 = parsed.epc,
                lastRaw1 = parsed.tid,
                lastRssi = parsed.rssi ?: current.lastRssi,
                lastReadEpc = if (tagFound) parsed.epc else current.lastReadEpc,
                tagsSeenCount = if (tagFound) current.tagsSeenCount + 1 else current.tagsSeenCount,
            )
        }
    }

    private suspend fun recoverAfterStopFailure(instance: IUHF): Boolean {
        val initOk =
            withContext(Dispatchers.IO) {
                uhfMutex.withLock {
                    val freeOk = runCatching { instance.free() }.getOrNull()
                    Log.w(LOG_TAG, "stopInventory recovery free ok=$freeOk")
                    val initR2000Ok = initR2000IfSupported(instance)?.getOrNull() == true
                    Log.w(LOG_TAG, "stopInventory recovery init_R2000 ok=$initR2000Ok")
                    if (initR2000Ok) {
                        true
                    } else {
                        val initOk = runCatching { instance.init(context) }.getOrNull()
                        Log.w(LOG_TAG, "stopInventory recovery init(context) ok=$initOk")
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
                val applyResult = applyDesiredConfigBestEffortLocked("stop-recover")
                if (applyResult.isFailure) {
                    Log.w(
                        LOG_TAG,
                        "stopInventory recovery apply failed: ${applyResult.exceptionOrNull()?.message}",
                    )
                }
            }
        }
        return initOk
    }

    private fun <T> configBusyResult(
        op: String,
        reason: String,
    ): Result<T> {
        Log.i(
            LOG_TAG,
            "configOp skipped (busy): inventoryRunning=$inventoryRunning scanRunning=$scanRunning op=$op reason=$reason",
        )
        return Result.failure(UhfError.OperationInProgress.asException())
    }

    private fun isUhfBusy(): Boolean = inventoryRunning || scanRunning

    private fun configGetterBusy(
        op: String,
        reason: String,
    ): Result<Int> {
        logBusySkip(op = op, reason = reason)
        return Result.success(UHF_CONFIG_BUSY)
    }

    private fun logBusySkip(
        op: String,
        reason: String,
    ) {
        val now = System.currentTimeMillis()
        val shouldLog =
            synchronized(busyLogLock) {
                val last = busyLogTimestamps[op] ?: 0L
                if (now - last >= BUSY_LOG_THROTTLE_MS) {
                    busyLogTimestamps[op] = now
                    true
                } else {
                    false
                }
            }
        if (shouldLog) {
            Log.w(LOG_TAG, "$op skipped busy reason=$reason")
        }
    }

    private fun updateProtocolSupportFromGet(value: Int) {
        val resolved =
            if (value == UHF_PROTOCOL_UNSUPPORTED) ProtocolSupport.Unsupported else ProtocolSupport.Supported
        updateProtocolSupport(resolved)
    }

    private fun updateProtocolSupport(next: ProtocolSupport) {
        if (protocolSupport == ProtocolSupport.Unsupported && next != ProtocolSupport.Unsupported) {
            return
        }
        if (protocolSupport != next) {
            protocolSupport = next
            updateDiagnosticsProtocolState()
            if (next == ProtocolSupport.Unsupported) {
                Log.w(
                    LOG_TAG,
                    "protocol unsupported detected (get=-1 or set failed), skipping further setProtocol",
                )
            }
        }
    }

    private fun recordProtocolAttempt(attempt: ProtocolAttempt?) {
        lastProtocolAttempt = attempt
        updateDiagnosticsProtocolState()
    }

    private fun updateDiagnosticsProtocolState() {
        diagnosticsState.update {
            it.copy(
                protocolSupport = protocolSupport,
                lastProtocolAttempt = lastProtocolAttempt,
            )
        }
    }

    private suspend fun resolveDesiredConfig(): UhfDesiredConfig {
        return settingsStore.getDesiredUhfConfig()
    }

    private suspend fun getReaderLocked(): Result<IUHF> {
        reader?.let { return Result.success(it) }
        return uhfMutex.withLock { runCatching { RFIDWithUHFUART.getInstance() as IUHF } }
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

    private fun initR2000IfSupported(instance: IUHF): Result<Boolean>? {
        val method =
            instance.javaClass.methods.firstOrNull {
                it.name == "init_R2000" && it.parameterTypes.isEmpty()
            } ?: return null
        return runCatching { method.invoke(instance) }.map { resultToBoolean(it) }
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
        val hasReadback =
            result.afterMode != null ||
                result.afterPower != null ||
                result.afterRfLink != null ||
                result.afterProtocol != null
        if (!hasReadback) {
            if (!result.success) {
                Log.w(
                    LOG_TAG,
                    "applyUhfConfig best-effort failed (reason=${result.reason} " +
                        "modeApplied=${result.modeApplied} rfLinkApplied=${result.rfLinkApplied} " +
                        "powerApplied=${result.powerApplied} protocolSupport=${result.protocolSupport})",
                )
            }
            return
        }
        if (!result.success) {
            val protocolAppliedLabel = result.protocolApplied?.toString() ?: "N/A"
            Log.w(
                LOG_TAG,
                "applyUhfConfigWithReadback verify failed (reason=${result.reason} " +
                    "modeApplied=${result.modeApplied} protocolApplied=$protocolAppliedLabel " +
                    "rfLinkApplied=${result.rfLinkApplied} powerApplied=${result.powerApplied} " +
                    "protocolSupport=${result.protocolSupport})",
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

    private fun setPowerOnBySystemIfSupported(instance: IUHF) {
        runCatching {
            val method = instance.javaClass.getMethod("setPowerOnBySystem", Context::class.java)
            method.invoke(instance, context)
        }
    }

    private fun getErrCodeIfSupported(instance: IUHF): Int? {
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
        const val SINGLE_READ_RETRY_DELAY_MS = 75L
        const val INVENTORY_POLL_DELAY_MS = 50L
        const val INVENTORY_LOG_LIMIT = 5
        const val STOP_RETRY_ATTEMPTS = 3
        const val STOP_RETRY_DELAY_MS = 100L
        const val MATRIX_READS = 10
        const val MATRIX_READ_DELAY_MS = 100L
        const val BUSY_LOG_THROTTLE_MS = 2_000L
    }
}
