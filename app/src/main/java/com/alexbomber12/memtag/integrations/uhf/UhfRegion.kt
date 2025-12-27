package com.alexbomber12.memtag.integrations.uhf

enum class UhfRegion(val settingsValue: String) {
    EU("EU"),
    US("US"),
    JP("JP"),
    CN("CN"),
    OTHER("OTHER"),
    ;

    companion object {
        const val MODE_CN: Int = 0x01
        const val MODE_US: Int = 0x02
        const val MODE_EU: Int = 0x04
        const val MODE_JP: Int = 0x05
        const val LEGACY_MODE_CN: Int = 0
        const val LEGACY_MODE_US: Int = 1
        const val LEGACY_MODE_EU: Int = 2
        const val LEGACY_MODE_JP: Int = 4

        fun fromSettings(value: String): UhfRegion {
            return values().firstOrNull { it.settingsValue.equals(value, ignoreCase = true) } ?: OTHER
        }

        fun fromFrequencyMode(mode: Int): UhfRegion {
            return when (mode) {
                MODE_EU -> EU
                MODE_US -> US
                MODE_JP -> JP
                MODE_CN -> CN
                else -> OTHER
            }
        }

        fun toFrequencyMode(region: UhfRegion): Int {
            return when (region) {
                EU -> MODE_EU
                US -> MODE_US
                JP -> MODE_JP
                CN -> MODE_CN
                OTHER -> MODE_EU
            }
        }

        fun isKnownFrequencyMode(mode: Int): Boolean {
            return mode == MODE_EU || mode == MODE_US || mode == MODE_JP || mode == MODE_CN
        }

        fun migrateLegacyMode(
            region: UhfRegion,
            mode: Int,
        ): Int? {
            return when (region) {
                CN -> if (mode == LEGACY_MODE_CN) MODE_CN else null
                US -> if (mode == LEGACY_MODE_US) MODE_US else null
                EU -> if (mode == LEGACY_MODE_EU) MODE_EU else null
                JP -> if (mode == LEGACY_MODE_JP) MODE_JP else null
                OTHER -> null
            }
        }
    }

    fun toFrequencyMode(): Int = toFrequencyMode(this)
}
