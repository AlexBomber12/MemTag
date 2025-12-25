package com.alexbomber12.memtag.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.alexbomber12.memtag.core.logging.AndroidLogger
import com.alexbomber12.memtag.core.logging.Logger
import com.alexbomber12.memtag.data.repository.DefaultMementoRepository
import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.data.settings.PreferencesSettingsStore
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.db.MIGRATION_1_2
import com.alexbomber12.memtag.db.MemTagDatabase
import com.alexbomber12.memtag.domain.LookupByEpcUseCase
import com.alexbomber12.memtag.domain.SyncMementoLibraryUseCase
import com.alexbomber12.memtag.integrations.feedback.DeviceFindFeedbackController
import com.alexbomber12.memtag.integrations.feedback.FindFeedbackController
import com.alexbomber12.memtag.integrations.memento.MementoClient
import com.alexbomber12.memtag.integrations.memento.MementoCloudClient
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

    val database: MemTagDatabase =
        Room.databaseBuilder(applicationContext, MemTagDatabase::class.java, "memtag.db")
            .addMigrations(MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
    val actionsLogDao = database.actionsLogDao()

    val mementoClient: MementoClient = MementoCloudClient(logger)
    val mementoRepository: MementoRepository =
        DefaultMementoRepository(
            settingsStore = settingsStore,
            database = database,
            inventoryItemDao = database.inventoryItemDao(),
            syncStateDao = database.syncStateDao(),
            mementoClient = mementoClient,
            logger = logger,
        )
    val syncMementoLibraryUseCase = SyncMementoLibraryUseCase(mementoRepository)
    val lookupByEpcUseCase = LookupByEpcUseCase(mementoRepository)
    val uhfReader: UhfReader = UhfReaderProvider.create(applicationContext)
    val scan2dService: Scan2dService = FakeScan2dService()
    val findFeedbackController: FindFeedbackController = DeviceFindFeedbackController(applicationContext)
}
