package com.alexbomber12.memtag.integrations.uhf

import android.content.Context
import com.alexbomber12.memtag.data.settings.SettingsStore

object UhfReaderProvider {
    fun create(
        context: Context,
        settingsStore: SettingsStore,
    ): UhfReader = FakeUhfReader()
}
