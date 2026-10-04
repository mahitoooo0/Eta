package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

/** Requested service tier, not a guarantee of provider support or latency. */
@Serializable
enum class GptSpeedMode {
    NORMAL,
    FAST,
    ULTRA_FAST;

    fun next(): GptSpeedMode = when (this) {
        NORMAL -> FAST
        FAST -> ULTRA_FAST
        ULTRA_FAST -> NORMAL
    }
}

// Match actual model IDs (including optional namespaces), never provider/display names.
fun isGptSpeedModel(modelId: String): Boolean =
    modelId.trim().substringAfterLast('/').startsWith("gpt-", ignoreCase = true)

/** Only the selected model decides speed eligibility; availability is still checked. */
internal fun supportsGptSpeedBinding(provider: ProviderSetting?, model: Model?): Boolean =
    provider != null && provider.isEnabled && model != null && model.isEnabled &&
        isGptSpeedModel(model.modelId)
