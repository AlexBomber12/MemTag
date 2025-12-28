package com.alexbomber12.memtag.integrations.uhf

const val UHF_PROTOCOL_ISO_18000_6C = 0x00
const val UHF_RFLINK_DSB_ASK = 0
const val UHF_PROTOCOL_UNSUPPORTED = -1
const val UHF_CONFIG_BUSY = -2

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
) {
    val success: Boolean =
        (modeApplied == true || (modeApplied == null && setModeOk != false)) &&
            (powerApplied == true || (powerApplied == null && setPowerOk != false)) &&
            (rfLinkApplied == true || (rfLinkApplied == null && setRfLinkOk != false)) &&
            protocolApplied != false
}

fun UhfApplyResult.toErrorMessage(): String {
    val failures =
        buildList {
            if (modeApplied == false) {
                add("modeApplied=false")
            } else if (modeApplied == null && setModeOk == false) {
                add("setModeOk=false")
            }
            if (powerApplied == false) {
                add("powerApplied=false")
            } else if (powerApplied == null && setPowerOk == false) {
                add("setPowerOk=false")
            }
            if (protocolApplied == false) {
                add("protocolApplied=false")
            }
            if (rfLinkApplied == false) {
                add("rfLinkApplied=false")
            } else if (rfLinkApplied == null && setRfLinkOk == false) {
                add("setRfLinkOk=false")
            }
        }
    if (failures.isEmpty()) {
        return ""
    }
    return "UHF config verify failed (${failures.joinToString(" ")})."
}
