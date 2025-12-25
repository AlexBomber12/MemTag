package com.alexbomber12.memtag.integrations.memento

interface MementoClient {
    suspend fun ping(): Boolean
}

class FakeMementoClient : MementoClient {
    override suspend fun ping(): Boolean {
        throw NotImplementedError("MementoClient is not implemented in PR-01.")
    }
}
