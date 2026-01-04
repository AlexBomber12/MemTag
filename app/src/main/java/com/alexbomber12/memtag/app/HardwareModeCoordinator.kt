package com.alexbomber12.memtag.app

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class HardwareModeCoordinator {
    private val mutex = Mutex()
    private var mode: Mode = Mode.Idle

    enum class Mode {
        Idle,
        UHF,
        QR,
    }

    suspend fun <T> runQrSession(
        reason: String = "unknown",
        block: suspend () -> T,
    ): T {
        return runSession(next = Mode.QR, reason = reason, block = block)
    }

    suspend fun <T> runUhfSession(
        reason: String = "unknown",
        block: suspend () -> T,
    ): T {
        return runSession(next = Mode.UHF, reason = reason, block = block)
    }

    private suspend fun <T> runSession(
        next: Mode,
        reason: String,
        block: suspend () -> T,
    ): T =
        mutex.withLock {
            val previous = mode
            mode = next
            HwModeLogger.i("mode $previous -> $next reason=$reason thread=${Thread.currentThread().name}")
            try {
                block()
            } finally {
                val from = mode
                mode = Mode.Idle
                HwModeLogger.i("mode $from -> ${Mode.Idle} reason=$reason thread=${Thread.currentThread().name}")
            }
        }

    private object HwModeLogger {
        private const val TAG = "HwMode"

        fun i(message: String) {
            runCatching { Log.i(TAG, message) }
        }
    }
}
