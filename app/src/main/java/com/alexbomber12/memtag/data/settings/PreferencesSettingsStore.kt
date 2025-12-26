package com.alexbomber12.memtag.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.alexbomber12.memtag.data.AppDefaults
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class PreferencesSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    override val settingsFlow: Flow<AppSettings> =
        dataStore.data
            .catch { exception ->
                if (exception is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw exception
                }
            }
            .map { preferences ->
                preferences.toAppSettings()
            }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val current = preferences.toAppSettings()
            val updated = transform(current).sanitized()
            preferences[Keys.MEMENTO_BASE_URL] = updated.mementoBaseUrl
            preferences[Keys.MEMENTO_TOKEN] = updated.mementoToken
            preferences[Keys.MEMENTO_LIBRARY_ID] = updated.mementoLibraryId
            preferences[Keys.UHF_REGION] = updated.uhfRegion
            preferences[Keys.UHF_POWER] = updated.uhfPower
            preferences[Keys.SCAN2D_ACTION] = updated.scan2dAction
            preferences[Keys.SCAN2D_EXTRA_KEY] = updated.scan2dExtraKey
            preferences[Keys.FIND_SOUND_ENABLED] = updated.findSoundEnabled
            preferences[Keys.FIND_HAPTIC_ENABLED] = updated.findHapticEnabled
            preferences[Keys.LAST_SCANNED_EPC] = updated.lastScannedEpc
            preferences[Keys.LAST_FIND_TARGET_EPC] = updated.lastFindTargetEpc
            preferences[Keys.RFID_KEY_CODES] = updated.rfidKeyCodes
            preferences[Keys.SCAN_KEY_CODES] = updated.scanKeyCodes
        }
    }

    override suspend fun setMemento(
        baseUrl: String,
        token: String,
        libraryId: String,
    ) {
        dataStore.edit { preferences ->
            preferences[Keys.MEMENTO_BASE_URL] = AppDefaults.normalizeBaseUrl(baseUrl)
            preferences[Keys.MEMENTO_TOKEN] = token
            preferences[Keys.MEMENTO_LIBRARY_ID] = libraryId
        }
    }

    override suspend fun setUhf(
        region: String,
        power: Int,
    ) {
        val normalizedRegion =
            if (AppDefaults.UHF_REGIONS.contains(region)) {
                region
            } else {
                AppDefaults.UHF_REGION
            }
        val normalizedPower = power.coerceIn(AppDefaults.UHF_POWER_MIN, AppDefaults.UHF_POWER_MAX)
        dataStore.edit { preferences ->
            preferences[Keys.UHF_REGION] = normalizedRegion
            preferences[Keys.UHF_POWER] = normalizedPower
        }
    }

    override suspend fun setScan2d(
        action: String,
        extraKey: String,
    ) {
        dataStore.edit { preferences ->
            preferences[Keys.SCAN2D_ACTION] = action
            preferences[Keys.SCAN2D_EXTRA_KEY] = extraKey
        }
    }

    private fun Preferences.toAppSettings(): AppSettings {
        return AppSettings(
            mementoBaseUrl = this[Keys.MEMENTO_BASE_URL] ?: AppDefaults.MEMENTO_BASE_URL,
            mementoToken = this[Keys.MEMENTO_TOKEN] ?: AppDefaults.MEMENTO_TOKEN,
            mementoLibraryId = this[Keys.MEMENTO_LIBRARY_ID] ?: AppDefaults.MEMENTO_LIBRARY_ID,
            uhfRegion = this[Keys.UHF_REGION] ?: AppDefaults.UHF_REGION,
            uhfPower = this[Keys.UHF_POWER] ?: AppDefaults.UHF_POWER,
            scan2dAction = this[Keys.SCAN2D_ACTION] ?: AppDefaults.SCAN2D_ACTION,
            scan2dExtraKey = this[Keys.SCAN2D_EXTRA_KEY] ?: AppDefaults.SCAN2D_EXTRA_KEY,
            findSoundEnabled = this[Keys.FIND_SOUND_ENABLED] ?: AppDefaults.FIND_SOUND_ENABLED,
            findHapticEnabled = this[Keys.FIND_HAPTIC_ENABLED] ?: AppDefaults.FIND_HAPTIC_ENABLED,
            lastScannedEpc =
                this[Keys.LAST_SCANNED_EPC]
                    ?: this[Keys.LEGACY_LAST_LOOKUP_EPC]
                    ?: AppDefaults.LAST_SCANNED_EPC,
            lastFindTargetEpc = this[Keys.LAST_FIND_TARGET_EPC] ?: AppDefaults.LAST_FIND_TARGET_EPC,
            rfidKeyCodes = this[Keys.RFID_KEY_CODES] ?: AppDefaults.RFID_KEY_CODES,
            scanKeyCodes = this[Keys.SCAN_KEY_CODES] ?: AppDefaults.SCAN_KEY_CODES,
        ).sanitized()
    }

    private object Keys {
        val MEMENTO_BASE_URL = stringPreferencesKey("memento_base_url")
        val MEMENTO_TOKEN = stringPreferencesKey("memento_token")
        val MEMENTO_LIBRARY_ID = stringPreferencesKey("memento_library_id")
        val UHF_REGION = stringPreferencesKey("uhf_region")
        val UHF_POWER = intPreferencesKey("uhf_power")
        val SCAN2D_ACTION = stringPreferencesKey("scan2d_action")
        val SCAN2D_EXTRA_KEY = stringPreferencesKey("scan2d_extra_key")
        val FIND_SOUND_ENABLED = booleanPreferencesKey("find_sound_enabled")
        val FIND_HAPTIC_ENABLED = booleanPreferencesKey("find_haptic_enabled")
        val LAST_SCANNED_EPC = stringPreferencesKey("last_scanned_epc")
        val LEGACY_LAST_LOOKUP_EPC = stringPreferencesKey("last_lookup_epc")
        val LAST_FIND_TARGET_EPC = stringPreferencesKey("last_find_target_epc")
        val RFID_KEY_CODES = stringPreferencesKey("rfid_key_codes")
        val SCAN_KEY_CODES = stringPreferencesKey("scan_key_codes")
    }
}
