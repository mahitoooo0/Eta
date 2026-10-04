package io.github.mangi.eta.data.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorReconnectPolicyTest {
    @Test
    fun policiesUseStableValuesAndTotalRetryWindows() {
        val expected = listOf(
            Triple(ErrorReconnectPolicy.NONE, "none", 0L),
            Triple(ErrorReconnectPolicy.WINDOW_30S, "window_30s", 30_000L),
            Triple(ErrorReconnectPolicy.WINDOW_1M, "window_1m", 60_000L),
            Triple(ErrorReconnectPolicy.WINDOW_5M, "window_5m", 300_000L),
            Triple(ErrorReconnectPolicy.CONTINUOUS, "continuous", null),
        )
        assertEquals(expected.map { it.first }, ErrorReconnectPolicy.entries.toList())
        expected.forEach { (policy, persisted, window) ->
            assertEquals(persisted, policy.persistedValue)
            assertEquals(window, policy.windowMillis)
            assertEquals(policy, ErrorReconnectPolicy.fromPersistedValue(persisted))
        }
    }

    @Test
    fun missingAndUnknownValuesNeverEnableReconnect() {
        listOf(null, "", "unknown", "WINDOW_30S", "30", " continuous ").forEach { raw ->
            assertEquals(ErrorReconnectPolicy.NONE, ErrorReconnectPolicy.fromPersistedValue(raw))
        }
    }

    @Test
    fun oldSettingsAndUnknownSerializedPolicyUseSafeDefault() {
        assertEquals(ErrorReconnectPolicy.NONE, Json.decodeFromString<Settings>("{}").errorReconnectPolicy)
        assertEquals(
            ErrorReconnectPolicy.NONE,
            Json.decodeFromString<Settings>("""{"errorReconnectPolicy":"future_policy"}""").errorReconnectPolicy,
        )
    }

    @Test
    fun serializedSettingsRoundTripEveryPolicyUsingStableValues() {
        ErrorReconnectPolicy.entries.forEach { policy ->
            val settings = Settings(errorReconnectPolicy = policy)
            val encoded = Json { encodeDefaults = true }.encodeToString(settings)
            assertEquals(policy, Json.decodeFromString<Settings>(encoded).errorReconnectPolicy)
            assertEquals("\"${policy.persistedValue}\"", Json.encodeToString(policy))
        }
    }
}
