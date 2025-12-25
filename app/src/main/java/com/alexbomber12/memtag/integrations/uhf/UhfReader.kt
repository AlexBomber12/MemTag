package com.alexbomber12.memtag.integrations.uhf

interface UhfReader {
    fun connect(): Boolean

    fun disconnect()
}

class FakeUhfReader : UhfReader {
    override fun connect(): Boolean {
        throw NotImplementedError("UHF reader is not implemented in PR-01.")
    }

    override fun disconnect() {
        throw NotImplementedError("UHF reader is not implemented in PR-01.")
    }
}
