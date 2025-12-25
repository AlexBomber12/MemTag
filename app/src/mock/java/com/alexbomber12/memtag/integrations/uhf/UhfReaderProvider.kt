package com.alexbomber12.memtag.integrations.uhf

import android.content.Context

object UhfReaderProvider {
    fun create(context: Context): UhfReader = FakeUhfReader()
}
