package com.alexbomber12.memtag.data.settings

import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.util.epc.EpcNormalizer

data class AppSettings(
    val mementoBaseUrl: String = AppDefaults.MEMENTO_BASE_URL,
    val mementoToken: String = AppDefaults.MEMENTO_TOKEN,
    val mementoLibraryId: String = AppDefaults.MEMENTO_LIBRARY_ID,
    val uhfRegion: String = AppDefaults.UHF_REGION,
    val uhfPower: Int = AppDefaults.UHF_POWER,
    val scan2dAction: String = AppDefaults.SCAN2D_ACTION,
    val scan2dExtraKey: String = AppDefaults.SCAN2D_EXTRA_KEY,
    val findSoundEnabled: Boolean = AppDefaults.FIND_SOUND_ENABLED,
    val findHapticEnabled: Boolean = AppDefaults.FIND_HAPTIC_ENABLED,
    val lastLookupEpc: String = AppDefaults.LAST_LOOKUP_EPC,
) {
    fun sanitized(): AppSettings {
        val normalizedRegion =
            if (AppDefaults.UHF_REGIONS.contains(uhfRegion)) {
                uhfRegion
            } else {
                AppDefaults.UHF_REGION
            }
        val normalizedPower = uhfPower.coerceIn(AppDefaults.UHF_POWER_MIN, AppDefaults.UHF_POWER_MAX)
        val normalizedLastEpc = runCatching { EpcNormalizer.normalize(lastLookupEpc) }.getOrNull().orEmpty()
        return copy(
            mementoBaseUrl = AppDefaults.normalizeBaseUrl(mementoBaseUrl),
            uhfRegion = normalizedRegion,
            uhfPower = normalizedPower,
            lastLookupEpc = normalizedLastEpc,
        )
    }
}
