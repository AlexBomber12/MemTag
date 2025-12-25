package com.alexbomber12.memtag.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.alexbomber12.memtag.data.AppDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createStore(
        scope: CoroutineScope,
        file: File,
    ): SettingsStore {
        val dataStore =
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { file },
            )
        return PreferencesSettingsStore(dataStore)
    }

    @Test
    fun defaultsAreCorrect() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val file = tempFolder.newFile("defaults.preferences_pb")
            val store = createStore(scope, file)

            val settings = store.settingsFlow.first()

            assertEquals(AppDefaults.MEMENTO_BASE_URL, settings.mementoBaseUrl)
            assertEquals(AppDefaults.MEMENTO_TOKEN, settings.mementoToken)
            assertEquals(AppDefaults.MEMENTO_LIBRARY_ID, settings.mementoLibraryId)
            assertEquals(AppDefaults.UHF_REGION, settings.uhfRegion)
            assertEquals(AppDefaults.UHF_POWER, settings.uhfPower)
            assertEquals(AppDefaults.SCAN2D_ACTION, settings.scan2dAction)
            assertEquals(AppDefaults.SCAN2D_EXTRA_KEY, settings.scan2dExtraKey)
            assertEquals(AppDefaults.FIND_SOUND_ENABLED, settings.findSoundEnabled)
            assertEquals(AppDefaults.FIND_HAPTIC_ENABLED, settings.findHapticEnabled)
            assertEquals(AppDefaults.LAST_LOOKUP_EPC, settings.lastLookupEpc)
        }

    @Test
    fun savesAndLoadsSettings() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            val file = tempFolder.newFile("updates.preferences_pb")
            val store = createStore(scope, file)

            store.setMemento("https://example.com/", "token12345678", "lib-01")
            store.setUhf("US", 25)
            store.setScan2d("com.example.SCAN", "payload")
            store.update {
                it.copy(
                    findSoundEnabled = true,
                    findHapticEnabled = true,
                    lastLookupEpc = "E2000017221101441890ABCD",
                )
            }

            val settings =
                store.settingsFlow.first {
                    it.mementoLibraryId == "lib-01" && it.scan2dExtraKey == "payload"
                }

            assertEquals("https://example.com", settings.mementoBaseUrl)
            assertEquals("token12345678", settings.mementoToken)
            assertEquals("lib-01", settings.mementoLibraryId)
            assertEquals("US", settings.uhfRegion)
            assertEquals(25, settings.uhfPower)
            assertEquals("com.example.SCAN", settings.scan2dAction)
            assertEquals("payload", settings.scan2dExtraKey)
            assertEquals(true, settings.findSoundEnabled)
            assertEquals(true, settings.findHapticEnabled)
            assertEquals("E2000017221101441890ABCD", settings.lastLookupEpc)
        }
}
