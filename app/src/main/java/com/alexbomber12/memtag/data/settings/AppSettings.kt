package com.alexbomber12.memtag.data.settings

import com.alexbomber12.memtag.data.AppDefaults
import com.alexbomber12.memtag.util.epc.EpcNormalizer

data class AppSettings(
    val mementoBaseUrl: String = AppDefaults.MEMENTO_BASE_URL,
    val mementoToken: String = AppDefaults.MEMENTO_TOKEN,
    val mementoLibraryId: String = AppDefaults.MEMENTO_LIBRARY_ID,
    val uhfRegion: String = AppDefaults.UHF_REGION,
    val uhfPower: Int = AppDefaults.UHF_POWER,
    val uhfFrequencyMode: Int? = null,
    val scan2dAction: String = AppDefaults.SCAN2D_ACTION,
    val scan2dExtraKey: String = AppDefaults.SCAN2D_EXTRA_KEY,
    val findSoundEnabled: Boolean = AppDefaults.FIND_SOUND_ENABLED,
    val findHapticEnabled: Boolean = AppDefaults.FIND_HAPTIC_ENABLED,
    val showFindDebugOverlay: Boolean = AppDefaults.FIND_DEBUG_OVERLAY_ENABLED,
    val lastScannedEpc: String = AppDefaults.LAST_SCANNED_EPC,
    val lastFindTargetEpc: String = AppDefaults.LAST_FIND_TARGET_EPC,
    val rfidKeyCodes: String = AppDefaults.RFID_KEY_CODES,
    val scanKeyCodes: String = AppDefaults.SCAN_KEY_CODES,
    val showDiagnosticsTab: Boolean = AppDefaults.SHOW_DIAGNOSTICS_TAB,
) {
    fun sanitized(): AppSettings {
        val normalizedRegion =
            if (AppDefaults.UHF_REGIONS.contains(uhfRegion)) {
                uhfRegion
            } else {
                AppDefaults.UHF_REGION
            }
        val normalizedPower = uhfPower.coerceIn(AppDefaults.UHF_POWER_MIN, AppDefaults.UHF_POWER_MAX)
        val normalizedFrequencyMode = uhfFrequencyMode?.takeIf { it >= 0 }
        val normalizedLastScanned = runCatching { EpcNormalizer.normalize(lastScannedEpc) }.getOrNull().orEmpty()
        val normalizedFindTarget = runCatching { EpcNormalizer.normalize(lastFindTargetEpc) }.getOrNull().orEmpty()
        return copy(
            mementoBaseUrl = AppDefaults.normalizeBaseUrl(mementoBaseUrl),
            uhfRegion = normalizedRegion,
            uhfPower = normalizedPower,
            uhfFrequencyMode = normalizedFrequencyMode,
            lastScannedEpc = normalizedLastScanned,
            lastFindTargetEpc = normalizedFindTarget,
            rfidKeyCodes = rfidKeyCodes.trim(),
            scanKeyCodes = scanKeyCodes.trim(),
        )
    }

    fun rfidKeyCodeSet(): Set<Int> = parseKeyCodes(rfidKeyCodes)

    fun scanKeyCodeSet(): Set<Int> = parseKeyCodes(scanKeyCodes)
}

private fun parseKeyCodes(raw: String): Set<Int> {
    return raw
        .split(',')
        .mapNotNull { token -> token.trim().takeIf { it.isNotEmpty() }?.toIntOrNull() }
        .toSet()
}
