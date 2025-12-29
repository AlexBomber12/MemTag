package com.alexbomber12.memtag.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionFlagsStore {
    private var findRunning: Boolean = false
    private var verifyRunning: Boolean = false
    private var batchRunning: Boolean = false
    private var diagRunning: Boolean = false

    private val mutableSessionActive = MutableStateFlow(false)
    val sessionActive: StateFlow<Boolean> = mutableSessionActive.asStateFlow()

    fun setFindRunning(isRunning: Boolean) {
        findRunning = isRunning
        updateSessionActive()
    }

    fun setVerifyRunning(isRunning: Boolean) {
        verifyRunning = isRunning
        updateSessionActive()
    }

    fun setBatchRunning(isRunning: Boolean) {
        batchRunning = isRunning
        updateSessionActive()
    }

    fun setDiagRunning(isRunning: Boolean) {
        diagRunning = isRunning
        updateSessionActive()
    }

    private fun updateSessionActive() {
        mutableSessionActive.value = findRunning || verifyRunning || batchRunning || diagRunning
    }
}
