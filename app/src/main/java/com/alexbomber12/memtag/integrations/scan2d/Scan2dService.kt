package com.alexbomber12.memtag.integrations.scan2d

interface Scan2dService {
    fun startScan()
}

class FakeScan2dService : Scan2dService {
    override fun startScan() {
        throw NotImplementedError("2D scanning is not implemented in PR-01.")
    }
}
