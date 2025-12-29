package com.alexbomber12.memtag.domain.find

import com.alexbomber12.memtag.integrations.uhf.TagReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityCalculatorTest {
    @Test
    fun strongerRssiProducesHigherScore() {
        val low =
            ProximityCalculator(
                targetEpc = "E2000017221101441890ABCD",
            ).onReading(reading(-75, 0L)) ?: error("Missing snapshot")

        val high =
            ProximityCalculator(
                targetEpc = "E2000017221101441890ABCD",
            ).onReading(reading(-55, 0L)) ?: error("Missing snapshot")

        assertTrue(high.rawScore > low.rawScore)
    }

    @Test
    fun increasesAsRssiAndHitRateImprove() {
        val calculator =
            ProximityCalculator(
                targetEpc = "E2000017221101441890ABCD",
                config = ProximityCalculator.Config(windowMs = 500L, hitsMax = 4, alpha = 0.2f),
            )

        val first =
            calculator.onReading(reading(-75, 0L)) ?: error("Missing snapshot")
        calculator.onReading(reading(-65, 100L))
        calculator.onReading(reading(-55, 200L))
        val last =
            calculator.onReading(reading(-45, 300L)) ?: error("Missing snapshot")

        assertTrue(last.smoothedScore > first.smoothedScore)
    }

    @Test
    fun singleOutlierDoesNotJumpWildly() {
        val calculator =
            ProximityCalculator(
                targetEpc = "E2000017221101441890ABCD",
                config = ProximityCalculator.Config(alpha = 0.2f),
            )

        calculator.onReading(reading(-65, 0L))
        calculator.onReading(reading(-65, 100L))
        calculator.onReading(reading(-65, 200L))
        calculator.onReading(reading(-65, 300L))
        val before = calculator.onTick(400L).smoothedScore

        val after =
            calculator.onReading(reading(-35, 450L))?.smoothedScore ?: error("Missing snapshot")

        assertTrue(after - before < 0.3f)
    }

    @Test
    fun decaysTowardZeroWhenHitsStop() {
        val calculator =
            ProximityCalculator(
                targetEpc = "E2000017221101441890ABCD",
                config = ProximityCalculator.Config(noSignalMs = 700L, decayPerSecond = 0.6f),
            )

        calculator.onReading(reading(-50, 0L))
        calculator.onReading(reading(-50, 100L))
        calculator.onReading(reading(-50, 200L))
        calculator.onReading(reading(-50, 300L))
        calculator.onReading(reading(-50, 400L))
        val steady = calculator.onTick(500L).smoothedScore

        val afterGap = calculator.onTick(1300L).smoothedScore
        val later = calculator.onTick(2300L).smoothedScore

        assertTrue(afterGap < steady)
        assertTrue(afterGap > 0f)
        assertTrue(later < afterGap)
    }

    @Test
    fun wakeUpBoostsFirstHitAfterIdle() {
        val calculator =
            ProximityCalculator(
                targetEpc = "E2000017221101441890ABCD",
                config =
                    ProximityCalculator.Config(
                        windowMs = 400L,
                        hitsMax = 4,
                        alpha = 0.3f,
                        noSignalMs = 400L,
                        decayPerSecond = 0.3f,
                        wakeUpIdleMs = 1000L,
                        wakeUpBoost = 1.4f,
                    ),
            )

        calculator.onReading(reading(-70, 0L))
        val afterDecay = calculator.onTick(3000L).smoothedScore
        val wake =
            calculator.onReading(reading(-70, 3200L)) ?: error("Missing snapshot")

        assertTrue(afterDecay < 0.05f)
        assertTrue(wake.smoothedScore > 0.1f)
        assertTrue(wake.lastWakeUpIdleMs != null)
    }

    @Test
    fun normalizesPositiveRssiValues() {
        val negative =
            ProximityCalculator(
                targetEpc = "E2000017221101441890ABCD",
            ).onReading(reading(-60, 0L)) ?: error("Missing snapshot")

        val positive =
            ProximityCalculator(
                targetEpc = "E2000017221101441890ABCD",
            ).onReading(reading(60, 0L)) ?: error("Missing snapshot")

        assertEquals(negative.rawScore, positive.rawScore, 0.0001f)
    }

    private fun reading(
        rssi: Int,
        timestampMs: Long,
    ): TagReading {
        return TagReading(
            epcHex = "E2000017221101441890ABCD",
            rssi = rssi,
            timestampMs = timestampMs,
        )
    }
}
