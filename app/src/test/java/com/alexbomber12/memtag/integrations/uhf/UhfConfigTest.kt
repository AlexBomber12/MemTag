package com.alexbomber12.memtag.integrations.uhf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UhfConfigTest {
    @Test
    fun resolvePowerAppliedAcceptsScaledReadback() {
        val applied =
            resolvePowerApplied(
                desiredDbm = 25,
                readback = 2500,
                scaleFactor = UHF_POWER_SCALE_FACTOR,
                toleranceDbm = UHF_POWER_TOLERANCE_DBM,
            )

        assertTrue(applied == true)
    }

    @Test
    fun resolvePowerAppliedFallsBackToUnverifiedWhenSetOk() {
        val applied =
            resolvePowerAppliedOrUnverified(
                desiredDbm = 25,
                readback = 10,
                setPowerOk = true,
                scaleFactor = UHF_POWER_SCALE_FACTOR,
                toleranceDbm = UHF_POWER_TOLERANCE_DBM,
            )

        assertEquals(null, applied)
    }
}
