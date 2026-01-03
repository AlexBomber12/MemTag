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
    fun resolvePowerAppliedOrUnverifiedReturnsTrueWhenReadbackMatches() {
        val applied =
            resolvePowerAppliedOrUnverified(
                desiredDbm = 25,
                readback = 25,
                setPowerOk = false,
                scaleFactor = UHF_POWER_SCALE_FACTOR,
                toleranceDbm = UHF_POWER_TOLERANCE_DBM,
            )

        assertEquals(true, applied)
    }

    @Test
    fun resolvePowerAppliedOrUnverifiedReturnsFalseWhenReadbackMismatches() {
        val applied =
            resolvePowerAppliedOrUnverified(
                desiredDbm = 25,
                readback = 10,
                setPowerOk = true,
                scaleFactor = UHF_POWER_SCALE_FACTOR,
                toleranceDbm = UHF_POWER_TOLERANCE_DBM,
            )

        assertEquals(false, applied)
    }

    @Test
    fun resolvePowerAppliedOrUnverifiedReturnsNullWhenReadbackMissingAndSetOk() {
        val applied =
            resolvePowerAppliedOrUnverified(
                desiredDbm = 25,
                readback = null,
                setPowerOk = true,
                scaleFactor = UHF_POWER_SCALE_FACTOR,
                toleranceDbm = UHF_POWER_TOLERANCE_DBM,
            )

        assertEquals(null, applied)
    }

    @Test
    fun resolvePowerAppliedOrUnverifiedReturnsNullWhenReadbackMissingAndSetFails() {
        val applied =
            resolvePowerAppliedOrUnverified(
                desiredDbm = 25,
                readback = null,
                setPowerOk = false,
                scaleFactor = UHF_POWER_SCALE_FACTOR,
                toleranceDbm = UHF_POWER_TOLERANCE_DBM,
            )

        assertEquals(null, applied)
    }
}
