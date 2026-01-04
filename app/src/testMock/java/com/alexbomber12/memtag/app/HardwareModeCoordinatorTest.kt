package com.alexbomber12.memtag.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareModeCoordinatorTest {
    @Test
    fun sessionsAreExclusive() =
        runTest {
            val coordinator = HardwareModeCoordinator()
            val events = mutableListOf<String>()

            launch {
                coordinator.runUhfSession("uhf") {
                    events += "uhf-start"
                    delay(100)
                    events += "uhf-end"
                }
            }
            launch {
                coordinator.runQrSession("qr") {
                    events += "qr-start"
                    events += "qr-end"
                }
            }

            runCurrent()
            advanceTimeBy(50)
            assertEquals(listOf("uhf-start"), events)

            advanceUntilIdle()
            assertEquals(listOf("uhf-start", "uhf-end", "qr-start", "qr-end"), events)
        }
}
