package com.alexbomber12.memtag.domain

sealed class LookupResult {
    data class Found(
        val item: InventoryItem,
    ) : LookupResult()

    data object NotFound : LookupResult()

    data class Error(
        val message: String,
    ) : LookupResult()
}
