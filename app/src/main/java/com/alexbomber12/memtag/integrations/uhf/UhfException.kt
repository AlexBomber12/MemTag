package com.alexbomber12.memtag.integrations.uhf

class UhfException(
    val error: UhfError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
