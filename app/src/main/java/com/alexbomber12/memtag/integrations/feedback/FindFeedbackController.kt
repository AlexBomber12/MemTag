package com.alexbomber12.memtag.integrations.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

interface FindFeedbackController {
    fun playSound()

    fun vibrate(durationMs: Long)

    fun release()
}

class DeviceFindFeedbackController(
    context: Context,
) : FindFeedbackController {
    private val appContext = context.applicationContext
    private val vibrator = appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator?
    private var toneGenerator: ToneGenerator? = null

    override fun playSound() {
        val generator =
            toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, TONE_VOLUME).also { toneGenerator = it }
        generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_DURATION_MS)
    }

    override fun vibrate(durationMs: Long) {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vib.vibrate(durationMs)
        }
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
