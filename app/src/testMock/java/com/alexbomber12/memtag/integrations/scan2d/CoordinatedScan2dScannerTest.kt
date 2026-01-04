package com.alexbomber12.memtag.integrations.scan2d

import com.alexbomber12.memtag.app.HardwareModeCoordinator
import com.alexbomber12.memtag.integrations.uhf.MatrixProbeResult
import com.alexbomber12.memtag.integrations.uhf.ProtocolSupport
import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfApplyResult
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfRegion
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class CoordinatedScan2dScannerTest {
    @Test
    fun scanOnceUsesMainDispatcherForDelegateAndIoForUhf() =
        runTest {
            val events = mutableListOf<String>()
            val ioDispatcher = StandardTestDispatcher(testScheduler)
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = HardwareModeCoordinator()
            val uhfReader = RecordingUhfReader(events)
            val delegate = RecordingScan2dScanner(events, Result.success("OK"))
            val scanner =
                CoordinatedScan2dScanner(
                    delegate = delegate,
                    rawUhfReader = uhfReader,
                    coordinator = coordinator,
                    ioDispatcher = ioDispatcher,
                    mainDispatcher = mainDispatcher,
                )

            val result = async { scanner.scanOnce(timeoutMs = 1_000, source = "test") }
            advanceUntilIdle()

            assertTrue(result.await().isSuccess)
            assertEquals(listOf("stopInventory", "close", "delegate"), events)
            assertSame(ioDispatcher, uhfReader.stopDispatcher)
            assertSame(ioDispatcher, uhfReader.closeDispatcher)
            assertSame(mainDispatcher, delegate.dispatcher)
        }

    @Test
    fun scanOnceContinuesAfterUhfTimeouts() =
        runTest {
            val events = mutableListOf<String>()
            val ioDispatcher = StandardTestDispatcher(testScheduler)
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = HardwareModeCoordinator()
            val uhfReader = RecordingUhfReader(events, delayMs = 1_000)
            val delegate = RecordingScan2dScanner(events, Result.success("OK"))
            val scanner =
                CoordinatedScan2dScanner(
                    delegate = delegate,
                    rawUhfReader = uhfReader,
                    coordinator = coordinator,
                    ioDispatcher = ioDispatcher,
                    mainDispatcher = mainDispatcher,
                )

            val result = async { scanner.scanOnce(timeoutMs = 1_000, source = "test") }
            advanceTimeBy(1_000)
            advanceUntilIdle()

            assertTrue(result.await().isSuccess)
            assertEquals(listOf("stopInventory", "close", "delegate"), events)
        }

    @Test
    fun scanOnceClosesUhfBeforeDelegateFailure() =
        runTest {
            val events = mutableListOf<String>()
            val ioDispatcher = StandardTestDispatcher(testScheduler)
            val mainDispatcher = StandardTestDispatcher(testScheduler)
            val coordinator = HardwareModeCoordinator()
            val uhfReader = RecordingUhfReader(events)
            val delegate = RecordingScan2dScanner(events, Result.failure(IllegalStateException("fail")))
            val scanner =
                CoordinatedScan2dScanner(
                    delegate = delegate,
                    rawUhfReader = uhfReader,
                    coordinator = coordinator,
                    ioDispatcher = ioDispatcher,
                    mainDispatcher = mainDispatcher,
                )

            val result = async { scanner.scanOnce(timeoutMs = 1_000, source = "test") }
            advanceUntilIdle()

            assertTrue(result.await().isFailure)
            assertEquals(listOf("stopInventory", "close", "delegate"), events)
        }
}

private class RecordingUhfReader(
    private val events: MutableList<String>,
    private val delayMs: Long = 0L,
) : UhfReader {
    var stopDispatcher: CoroutineDispatcher? = null
    var closeDispatcher: CoroutineDispatcher? = null

    override suspend fun initialize(): Result<Unit> = Result.success(Unit)

    override suspend fun close(): Result<Unit> {
        events += "close"
        closeDispatcher = coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        if (delayMs > 0) {
            delay(delayMs)
        }
        return Result.success(Unit)
    }

    override suspend fun readSingle(timeoutMs: Long): Result<String> = Result.success("")

    override suspend fun writeEpc(
        epcHex: String,
        targetEpcHex: String?,
        timeoutMs: Long,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun verifyEpc(
        expectedEpcHex: String,
        timeoutMs: Long,
    ): Result<Boolean> = Result.success(true)

    override suspend fun startInventory(filterEpcHex: String?): Flow<TagReading> = flow {}

    override suspend fun stopInventory(): Result<Unit> {
        events += "stopInventory"
        stopDispatcher = coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        if (delayMs > 0) {
            delay(delayMs)
        }
        return Result.success(Unit)
    }

    override suspend fun setPower(dbm: Int): Result<Unit> = Result.success(Unit)

    override suspend fun getPower(reason: String): Result<Int> = Result.success(0)

    override suspend fun getFrequencyMode(reason: String): Result<Int> = Result.success(0)

    override suspend fun getProtocol(reason: String): Result<Int> = Result.success(0)

    override suspend fun getRfLink(reason: String): Result<Int> = Result.success(0)

    override suspend fun setRegion(region: UhfRegion): Result<Unit> = Result.success(Unit)

    override suspend fun getRegion(reason: String): Result<UhfRegion> = Result.success(UhfRegion.OTHER)

    override suspend fun applyDesiredConfigBestEffort(reason: String): Result<UhfApplyResult> =
        Result.success(
            UhfApplyResult(
                reason = reason,
                beforeMode = null,
                beforePower = null,
                beforeProtocol = null,
                beforeRfLink = null,
                desiredMode = 0,
                desiredPower = 0,
                desiredProtocol = 0,
                desiredRfLink = 0,
                setModeOk = true,
                setPowerOk = true,
                setProtocolOk = null,
                setRfLinkOk = true,
                afterMode = null,
                afterPower = null,
                afterProtocol = null,
                afterRfLink = null,
                protocolSupport = ProtocolSupport.Unknown,
                protocolAttempt = null,
                modeApplied = true,
                powerApplied = true,
                protocolApplied = null,
                rfLinkApplied = true,
            ),
        )

    override suspend fun applyDesiredConfigWithReadback(reason: String): Result<UhfApplyResult> = applyDesiredConfigBestEffort(reason)

    override suspend fun applyFindProfile(
        targetEpcHex: String?,
        useHardwareFilter: Boolean,
    ): Result<Unit> = Result.success(Unit)

    override suspend fun clearFindProfile(): Result<Unit> = Result.success(Unit)

    override suspend fun runMatrixProbe(): List<MatrixProbeResult> = emptyList()
}

private class RecordingScan2dScanner(
    private val events: MutableList<String>,
    private val result: Result<String>,
) : Scan2dScanner {
    var dispatcher: CoroutineDispatcher? = null

    override suspend fun scanOnce(
        timeoutMs: Long,
        source: String,
    ): Result<String> {
        dispatcher = coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher
        events += "delegate"
        return result
    }
}
