package com.alexbomber12.memtag.integrations.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

enum class VibrationResult {
    Triggered,
    NoVibrator,
}

object VibrationHelper {
    private const val TAG = "memtag-vibration"
    private const val SHORT_PULSE_MS = 50L

    fun shortPulse(context: Context): VibrationResult {
        return vibrate(context, SHORT_PULSE_MS)
    }

    fun vibrate(
        context: Context,
        durationMs: Long,
    ): VibrationResult {
        val vibrator = resolveVibrator(context)
        if (vibrator == null) {
            Log.i(TAG, "No vibrator service available.")
            return VibrationResult.NoVibrator
        }
        if (!vibrator.hasVibrator()) {
            Log.i(TAG, "No vibrator hardware available.")
            return VibrationResult.NoVibrator
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs)
            }
            VibrationResult.Triggered
        } catch (error: Exception) {
            Log.w(TAG, "Failed to trigger vibration.", error)
            VibrationResult.NoVibrator
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }
    }
}
