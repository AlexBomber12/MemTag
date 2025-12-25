package com.alexbomber12.memtag.domain

import com.alexbomber12.memtag.data.repository.MementoRepository

class LookupByEpcUseCase(
    private val repository: MementoRepository,
) {
    suspend fun execute(
        epcRaw: String,
        allowNetwork: Boolean = false,
    ): LookupResult {
        return repository.lookupByEpc(epcRaw, allowNetwork)
    }
}
