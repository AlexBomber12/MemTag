package com.alexbomber12.memtag.integrations.scan2d

import com.alexbomber12.memtag.testing.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class Soft2dScan2dScannerTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun scanOnceSuccessCallsStopAndClose() =
        runTest(mainDispatcherRule.dispatcher) {
            val calls = mutableListOf<String>()
            val adapter = FakeSoft2dAdapter(calls, payload = VALID_EPC)
            val scanner = Soft2dScan2dScanner(adapter, mainDispatcher = mainDispatcherRule.dispatcher)

            val result = async { scanner.scanOnce(timeoutMs = 1_000, source = "test") }
            advanceUntilIdle()

            assertTrue(result.await().isSuccess)
            assertOrder(calls, listOf("open", "setCallback", "scan", "stopScan", "close"))
        }

    @Test
    fun scanOnceTimeoutStillStopsAndCloses() =
        runTest(mainDispatcherRule.dispatcher) {
            val calls = mutableListOf<String>()
            val adapter = FakeSoft2dAdapter(calls, payload = null, autoCallback = false)
            val scanner = Soft2dScan2dScanner(adapter, mainDispatcher = mainDispatcherRule.dispatcher)

            val result = async { scanner.scanOnce(timeoutMs = 1_000, source = "test") }
            advanceTimeBy(1_000)
            advanceUntilIdle()

            val error = result.await().exceptionOrNull() as? Scan2dException
            assertEquals(Scan2dError.Timeout, error?.error)
            assertTrue(calls.contains("stopScan"))
            assertTrue(calls.contains("close"))
        }

    @Test
    fun concurrentScanReturnsOperationInProgress() =
        runTest(mainDispatcherRule.dispatcher) {
            val calls = mutableListOf<String>()
            val adapter = FakeSoft2dAdapter(calls, payload = null, autoCallback = false)
            val scanner = Soft2dScan2dScanner(adapter, mainDispatcher = mainDispatcherRule.dispatcher)

            val first = async { scanner.scanOnce(timeoutMs = 10_000, source = "test") }
            runCurrent()
            val second = async { scanner.scanOnce(timeoutMs = 1_000, source = "test") }
            runCurrent()

            val error = second.await().exceptionOrNull() as? Scan2dException
            assertEquals(Scan2dError.OperationInProgress, error?.error)

            first.cancelAndJoin()
            advanceUntilIdle()
        }

    private fun assertOrder(
        calls: List<String>,
        ordered: List<String>,
    ) {
        var lastIndex = -1
        ordered.forEach { label ->
            val nextIndex = calls.indexOf(label)
            assertTrue("Missing call: $label", nextIndex >= 0)
            assertTrue("Call order wrong for $label", nextIndex > lastIndex)
            lastIndex = nextIndex
        }
    }

    private companion object {
        const val VALID_EPC = "E2000017221101441890ABCD"
    }
}

private class FakeSoft2dAdapter(
    private val calls: MutableList<String>,
    private val payload: String?,
    private val autoCallback: Boolean = true,
) : Soft2dScannerAdapter {
    private var callback: Soft2dScanCallback? = null

    override fun open(): Boolean {
        calls += "open"
        return true
    }

    override fun setDefaultParameters() {
        calls += "setDefaultParameters"
    }

    override fun enableAllCodeTypes() {
        calls += "enableAllCodeTypes"
    }

    override fun setTimeOut(seconds: Int) {
        calls += "setTimeOut:$seconds"
    }

    override fun setScanCallback(callback: Soft2dScanCallback) {
        calls += "setCallback"
        this.callback = callback
    }

    override fun scan() {
        calls += "scan"
        if (autoCallback && payload != null) {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            callback?.onScanComplete(1, bytes.size, bytes)
        }
    }

    override fun stopScan() {
        calls += "stopScan"
    }

    override fun close(): Boolean {
        calls += "close"
        return true
    }
}
