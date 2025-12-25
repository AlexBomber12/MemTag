package com.alexbomber12.memtag.data

object AppDefaults {
    const val MEMENTO_BASE_URL = "https://mementoserver-hrd.appspot.com/v1"
    const val MEMENTO_TOKEN = ""
    const val MEMENTO_LIBRARY_ID = ""

    const val UHF_REGION = "EU"
    const val UHF_POWER = 20
    const val UHF_POWER_MIN = 5
    const val UHF_POWER_MAX = 30
    val UHF_REGIONS = listOf("EU", "US", "JP", "CN")

    const val SCAN2D_ACTION = "com.alexbomber12.inventory.SCAN"
    const val SCAN2D_EXTRA_KEY = "data"

    fun normalizeBaseUrl(input: String): String {
        var trimmed = input.trim()
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.dropLast(1)
        }
        return trimmed
    }
}
