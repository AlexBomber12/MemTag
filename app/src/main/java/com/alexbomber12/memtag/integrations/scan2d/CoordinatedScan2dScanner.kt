package com.alexbomber12.memtag.integrations.scan2d

import com.alexbomber12.memtag.integrations.uhf.UhfReader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class CoordinatedScan2dScanner(
    private val delegate: Scan2dScanner,
    private val uhfReader: UhfReader,
    private val ioDispatcher: CoroutineDispatcher,
) : Scan2dScanner {
    override suspend fun scanOnce(timeoutMs: Long): Result<String> =
        withContext(ioDispatcher) {
            runCatching { uhfReader.stopInventory() }
            runCatching { uhfReader.close() }
            delegate.scanOnce(timeoutMs)
        }
}
