package com.alexbomber12.memtag.integrations.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator

interface FindFeedbackController {
    fun playSound()

    fun vibrate(durationMs: Long)

    fun release()
}

class DeviceFindFeedbackController(
    context: Context,
) : FindFeedbackController {
    private val appContext = context.applicationContext
    private var toneGenerator: ToneGenerator? = null

    override fun playSound() {
        val generator =
            toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also { toneGenerator = it }
        generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
    }

    override fun vibrate(durationMs: Long) {
        VibrationHelper.vibrate(appContext, durationMs)
    }

    override fun release() {
        toneGenerator?.release()
        toneGenerator = null
    }

    private companion object {
        const val TONE_VOLUME = 80
        const val TONE_DURATION_MS = 40
    }
}
