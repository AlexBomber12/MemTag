package com.alexbomber12.memtag.integrations.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

interface HapticEngine {
    fun hasVibrator(): Boolean

    fun hasAmplitudeControl(): Boolean

    fun pulse(
        durationMs: Long,
        amplitude: Int? = null,
    )

    fun testPulse()
}

enum class HapticApiPath {
    VibratorManager,
    Vibrator,
}

class SystemHapticEngine(context: Context) : HapticEngine {
    private val appContext = context.applicationContext

    val apiPath: HapticApiPath =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            HapticApiPath.VibratorManager
        } else {
            HapticApiPath.Vibrator
        }

    override fun hasVibrator(): Boolean {
        return resolveVibrator()?.hasVibrator() == true
    }

    override fun hasAmplitudeControl(): Boolean {
        val vibrator = resolveVibrator() ?: return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
    }

    override fun pulse(
        durationMs: Long,
        amplitude: Int?,
    ) {
        if (durationMs <= 0) {
            return
        }
        val vibrator = resolveVibrator() ?: return
        if (!vibrator.hasVibrator()) {
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudeValue =
                    if (amplitude != null && vibrator.hasAmplitudeControl()) {
                        amplitude.coerceIn(1, 255)
                    } else {
                        VibrationEffect.DEFAULT_AMPLITUDE
                    }
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitudeValue))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
        } catch (_: Exception) {
            // Ignore vibrator failures (e.g., DND/system settings).
        }
    }

    override fun testPulse() {
        pulse(TEST_PULSE_MS)
    }

    private fun resolveVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            appContext.getSystemService(Vibrator::class.java)
        }
    }

    companion object {
        const val TEST_PULSE_MS = 110L
    }
}
