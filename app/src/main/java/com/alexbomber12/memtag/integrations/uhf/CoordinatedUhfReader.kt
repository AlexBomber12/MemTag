package com.alexbomber12.memtag.integrations.uhf

import com.alexbomber12.memtag.app.HardwareModeCoordinator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class CoordinatedUhfReader(
    private val delegate: UhfReader,
    private val coordinator: HardwareModeCoordinator,
) : UhfReader, UhfDiagnosticsSource {
    private val diagnosticsSource = delegate as? UhfDiagnosticsSource
    override val diagnosticsFlow: StateFlow<UhfDiagnostics> =
        diagnosticsSource?.diagnosticsFlow ?: MutableStateFlow(UhfDiagnostics())

    override suspend fun initialize(): Result<Unit> =
        coordinator.runUhfSession(reason = "uhf.initialize") {
            delegate.initialize()
        }

    override suspend fun close(): Result<Unit> =
        coordinator.runUhfSession(reason = "uhf.close") {
            delegate.close()
        }

    override suspend fun readSingle(timeoutMs: Long): Result<String> =
        coordinator.runUhfSession(reason = "uhf.readSingle") {
            delegate.readSingle(timeoutMs)
        }

    override suspend fun writeEpc(
        epcHex: String,
        targetEpcHex: String?,
        timeoutMs: Long,
    ): Result<Unit> =
        coordinator.runUhfSession(reason = "uhf.writeEpc") {
            delegate.writeEpc(epcHex, targetEpcHex, timeoutMs)
        }

    override suspend fun verifyEpc(
        expectedEpcHex: String,
        timeoutMs: Long,
    ): Result<Boolean> =
        coordinator.runUhfSession(reason = "uhf.verifyEpc") {
            delegate.verifyEpc(expectedEpcHex, timeoutMs)
        }

    override suspend fun startInventory(filterEpcHex: String?): Flow<TagReading> =
        flow {
            coordinator.runUhfSession(reason = "uhf.startInventory") {
                delegate.startInventory(filterEpcHex).collect { reading ->
                    emit(reading)
                }
            }
        }

    override suspend fun stopInventory(): Result<Unit> =
        coordinator.runUhfSession(reason = "uhf.stopInventory") {
            delegate.stopInventory()
        }

    override suspend fun setPower(dbm: Int): Result<Unit> =
        coordinator.runUhfSession(reason = "uhf.setPower") {
            delegate.setPower(dbm)
        }

    override suspend fun getPower(reason: String): Result<Int> =
        coordinator.runUhfSession(reason = "uhf.getPower:$reason") {
            delegate.getPower(reason)
        }

    override suspend fun getFrequencyMode(reason: String): Result<Int> =
        coordinator.runUhfSession(reason = "uhf.getFrequencyMode:$reason") {
            delegate.getFrequencyMode(reason)
        }

    override suspend fun getProtocol(reason: String): Result<Int> =
        coordinator.runUhfSession(reason = "uhf.getProtocol:$reason") {
            delegate.getProtocol(reason)
        }

    override suspend fun getRfLink(reason: String): Result<Int> =
        coordinator.runUhfSession(reason = "uhf.getRfLink:$reason") {
            delegate.getRfLink(reason)
        }

    override suspend fun setRegion(region: UhfRegion): Result<Unit> =
        coordinator.runUhfSession(reason = "uhf.setRegion") {
            delegate.setRegion(region)
        }

    override suspend fun getRegion(reason: String): Result<UhfRegion> =
        coordinator.runUhfSession(reason = "uhf.getRegion:$reason") {
            delegate.getRegion(reason)
        }

    override suspend fun applyDesiredConfigBestEffort(reason: String): Result<UhfApplyResult> =
        coordinator.runUhfSession(reason = "uhf.applyConfig:$reason") {
            delegate.applyDesiredConfigBestEffort(reason)
        }

    override suspend fun applyDesiredConfigWithReadback(reason: String): Result<UhfApplyResult> =
        coordinator.runUhfSession(reason = "uhf.applyConfigReadback:$reason") {
            delegate.applyDesiredConfigWithReadback(reason)
        }

    override suspend fun applyFindProfile(
        targetEpcHex: String?,
        useHardwareFilter: Boolean,
    ): Result<Unit> =
        coordinator.runUhfSession(reason = "uhf.applyFindProfile") {
            delegate.applyFindProfile(targetEpcHex, useHardwareFilter)
        }

    override suspend fun clearFindProfile(): Result<Unit> =
        coordinator.runUhfSession(reason = "uhf.clearFindProfile") {
            delegate.clearFindProfile()
        }

    override suspend fun runMatrixProbe(): List<MatrixProbeResult> =
        coordinator.runUhfSession(reason = "uhf.matrixProbe") {
            delegate.runMatrixProbe()
        }
}
