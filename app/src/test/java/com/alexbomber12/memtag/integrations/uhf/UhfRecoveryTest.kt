package com.alexbomber12.memtag.integrations.uhf

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UhfRecoveryTest {
    @Test
    fun ensureConfiguredWithRecoveryRetriesOnceOnFailure() =
        runTest {
            val reader =
                TestUhfReader(
                    ArrayDeque(
                        listOf(
                            Result.success(buildApplyResult(modeApplied = false)),
                            Result.success(buildApplyResult(modeApplied = true)),
                        ),
                    ),
                )

            val result = reader.ensureConfiguredWithRecovery("lookup-scan")

            assertTrue(result.isSuccess)
            assertEquals(listOf("lookup-scan", "lookup-scan-recover"), reader.applyReasons)
            assertEquals(2, reader.initializeCalls)
            assertEquals(1, reader.closeCalls)
            assertTrue(result.getOrNull()?.recoveryAttempted == true)
        }

    @Test
    fun ensureConfiguredWithRecoveryRetriesAfterOperationInProgress() =
        runTest {
            val reader =
                TestUhfReader(
                    ArrayDeque(
                        listOf(
                            Result.failure(UhfError.OperationInProgress.asException()),
                            Result.success(buildApplyResult(modeApplied = true)),
                        ),
                    ),
                )

            val result = reader.ensureConfiguredWithRecovery("diag-scan")

            assertTrue(result.isSuccess)
            assertEquals(listOf("diag-scan", "diag-scan-recover"), reader.applyReasons)
            assertEquals(2, reader.initializeCalls)
            assertEquals(1, reader.closeCalls)
            assertTrue(result.getOrNull()?.recoveryAttempted == true)
        }

    private fun buildApplyResult(modeApplied: Boolean): UhfApplyResult {
        return UhfApplyResult(
            reason = "ignored",
            beforeMode = null,
            beforePower = null,
            beforeProtocol = null,
            beforeRfLink = null,
            desiredMode = 1,
            desiredPower = 20,
            desiredProtocol = UHF_PROTOCOL_ISO_18000_6C,
            desiredRfLink = UHF_RFLINK_DSB_ASK,
            setModeOk = true,
            setPowerOk = true,
            setProtocolOk = true,
            setRfLinkOk = true,
            afterMode = 1,
            afterPower = 20,
            afterProtocol = UHF_PROTOCOL_ISO_18000_6C,
            afterRfLink = UHF_RFLINK_DSB_ASK,
            protocolSupport = ProtocolSupport.Supported,
            protocolAttempt = null,
            modeApplied = modeApplied,
            powerApplied = true,
            protocolApplied = true,
            rfLinkApplied = true,
        )
    }

    private class TestUhfReader(
        private val applyResults: ArrayDeque<Result<UhfApplyResult>>,
    ) : UhfReader {
        var initializeCalls: Int = 0
            private set
        var closeCalls: Int = 0
            private set
        val applyReasons = mutableListOf<String>()

        override suspend fun initialize(): Result<Unit> {
            initializeCalls += 1
            return Result.success(Unit)
        }

        override suspend fun close(): Result<Unit> {
            closeCalls += 1
            return Result.success(Unit)
        }

        override suspend fun readSingle(timeoutMs: Long): Result<String> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun writeEpc(
            epcHex: String,
            targetEpcHex: String?,
            timeoutMs: Long,
        ): Result<Unit> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun verifyEpc(
            expectedEpcHex: String,
            timeoutMs: Long,
        ): Result<Boolean> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun startInventory(filterEpcHex: String?): Flow<TagReading> {
            return emptyFlow()
        }

        override suspend fun stopInventory(): Result<Unit> {
            return Result.success(Unit)
        }

        override suspend fun setPower(dbm: Int): Result<Unit> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun getPower(reason: String): Result<Int> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun getFrequencyMode(reason: String): Result<Int> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun getProtocol(reason: String): Result<Int> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun getRfLink(reason: String): Result<Int> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun setRegion(region: UhfRegion): Result<Unit> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun getRegion(reason: String): Result<UhfRegion> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun applyDesiredConfigBestEffort(reason: String): Result<UhfApplyResult> {
            applyReasons.add(reason)
            val result = applyResults.removeFirst()
            return result.map { it.copy(reason = reason) }
        }

        override suspend fun applyDesiredConfigWithReadback(reason: String): Result<UhfApplyResult> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun applyFindProfile(
            targetEpcHex: String?,
            useHardwareFilter: Boolean,
        ): Result<Unit> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun clearFindProfile(): Result<Unit> {
            return Result.failure(IllegalStateException("Not used"))
        }

        override suspend fun runMatrixProbe(): List<MatrixProbeResult> {
            return emptyList()
        }
    }
}
