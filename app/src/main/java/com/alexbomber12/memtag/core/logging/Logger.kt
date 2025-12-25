package com.alexbomber12.memtag.core.logging

import android.util.Log

interface Logger {
    fun d(
        tag: String,
        msg: String,
    )

    fun i(
        tag: String,
        msg: String,
    )

    fun w(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    )

    fun e(
        tag: String,
        msg: String,
        tr: Throwable? = null,
    )
}

class AndroidLogger : Logger {
    override fun d(
        tag: String,
        msg: String,
    ) {
        Log.d(tag, msg)
    }

    override fun i(
        tag: String,
        msg: String,
    ) {
        Log.i(tag, msg)
    }

    override fun w(
        tag: String,
        msg: String,
        tr: Throwable?,
    ) {
        Log.w(tag, msg, tr)
    }

    override fun e(
        tag: String,
        msg: String,
        tr: Throwable?,
    ) {
        Log.e(tag, msg, tr)
    }
}

fun safeRedact(
    value: String?,
    visiblePrefix: Int = 4,
    visibleSuffix: Int = 4,
): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) {
        return ""
    }
    if (trimmed.length <= visiblePrefix + visibleSuffix) {
        return "***"
    }
    return trimmed.take(visiblePrefix) + "***" + trimmed.takeLast(visibleSuffix)
}
