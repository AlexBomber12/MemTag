package com.alexbomber12.memtag.integrations.memento

sealed class MementoSettingsValidation {
    data class Valid(
        val config: MementoConfig,
    ) : MementoSettingsValidation()

    data class Error(
        val message: String,
    ) : MementoSettingsValidation()
}

object MementoSettingsValidator {
    fun validate(
        baseUrl: String,
        token: String,
        libraryId: String,
    ): MementoSettingsValidation {
        val trimmedBaseUrl = baseUrl.trim().trimEnd('/')
        if (trimmedBaseUrl.isBlank()) {
            return MementoSettingsValidation.Error("Memento base URL is missing.")
        }
        if (token.trim().isBlank()) {
            return MementoSettingsValidation.Error("Memento token is missing.")
        }
        if (libraryId.trim().isBlank()) {
            return MementoSettingsValidation.Error("Memento library ID is missing.")
        }
        return MementoSettingsValidation.Valid(
            MementoConfig(
                baseUrl = trimmedBaseUrl,
                token = token.trim(),
                libraryId = libraryId.trim(),
            ),
        )
    }
}
