package com.alexbomber12.memtag.integrations.uhf

sealed class UhfError {
    data object NotInitialized : UhfError()

    data object HardwareUnavailable : UhfError()

    data object Timeout : UhfError()

    data object OperationInProgress : UhfError()

    data class VendorError(val message: String) : UhfError()
}

fun UhfError.asException(
    message: String? = null,
    cause: Throwable? = null,
): UhfException {
    val resolvedMessage =
        when (this) {
            is UhfError.VendorError -> this.message
            else -> message ?: this::class.simpleName.orEmpty()
        }
    return UhfException(error = this, message = resolvedMessage, cause = cause)
}
