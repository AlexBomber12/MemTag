package com.alexbomber12.memtag.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.alexbomber12.memtag.core.logging.AndroidLogger
import com.alexbomber12.memtag.core.logging.Logger
import com.alexbomber12.memtag.data.settings.PreferencesSettingsStore
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.integrations.memento.FakeMementoClient
import com.alexbomber12.memtag.integrations.memento.MementoClient
import com.alexbomber12.memtag.integrations.scan2d.FakeScan2dService
import com.alexbomber12.memtag.integrations.scan2d.Scan2dService
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfReaderProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { File(applicationContext.filesDir, "app_settings.preferences_pb") },
        )

    val settingsStore: SettingsStore = PreferencesSettingsStore(dataStore)
    val logger: Logger = AndroidLogger()

    val mementoClient: MementoClient = FakeMementoClient()
    val uhfReader: UhfReader = UhfReaderProvider.create(applicationContext)
    val scan2dService: Scan2dService = FakeScan2dService()
}
