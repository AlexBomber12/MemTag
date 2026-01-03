package com.alexbomber12.memtag.integrations.uhf

import kotlin.math.abs

const val UHF_PROTOCOL_ISO_18000_6C = 0x00
const val UHF_RFLINK_DSB_ASK = 0
const val UHF_PROTOCOL_UNSUPPORTED = -1
const val UHF_CONFIG_BUSY = -2
internal const val UHF_POWER_SCALE_FACTOR = 100
internal const val UHF_POWER_TOLERANCE_DBM = 1

enum class ProtocolSupport {
    Unknown,
    Supported,
    Unsupported,
}

data class ProtocolAttempt(
    val ok: Boolean,
    val errorCode: Int? = null,
)

data class UhfDesiredConfig(
    val region: UhfRegion,
    val powerDbm: Int,
    val protocol: Int = UHF_PROTOCOL_ISO_18000_6C,
    val rfLink: Int = UHF_RFLINK_DSB_ASK,
) {
    val frequencyMode: Int = UhfRegion.toFrequencyMode(region)
}

data class UhfConfig(
    val frequencyMode: Int,
    val power: Int,
    val protocol: Int,
    val rfLink: Int,
)

data class UhfApplyResult(
    val reason: String,
    val beforeMode: Int?,
    val beforePower: Int?,
    val beforeProtocol: Int?,
    val beforeRfLink: Int?,
    val desiredMode: Int,
    val desiredPower: Int,
    val desiredProtocol: Int,
    val desiredRfLink: Int,
    val setModeOk: Boolean?,
    val setPowerOk: Boolean?,
    val setProtocolOk: Boolean?,
    val setRfLinkOk: Boolean?,
    val afterMode: Int?,
    val afterPower: Int?,
    val afterProtocol: Int?,
    val afterRfLink: Int?,
    val protocolSupport: ProtocolSupport,
    val protocolAttempt: ProtocolAttempt?,
    val modeApplied: Boolean?,
    val powerApplied: Boolean?,
    val protocolApplied: Boolean?,
    val rfLinkApplied: Boolean?,
    val recoveryAttempted: Boolean = false,
) {
    val success: Boolean =
        (modeApplied == true || (modeApplied == null && setModeOk != false)) &&
            powerApplied != false &&
            (rfLinkApplied == true || (rfLinkApplied == null && setRfLinkOk != false)) &&
            protocolApplied != false
}

fun UhfApplyResult.toErrorMessage(): String {
    val failures = mutableListOf<String>()
    if (modeApplied == false) {
        failures.add("modeApplied=false")
    } else if (modeApplied == null && setModeOk == false) {
        failures.add("setModeOk=false")
    }
    if (protocolApplied == false) {
        failures.add("protocolApplied=false")
    }
    if (rfLinkApplied == false) {
        failures.add("rfLinkApplied=false")
    } else if (rfLinkApplied == null && setRfLinkOk == false) {
        failures.add("setRfLinkOk=false")
    }
    if (failures.isEmpty()) {
        return ""
    }
    val powerAppliedLabel =
        when (powerApplied) {
            true -> "true"
            false -> "false"
            null -> "null"
        }
    failures.add("powerApplied=$powerAppliedLabel")
    failures.add("recoveryAttempted=$recoveryAttempted")
    return "UHF config verify failed (${failures.joinToString(" ")})."
}

internal fun resolvePowerApplied(
    desiredDbm: Int,
    readback: Int?,
    scaleFactor: Int = UHF_POWER_SCALE_FACTOR,
    toleranceDbm: Int = UHF_POWER_TOLERANCE_DBM,
): Boolean? {
    if (readback == null) {
        return null
    }
    val unscaledMatch = abs(readback - desiredDbm) <= toleranceDbm
    val scaledMatch = abs(readback - desiredDbm * scaleFactor) <= toleranceDbm * scaleFactor
    return unscaledMatch || scaledMatch
}

@Suppress("UNUSED_PARAMETER")
internal fun resolvePowerAppliedOrUnverified(
    desiredDbm: Int,
    readback: Int?,
    setPowerOk: Boolean?,
    scaleFactor: Int = UHF_POWER_SCALE_FACTOR,
    toleranceDbm: Int = UHF_POWER_TOLERANCE_DBM,
): Boolean? {
    if (readback == null) {
        return null
    }
    return resolvePowerApplied(desiredDbm, readback, scaleFactor, toleranceDbm)
}
