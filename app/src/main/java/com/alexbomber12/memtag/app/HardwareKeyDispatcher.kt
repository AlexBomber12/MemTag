package com.alexbomber12.memtag.app

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

sealed class HardwareAction {
    data object Rfid : HardwareAction()

    data object Scan : HardwareAction()
}

class HardwareKeyDispatcher {
    private val mutableActions =
        MutableSharedFlow<HardwareAction>(
            extraBufferCapacity = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
    val actions: SharedFlow<HardwareAction> = mutableActions

    fun tryEmit(action: HardwareAction) {
        mutableActions.tryEmit(action)
    }
}
