package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.GptSpeedMode
import io.github.mangi.eta.data.model.isGptSpeedModel
import org.json.JSONObject

/** Apply only to final OpenAI text request bodies, after custom-body/model merging. */
internal object GptServiceTier {
    fun apply(request: JSONObject, config: AgentModelClient.ModelConfig) {
        val mode = config.gptSpeedMode ?: return // Preserve legacy/manual service_tier settings.
        val actualModel = request.opt("model") as? String ?: return
        if (!isGptSpeedModel(actualModel)) return
        request.put("service_tier", when (mode) {
            GptSpeedMode.NORMAL -> "default"
            GptSpeedMode.FAST -> "fast"
            GptSpeedMode.ULTRA_FAST -> "ultrafast"
        })
        // Do not change reasoning/model or silently retry with a different tier on API errors.
    }
}
