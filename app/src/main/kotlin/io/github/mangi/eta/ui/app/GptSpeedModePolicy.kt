package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.isGptSpeedModel
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.supportsGptSpeedBinding

/** Transient conversation preference, separate from reasoning depth and model credentials. */
internal object GptSpeedModePolicy {
    fun forBinding(current: GptSpeedMode, modelId: String, bindingAvailable: Boolean = true): GptSpeedMode =
        if (bindingAvailable && isGptSpeedModel(modelId)) current else GptSpeedMode.NORMAL

    fun cycle(current: GptSpeedMode, modelId: String, bindingAvailable: Boolean = true): GptSpeedMode =
        if (bindingAvailable && isGptSpeedModel(modelId)) current.next() else GptSpeedMode.NORMAL

    fun forSelection(
        current: GptSpeedMode,
        providerId: String,
        modelId: String,
        providers: List<ProviderSetting>,
    ): GptSpeedMode {
        val provider = providers.firstOrNull { it.id == providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == modelId && it.isEnabled }
        return forBinding(current, model?.modelId.orEmpty(), supportsGptSpeedBinding(provider, model))
    }

    fun snapshot(
        config: AgentModelClient.ModelConfig,
        mode: GptSpeedMode,
        bindingAvailable: Boolean = true,
    ): AgentModelClient.ModelConfig = config.copy(
        gptSpeedMode = mode.takeIf {
            bindingAvailable && isGptSpeedModel(config.model)
        },
    )
}
