package com.alexbomber12.memtag.integrations.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock

interface FindFeedbackController {
    fun playSound()

    fun vibrate(durationMs: Long)

    fun release()
}

class DeviceFindFeedbackController(
    context: Context,
    private val hapticEngine: HapticEngine = SystemHapticEngine(context),
) : FindFeedbackController {
    private var toneGenerator: ToneGenerator? = null
    private var lastVibrateAtMs: Long = 0L

    override fun playSound() {
        val generator =
            toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also { toneGenerator = it }
        generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
    }

    override fun vibrate(durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastVibrateAtMs < MIN_VIBRATE_INTERVAL_MS) {
            return
        }
        lastVibrateAtMs = now
        hapticEngine.pulse(durationMs)
    }

    override fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }

    private companion object {
        const val TONE_VOLUME = 80
        const val TONE_DURATION_MS = 40
        const val MIN_VIBRATE_INTERVAL_MS = 60L
    }
}
