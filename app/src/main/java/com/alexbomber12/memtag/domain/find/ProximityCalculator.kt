package com.alexbomber12.memtag.domain.find

import com.alexbomber12.memtag.integrations.uhf.TagReading
import kotlin.math.pow
import kotlin.math.roundToInt

data class ProximitySnapshot(
    val proximity: Int,
    val rssi: Int?,
    val hitsPerWindow: Int,
    val rawScore: Float,
    val smoothedScore: Float,
    val seenRecently: Boolean,
    val lastSeenAt: Long?,
)

class ProximityCalculator(
    private val targetEpc: String,
    private val config: Config = Config(),
) {
    data class Config(
        val windowMs: Long = 500L,
        val hitsMax: Int = 10,
        val rssiMin: Int = -80,
        val rssiMax: Int = -35,
        val alpha: Float = 0.2f,
        val noSignalMs: Long = 700L,
        val decayPerSecond: Float = 0.6f,
    )

    private val hitTimestamps = ArrayDeque<Long>()
    private var lastRssi: Int? = null
    private var lastSeenAt: Long? = null
    private var smoothedScore = 0f
    private var rawScore = 0f
    private var lastUpdateMs: Long? = null

    fun reset() {
        hitTimestamps.clear()
        lastRssi = null
        lastSeenAt = null
        smoothedScore = 0f
        rawScore = 0f
        lastUpdateMs = null
    }

    fun onReading(reading: TagReading): ProximitySnapshot? {
        if (!reading.epcHex.equals(targetEpc, ignoreCase = true)) {
            return null
        }
        val nowMs = reading.timestampMs
        hitTimestamps.addLast(nowMs)
        lastSeenAt = nowMs
        reading.rssi?.let { lastRssi = normalizeRssi(it) }
        return buildSnapshot(nowMs)
    }

    fun onTick(nowMs: Long): ProximitySnapshot {
        return buildSnapshot(nowMs)
    }

    private fun buildSnapshot(nowMs: Long): ProximitySnapshot {
        pruneHits(nowMs)
        val hits = hitTimestamps.size
        val rssiScore = lastRssi?.let(::mapRssiToScore) ?: 0f
        val hitScore = (hits.toFloat() / config.hitsMax.coerceAtLeast(1)).coerceIn(0f, 1f)
        rawScore = (0.7f * rssiScore) + (0.3f * hitScore)
        smoothedScore =
            if (lastUpdateMs == null) {
                rawScore
            } else {
                (config.alpha * rawScore) + ((1f - config.alpha) * smoothedScore)
            }
        smoothedScore = applyDecay(nowMs, smoothedScore).coerceIn(0f, 1f)
        lastUpdateMs = nowMs
        val proximity = (smoothedScore * 100f).roundToInt().coerceIn(0, 100)
        val seenRecently = lastSeenAt?.let { nowMs - it <= config.noSignalMs } ?: false
        return ProximitySnapshot(
            proximity = proximity,
            rssi = lastRssi,
            hitsPerWindow = hits,
            rawScore = rawScore,
            smoothedScore = smoothedScore,
            seenRecently = seenRecently,
            lastSeenAt = lastSeenAt,
        )
    }

    private fun pruneHits(nowMs: Long) {
        while (hitTimestamps.isNotEmpty() && nowMs - hitTimestamps.first() > config.windowMs) {
            hitTimestamps.removeFirst()
        }
    }

    private fun mapRssiToScore(rssi: Int): Float {
        val clamped = rssi.coerceIn(config.rssiMin, config.rssiMax)
        return ((clamped - config.rssiMin).toFloat() / (config.rssiMax - config.rssiMin).toFloat())
            .coerceIn(0f, 1f)
    }

    private fun applyDecay(
        nowMs: Long,
        value: Float,
    ): Float {
        val lastSeen = lastSeenAt ?: return value
        if (nowMs - lastSeen <= config.noSignalMs) {
            return value
        }
        val previousUpdate = lastUpdateMs ?: return value
        val deltaMs = (nowMs - previousUpdate).coerceAtLeast(0L)
        if (deltaMs == 0L) {
            return value
        }
        val decayFactor = config.decayPerSecond.pow(deltaMs / 1000f)
        return value * decayFactor
    }

    private fun normalizeRssi(rssi: Int): Int {
        // Some SDKs return positive RSSI values; treat them as negative dBm magnitudes.
        return if (rssi > 0) -rssi else rssi
    }
}
