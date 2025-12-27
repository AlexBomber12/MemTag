package com.alexbomber12.memtag.integrations.uhf

import android.util.Log

const val LOG_TAG = "memtag-uhf"

object UhfLogger {
    fun i(message: String) {
        runCatching { Log.i(LOG_TAG, message) }
    }

    fun w(message: String) {
        runCatching { Log.w(LOG_TAG, message) }
    }

    fun e(
        message: String,
        error: Throwable? = null,
    ) {
        runCatching {
            if (error != null) {
                Log.e(LOG_TAG, message, error)
            } else {
                Log.e(LOG_TAG, message)
            }
        }
    }
}
