package com.alexbomber12.memtag.integrations.uhf

import com.alexbomber12.memtag.data.settings.SettingsStore
import kotlinx.coroutines.flow.first

suspend fun SettingsStore.getDesiredUhfConfig(): UhfDesiredConfig {
    val settings = settingsFlow.first()
    val storedMode = settings.uhfFrequencyMode
    val region = UhfRegion.fromSettings(settings.uhfRegion)
    val regionForMode =
        if (storedMode != null && !UhfRegion.isKnownFrequencyMode(storedMode)) {
            UhfRegion.EU
        } else {
            region
        }
    val desired = UhfDesiredConfig(region = regionForMode, powerDbm = settings.uhfPower)
    val legacyMigrated = storedMode?.let { UhfRegion.migrateLegacyMode(regionForMode, it) }
    val resolvedMode =
        when {
            legacyMigrated != null -> legacyMigrated
            storedMode != null && UhfRegion.isKnownFrequencyMode(storedMode) && storedMode == desired.frequencyMode ->
                storedMode
            else -> desired.frequencyMode
        }
    if (storedMode == null || storedMode != resolvedMode || regionForMode != region) {
        update { current ->
            current.copy(
                uhfFrequencyMode = resolvedMode,
                uhfRegion = regionForMode.settingsValue,
            )
        }
    }
    return desired
}

suspend fun SettingsStore.setDesiredUhfConfig(config: UhfDesiredConfig) {
    val region = config.region
    update { current ->
        current.copy(
            uhfFrequencyMode = config.frequencyMode,
            uhfPower = config.powerDbm,
            uhfRegion = region.settingsValue,
        )
    }
}
