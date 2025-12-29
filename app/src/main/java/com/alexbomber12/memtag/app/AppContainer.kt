package com.alexbomber12.memtag.app

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import com.alexbomber12.memtag.core.logging.AndroidLogger
import com.alexbomber12.memtag.core.logging.Logger
import com.alexbomber12.memtag.data.batch.BatchRepository
import com.alexbomber12.memtag.data.batch.DefaultBatchRepository
import com.alexbomber12.memtag.data.repository.DefaultMementoRepository
import com.alexbomber12.memtag.data.repository.MementoRepository
import com.alexbomber12.memtag.data.settings.PreferencesSettingsStore
import com.alexbomber12.memtag.data.settings.SettingsStore
import com.alexbomber12.memtag.db.MIGRATION_1_2
import com.alexbomber12.memtag.db.MIGRATION_2_3
import com.alexbomber12.memtag.db.MIGRATION_3_4
import com.alexbomber12.memtag.db.MemTagDatabase
import com.alexbomber12.memtag.domain.SyncMementoLibraryUseCase
import com.alexbomber12.memtag.integrations.feedback.DeviceFindFeedbackController
import com.alexbomber12.memtag.integrations.feedback.FindFeedbackController
import com.alexbomber12.memtag.integrations.memento.MementoClient
import com.alexbomber12.memtag.integrations.memento.MementoCloudClient
import com.alexbomber12.memtag.integrations.scan2d.Scan2dScanner
import com.alexbomber12.memtag.integrations.scan2d.Scan2dScannerProvider
import com.alexbomber12.memtag.integrations.uhf.UhfReader
import com.alexbomber12.memtag.integrations.uhf.UhfReaderProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore =
        PreferenceDataStoreFactory.create(
            scope = appScope,
            produceFile = { File(applicationContext.filesDir, "app_settings.preferences_pb") },
        )

    val settingsStore: SettingsStore = PreferencesSettingsStore(dataStore)
    val logger: Logger = AndroidLogger()

    val database: MemTagDatabase =
        Room.databaseBuilder(applicationContext, MemTagDatabase::class.java, "memtag.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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
    val batchRepository: BatchRepository =
        DefaultBatchRepository(
            database = database,
            batchDao = database.batchDao(),
            batchMetaDao = database.batchMetaDao(),
        )
    val syncMementoLibraryUseCase = SyncMementoLibraryUseCase(mementoRepository)
    val syncCoordinator = SyncCoordinator(settingsStore, mementoRepository, syncMementoLibraryUseCase, appScope)
    val uhfReader: UhfReader = UhfReaderProvider.create(applicationContext, settingsStore)
    val scan2dScanner: Scan2dScanner = Scan2dScannerProvider.create(applicationContext, settingsStore)
    val findFeedbackController: FindFeedbackController = DeviceFindFeedbackController(applicationContext)
    val hardwareKeyDispatcher = HardwareKeyDispatcher()
    val sessionFlagsStore = SessionFlagsStore()
}
