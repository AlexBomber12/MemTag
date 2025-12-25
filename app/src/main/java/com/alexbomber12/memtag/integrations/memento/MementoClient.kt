package com.alexbomber12.memtag.integrations.memento

interface MementoClient {
    suspend fun fetchLibrarySchema(config: MementoConfig): MementoLibrarySchema

    suspend fun fetchEntriesPage(
        config: MementoConfig,
        request: MementoEntriesRequest,
    ): MementoEntriesPage
}
