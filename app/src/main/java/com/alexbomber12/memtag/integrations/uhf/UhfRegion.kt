package com.alexbomber12.memtag.integrations.uhf

enum class UhfRegion(val settingsValue: String) {
    EU("EU"),
    US("US"),
    JP("JP"),
    CN("CN"),
    OTHER("OTHER"),
    ;

    companion object {
        fun fromSettings(value: String): UhfRegion {
            return values().firstOrNull { it.settingsValue.equals(value, ignoreCase = true) } ?: OTHER
        }
    }
}
