package com.alexbomber12.memtag.integrations.scan2d

class FakeScan2dScanner : Scan2dScanner {
    override suspend fun scanOnce(
        timeoutMs: Long,
        source: String,
    ): Result<String> {
        return Result.success(FAKE_EPC)
    }

    companion object {
        const val FAKE_EPC = "E2000017221101441890ABCD"
    }
}
