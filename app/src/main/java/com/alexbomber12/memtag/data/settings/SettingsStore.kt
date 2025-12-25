package com.alexbomber12.memtag.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsStore {
    val settingsFlow: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun setMemento(
        baseUrl: String,
        token: String,
        libraryId: String,
    )

    suspend fun setUhf(
        region: String,
        power: Int,
    )

    suspend fun setScan2d(
        action: String,
        extraKey: String,
    )
}
