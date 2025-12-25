package com.alexbomber12.memtag.data.settings

import com.alexbomber12.memtag.data.AppDefaults

data class AppSettings(
    val mementoBaseUrl: String = AppDefaults.MEMENTO_BASE_URL,
    val mementoToken: String = AppDefaults.MEMENTO_TOKEN,
    val mementoLibraryId: String = AppDefaults.MEMENTO_LIBRARY_ID,
    val uhfRegion: String = AppDefaults.UHF_REGION,
    val uhfPower: Int = AppDefaults.UHF_POWER,
    val scan2dAction: String = AppDefaults.SCAN2D_ACTION,
    val scan2dExtraKey: String = AppDefaults.SCAN2D_EXTRA_KEY,
) {
    fun sanitized(): AppSettings {
        val normalizedRegion =
            if (AppDefaults.UHF_REGIONS.contains(uhfRegion)) {
                uhfRegion
            } else {
                AppDefaults.UHF_REGION
            }
        val normalizedPower = uhfPower.coerceIn(AppDefaults.UHF_POWER_MIN, AppDefaults.UHF_POWER_MAX)
        return copy(
            mementoBaseUrl = AppDefaults.normalizeBaseUrl(mementoBaseUrl),
            uhfRegion = normalizedRegion,
            uhfPower = normalizedPower,
        )
    }
}
