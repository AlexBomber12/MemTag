package com.alexbomber12.memtag.integrations.scan2d

import android.util.Log

const val SCAN2D_LOG_TAG = "Scan2D"

object Scan2dLogger {
    fun i(message: String) {
        runCatching { Log.i(SCAN2D_LOG_TAG, message) }
    }

    fun w(
        message: String,
        error: Throwable? = null,
    ) {
        runCatching {
            if (error != null) {
                Log.w(SCAN2D_LOG_TAG, message, error)
            } else {
                Log.w(SCAN2D_LOG_TAG, message)
            }
        }
    }
}
