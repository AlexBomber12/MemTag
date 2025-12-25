package com.alexbomber12.memtag.integrations.scan2d

interface Scan2dScanner {
    suspend fun scanOnce(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Result<String>

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}

sealed class Scan2dError {
    data object HardwareUnavailable : Scan2dError()

    data object Timeout : Scan2dError()

    data object Cancelled : Scan2dError()

    data object OperationInProgress : Scan2dError()

    data class InvalidPayload(val message: String) : Scan2dError()

    data class VendorError(val message: String) : Scan2dError()
}

class Scan2dException(
    val error: Scan2dError,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

fun Scan2dError.asException(
    message: String? = null,
    cause: Throwable? = null,
): Scan2dException {
    val resolvedMessage =
        when (this) {
            is Scan2dError.InvalidPayload -> this.message
            is Scan2dError.VendorError -> this.message
            else -> message ?: this::class.simpleName.orEmpty()
        }
    return Scan2dException(error = this, message = resolvedMessage, cause = cause)
}
