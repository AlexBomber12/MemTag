package com.alexbomber12.memtag.integrations.uhf

import android.content.Context
import com.alexbomber12.memtag.util.epc.EpcNormalizer
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class ChainwayUhfReader(
    private val context: Context,
) : UhfReader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val inventoryFlow =
        MutableSharedFlow<TagReading>(
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    private var reader: RFIDWithUHFUART? = null

    private var initialized = false
    private var inventoryJob: Job? = null

    override suspend fun initialize(): Result<Unit> {
        synchronized(lock) {
            if (initialized) {
                return Result.success(Unit)
            }
        }
        val instance =
            getReader()
                .getOrElse { error ->
                    return Result.failure(
                        UhfError.VendorError(error.message ?: "UHF init error").asException(cause = error),
                    )
                }
        return withContext(Dispatchers.IO) {
            val contextInit =
                runCatching { instance.init(context) }
                    .getOrElse { error ->
                        return@withContext Result.failure(
                            UhfError.VendorError(error.message ?: "UHF init error")
                                .asException(cause = error),
                        )
                    }
            val initSuccess =
                if (contextInit) {
                    true
                } else {
                    runCatching { instance.init() }
                        .getOrElse { error ->
                            return@withContext Result.failure(
                                UhfError.VendorError(error.message ?: "UHF init error")
                                    .asException(cause = error),
                            )
                        }
                }
            if (initSuccess) {
                synchronized(lock) { initialized = true }
                Result.success(Unit)
            } else {
                Result.failure(
                    UhfError.HardwareUnavailable.asException(
                        message = "UHF initialization failed.",
                    ),
                )
            }
        }
    }

    override suspend fun close(): Result<Unit> {
        stopInventory()
        val instance = reader ?: return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            runCatching {
                instance.free()
                synchronized(lock) { initialized = false }
                reader = null
            }.fold(
                onSuccess = { Result.success(Unit) },
                onFailure = { error ->
                    Result.failure(
                        UhfError.VendorError(error.message ?: "UHF close error")
                            .asException(cause = error),
                    )
                },
            )
        }
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
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return try {
            withTimeout(timeoutMs) {
                val tag = withContext(Dispatchers.IO) { instance.inventorySingleTag() }
                val epc = tag?.let(::tagToEpc)
                if (epc.isNullOrBlank()) {
                    Result.failure(UhfError.Timeout.asException())
                } else {
                    Result.success(epc)
                }
            }
        } catch (_: TimeoutCancellationException) {
            Result.failure(UhfError.Timeout.asException())
        } catch (error: Throwable) {
            Result.failure(
                UhfError.VendorError(error.message ?: "UHF read error")
                    .asException(cause = error),
            )
        }
    }

    override suspend fun writeEpc(
        epcHex: String,
        timeoutMs: Long,
    ): Result<Unit> {
        synchronized(lock) {
            if (!initialized) {
                return Result.failure(UhfError.NotInitialized.asException())
            }
            if (inventoryJob != null) {
                return Result.failure(UhfError.OperationInProgress.asException())
            }
        }
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        val normalized =
            runCatching { EpcNormalizer.normalize(epcHex) }.getOrElse { error ->
                return Result.failure(
                    UhfError.VendorError(error.message ?: "Invalid EPC").asException(cause = error),
                )
            }
        if (normalized.length % EPC_WORD_HEX_LENGTH != 0) {
            return Result.failure(
                UhfError.VendorError("EPC must be a multiple of 4 hex characters.")
                    .asException(),
            )
        }
        val wordCount = normalized.length / EPC_WORD_HEX_LENGTH
        if (wordCount > EPC_MAX_WORDS) {
            return Result.failure(
                UhfError.VendorError("EPC length exceeds maximum supported size.")
                    .asException(),
            )
        }
        return try {
            withTimeout(timeoutMs) {
                val pcWord =
                    withContext(Dispatchers.IO) {
                        instance.readData(DEFAULT_ACCESS_PASSWORD, EPC_MEMORY_BANK, EPC_PC_WORD, 1)
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
                        instance.writeData(
                            DEFAULT_ACCESS_PASSWORD,
                            EPC_MEMORY_BANK,
                            EPC_PC_WORD,
                            totalWords,
                            payload,
                        )
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
    ): Result<Boolean> {
        val normalizedExpected =
            runCatching { EpcNormalizer.normalize(expectedEpcHex) }.getOrElse { error ->
                return Result.failure(
                    UhfError.VendorError(error.message ?: "Invalid EPC").asException(cause = error),
                )
            }
        val readResult = readSingle(timeoutMs)
        if (readResult.isFailure) {
            return Result.failure(readResult.exceptionOrNull() ?: UhfError.VendorError("Verify failed").asException())
        }
        val normalizedRead =
            runCatching { EpcNormalizer.normalize(readResult.getOrNull().orEmpty()) }.getOrElse { error ->
                return Result.failure(
                    UhfError.VendorError(error.message ?: "Invalid EPC").asException(cause = error),
                )
            }
        return Result.success(normalizedRead == normalizedExpected)
    }

    override fun startInventory(filterEpcHex: String?): Flow<TagReading> {
        synchronized(lock) {
            if (!initialized) {
                return flow { throw UhfError.NotInitialized.asException() }
            }
            if (inventoryJob == null) {
                val instance = reader ?: return flow { throw UhfError.HardwareUnavailable.asException() }
                val started =
                    runCatching { instance.startInventoryTag() }
                        .getOrElse { error ->
                            return flow {
                                throw UhfError.VendorError(error.message ?: "UHF start error")
                                    .asException(cause = error)
                            }
                        }
                if (!started) {
                    return flow { throw UhfError.VendorError("Failed to start inventory").asException() }
                }
                inventoryJob =
                    scope.launch {
                        while (isActive) {
                            val tag = runCatching { instance.readTagFromBuffer() }.getOrNull()
                            val epc = tag?.let(::tagToEpc)
                            if (!epc.isNullOrBlank()) {
                                val rssi = tagToRssi(tag)
                                inventoryFlow.emit(
                                    TagReading(
                                        epcHex = epc,
                                        rssi = rssi,
                                        timestampMs = System.currentTimeMillis(),
                                    ),
                                )
                            } else {
                                delay(20)
                            }
                        }
                    }
            }
        }
        val baseFlow = inventoryFlow.asSharedFlow()
        // Apply EPC filtering in-app; add SDK hardware filtering here if supported.
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
        if (jobToCancel == null) {
            return Result.success(Unit)
        }
        jobToCancel.cancel()
        val instance = reader ?: return Result.success(Unit)
        return withContext(Dispatchers.IO) {
            runCatching { instance.stopInventory() }
                .fold(
                    onSuccess = { Result.success(Unit) },
                    onFailure = { error ->
                        Result.failure(
                            UhfError.VendorError(error.message ?: "UHF stop error")
                                .asException(cause = error),
                        )
                    },
                )
        }
    }

    override suspend fun setPower(dbm: Int): Result<Unit> {
        synchronized(lock) {
            if (!initialized) {
                return Result.failure(UhfError.NotInitialized.asException())
            }
        }
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return withContext(Dispatchers.IO) {
            runCatching { instance.setPower(dbm) }
                .fold(
                    onSuccess = { success ->
                        if (success) {
                            Result.success(Unit)
                        } else {
                            Result.failure(UhfError.VendorError("Failed to set power").asException())
                        }
                    },
                    onFailure = { error ->
                        Result.failure(
                            UhfError.VendorError(error.message ?: "UHF power error")
                                .asException(cause = error),
                        )
                    },
                )
        }
    }

    override suspend fun getPower(): Result<Int> {
        synchronized(lock) {
            if (!initialized) {
                return Result.failure(UhfError.NotInitialized.asException())
            }
        }
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return withContext(Dispatchers.IO) {
            runCatching { instance.power }
                .fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { error ->
                        Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get power error")
                                .asException(cause = error),
                        )
                    },
                )
        }
    }

    override suspend fun setRegion(region: UhfRegion): Result<Unit> {
        synchronized(lock) {
            if (!initialized) {
                return Result.failure(UhfError.NotInitialized.asException())
            }
        }
        val mode =
            regionToMode(region)
                ?: return Result.failure(UhfError.VendorError("Region not supported by SDK").asException())
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return withContext(Dispatchers.IO) {
            runCatching { instance.setFrequencyMode(mode) }
                .fold(
                    onSuccess = { success ->
                        if (success) {
                            Result.success(Unit)
                        } else {
                            Result.failure(UhfError.VendorError("Failed to set region").asException())
                        }
                    },
                    onFailure = { error ->
                        Result.failure(
                            UhfError.VendorError(error.message ?: "UHF region error")
                                .asException(cause = error),
                        )
                    },
                )
        }
    }

    override suspend fun getRegion(): Result<UhfRegion> {
        synchronized(lock) {
            if (!initialized) {
                return Result.failure(UhfError.NotInitialized.asException())
            }
        }
        val instance = reader ?: return Result.failure(UhfError.HardwareUnavailable.asException())
        return withContext(Dispatchers.IO) {
            runCatching { instance.frequencyMode }
                .fold(
                    onSuccess = { Result.success(modeToRegion(it)) },
                    onFailure = { error ->
                        Result.failure(
                            UhfError.VendorError(error.message ?: "UHF get region error")
                                .asException(cause = error),
                        )
                    },
                )
        }
    }

    private fun tagToEpc(tag: UHFTAGInfo): String? = tag.epc

    private fun tagToRssi(tag: UHFTAGInfo): Int? {
        return runCatching { tag.rssi.toString().toIntOrNull() }.getOrNull()
    }

    private fun regionToMode(region: UhfRegion): Int? {
        return when (region) {
            UhfRegion.EU -> 2
            UhfRegion.US -> 1
            UhfRegion.JP -> 4
            UhfRegion.CN -> 0
            UhfRegion.OTHER -> null
        }
    }

    private fun modeToRegion(mode: Int): UhfRegion {
        return when (mode) {
            2 -> UhfRegion.EU
            1 -> UhfRegion.US
            4 -> UhfRegion.JP
            0 -> UhfRegion.CN
            else -> UhfRegion.OTHER
        }
    }

    private fun getReader(): Result<RFIDWithUHFUART> {
        synchronized(lock) {
            reader?.let { return Result.success(it) }
        }
        return runCatching { RFIDWithUHFUART.getInstance() }
            .fold(
                onSuccess = { instance ->
                    synchronized(lock) { reader = instance }
                    Result.success(instance)
                },
                onFailure = { error ->
                    Result.failure(error)
                },
            )
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
    }
}
