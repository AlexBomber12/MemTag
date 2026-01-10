package com.alexbomber12.memtag.domain.find

import com.alexbomber12.memtag.integrations.uhf.TagReading
import com.alexbomber12.memtag.integrations.uhf.UhfLogger
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
    val lastWakeUpAt: Long?,
    val lastWakeUpIdleMs: Long?,
)

class ProximityCalculator(
    private val targetEpc: String,
    private val config: Config = Config(),
    private val matchAll: Boolean = false,
) {
    data class Config(
        val windowMs: Long = 500L,
        val hitsMax: Int = 10,
        val rssiMin: Int = -65,
        val rssiMax: Int = -25,
        val rssiGamma: Float = 1.0f,
        val alpha: Float = 0.4f,
        val noSignalMs: Long = 700L,
        val decayPerSecond: Float = 0.8f,
        val wakeUpIdleMs: Long = 2000L,
        val wakeUpBoost: Float = 1.4f,
    )

    private val hitTimestamps = ArrayDeque<Long>()
    private var lastRssi: Int? = null
    private var lastHitAt: Long? = null
    private var lastNonZeroAt: Long? = null
    private var pendingWakeUpIdleMs: Long? = null
    private var lastWakeUpAt: Long? = null
    private var lastWakeUpIdleMs: Long? = null
    private var smoothedScore = 0f
    private var rawScore = 0f
    private var lastUpdateMs: Long? = null

    fun reset() {
        hitTimestamps.clear()
        lastRssi = null
        lastHitAt = null
        lastNonZeroAt = null
        pendingWakeUpIdleMs = null
        lastWakeUpAt = null
        lastWakeUpIdleMs = null
        smoothedScore = 0f
        rawScore = 0f
        lastUpdateMs = null
    }

    fun onReading(reading: TagReading): ProximitySnapshot? {
        if (!matchAll && !reading.epcHex.equals(targetEpc, ignoreCase = true)) {
            return null
        }
        val nowMs = reading.timestampMs
        val previousHit = lastHitAt
        if (previousHit != null) {
            val idleMs = nowMs - previousHit
            if (idleMs > config.wakeUpIdleMs) {
                pendingWakeUpIdleMs = idleMs
            }
        }
        hitTimestamps.addLast(nowMs)
        lastHitAt = nowMs
        reading.rssi?.let { lastRssi = normalizeRssi(it) }
        return buildSnapshot(nowMs, isReading = true)
    }

    fun onTick(nowMs: Long): ProximitySnapshot {
        return buildSnapshot(nowMs, isReading = false)
    }

    private fun buildSnapshot(
        nowMs: Long,
        isReading: Boolean,
    ): ProximitySnapshot {
        pruneHits(nowMs)
        val hits = hitTimestamps.size
        val rssiScore = lastRssi?.let(::mapRssiToScore) ?: 0f
        val hitScore = (hits.toFloat() / config.hitsMax.coerceAtLeast(1)).coerceIn(0f, 1f)
        val hitBonus = 0.15f * hitScore
        rawScore = (rssiScore + hitBonus).coerceIn(0f, 1f)
        if (isReading && rawScore > 0f) {
            lastNonZeroAt = nowMs
        }
        var nextSmoothed =
            if (lastUpdateMs == null) {
                rawScore
            } else {
                (config.alpha * rawScore) + ((1f - config.alpha) * smoothedScore)
            }
        if (isReading) {
            val idleMs = pendingWakeUpIdleMs
            if (idleMs != null) {
                val emaBefore = nextSmoothed
                val boosted = (rawScore * config.wakeUpBoost).coerceIn(0f, 1f)
                if (boosted > nextSmoothed) {
                    nextSmoothed = boosted
                }
                if (rawScore > nextSmoothed) {
                    nextSmoothed = rawScore
                }
                lastWakeUpAt = nowMs
                lastWakeUpIdleMs = idleMs
                UhfLogger.i(
                    "Proximity wake-up triggered: idleMs=$idleMs " +
                        "rawScore=$rawScore emaBefore=$emaBefore emaAfter=$nextSmoothed",
                )
            }
            pendingWakeUpIdleMs = null
        }
        nextSmoothed = applyDecay(nowMs, nextSmoothed).coerceIn(0f, 1f)
        smoothedScore = nextSmoothed
        lastUpdateMs = nowMs
        val proximity = (smoothedScore * 100f).roundToInt().coerceIn(0, 100)
        val seenRecently = lastHitAt?.let { nowMs - it <= config.noSignalMs } ?: false
        return ProximitySnapshot(
            proximity = proximity,
            rssi = lastRssi,
            hitsPerWindow = hits,
            rawScore = rawScore,
            smoothedScore = smoothedScore,
            seenRecently = seenRecently,
            lastSeenAt = lastHitAt,
            lastWakeUpAt = lastWakeUpAt,
            lastWakeUpIdleMs = lastWakeUpIdleMs,
        )
    }

    private fun pruneHits(nowMs: Long) {
        while (hitTimestamps.isNotEmpty() && nowMs - hitTimestamps.first() > config.windowMs) {
            hitTimestamps.removeFirst()
        }
    }

    private fun mapRssiToScore(rssi: Int): Float {
        val clamped = rssi.coerceIn(config.rssiMin, config.rssiMax)
        val normalized =
            ((clamped - config.rssiMin).toFloat() / (config.rssiMax - config.rssiMin).toFloat())
                .coerceIn(0f, 1f)
        return normalized.pow(config.rssiGamma)
    }

    private fun applyDecay(
        nowMs: Long,
        value: Float,
    ): Float {
        val lastSeen = lastHitAt ?: return value
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
