package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentLoop
import io.github.mangi.eta.agent.model.AgentContextCompactor
import io.github.mangi.eta.agent.model.AgentCompressionEndpoint
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.AssistantRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository

/** Resolve the same model/window/endpoint settings for parent and child requests. */
internal object AgentCompressionPolicy {
    suspend fun resolve(config: AgentModelClient.ModelConfig, child: Boolean = false): AgentLoop.CompactPolicy {
        val preference = Prefs.localAgentPreferences()?.takeIf {
            it.contains(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED)
        }?.getBoolean(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED, false)
        return create(config, resolveCompressModelConfig(config) ?: config,
            if (child) preference else Prefs.isEnabled(Prefs.Keys.AGENT_AUTO_COMPRESS_ENABLED), child)
    }

    internal fun create(config: AgentModelClient.ModelConfig, summary: AgentModelClient.ModelConfig,
                        preference: Boolean?, child: Boolean): AgentLoop.CompactPolicy {
        val configuredWindow = AgentContextCompactor.configuredContextWindow(config.contextWindow)
        val summaryWindow = summary.contextWindow?.takeIf { it > 0 } ?: configuredWindow
        val effectiveSummary = if (summaryWindow == null || summaryWindow == summary.contextWindow) summary
            else summary.copy(contextWindow = summaryWindow)
        return AgentLoop.CompactPolicy(
            enabled = AgentContextCompactor.autoCompressEnabled(preference ?: child, config.contextWindow),
            contextWindow = configuredWindow ?: AgentLoop.CompactPolicy.Disabled.contextWindow,
            keepRecentMessages = 0,
            compressModelConfig = effectiveSummary.copy(errorReconnectPolicy = config.errorReconnectPolicy),
        )
    }

    private suspend fun resolveCompressModelConfig(
        fallback: AgentModelClient.ModelConfig,
    ): AgentModelClient.ModelConfig? {
        val prefs = Prefs.localAgentPreferences()
        val customEnabled = Prefs.isCustomCompressModelEnabled(prefs)
        val providerId = prefs?.takeIf { customEnabled }
            ?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_PROVIDER_ID, null)
        val modelId = prefs?.takeIf { customEnabled }
            ?.getString(Prefs.Keys.AGENT_COMPRESS_MODEL_ID, null)
        val resolved = if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) {
            fallback
        } else {
            val assistant = fallback.assistantId.takeIf { it.isNotBlank() }?.let {
                runCatching { AssistantRepository.currentProfile(it) }.getOrNull()
            }
            runCatching {
                RuntimeConfigRepository.configForProviderAndModel(providerId, modelId, assistant)
            }.getOrNull()?.copy(assistantId = fallback.assistantId, systemPrompt = fallback.systemPrompt,
                errorReconnectPolicy = fallback.errorReconnectPolicy)
                ?: fallback
        }
        val compressed = AgentRuntimePolicy.forCompression(resolved)
        return AgentCompressionEndpoint.apply(
            compressed,
            prefs?.getString(Prefs.Keys.AGENT_COMPRESS_ENDPOINT_MODE, null),
        )
    }
}
