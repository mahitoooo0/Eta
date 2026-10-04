package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val memoryEnabled: Boolean = true,
    val fileLoggingEnabled: Boolean = true,
    val appearance: AppearanceSettings = AppearanceSettings(),
    val errorReconnectPolicy: ErrorReconnectPolicy = ErrorReconnectPolicy.NONE,
)
