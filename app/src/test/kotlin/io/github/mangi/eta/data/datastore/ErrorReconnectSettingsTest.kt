package io.github.mangi.eta.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.data.model.ErrorReconnectPolicy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class ErrorReconnectSettingsTest {
    @Test
    fun setterAndLegacyUpdatesPreserveAllSettingsFields() = runBlocking {
        val before = SettingsDataStore.settings()
        try {
            SettingsDataStore.updateSettings {
                it.copy(selectedProviderId = "provider", selectedModelId = "model", memoryEnabled = false)
            }
            ErrorReconnectPolicy.entries.forEach { policy ->
                SettingsDataStore.setErrorReconnectPolicy(policy)
                assertEquals(policy, SettingsDataStore.errorReconnectPolicyFlow().first())
                val actual = SettingsDataStore.settings()
                assertEquals("provider", actual.selectedProviderId)
                assertEquals("model", actual.selectedModelId)
                assertEquals(false, actual.memoryEnabled)
                assertEquals(before.appearance, actual.appearance)
                assertEquals(before.fileLoggingEnabled, actual.fileLoggingEnabled)
            }
            SettingsDataStore.setErrorReconnectPolicy(ErrorReconnectPolicy.WINDOW_5M)
            SettingsDataStore.setMemoryEnabled(true)
            SettingsDataStore.setFileLoggingEnabled(false)
            SettingsDataStore.setSelectedModelId("other-model")
            SettingsDataStore.setAppearanceSettings(before.appearance.copy(blurEnabled = false))
            assertEquals(ErrorReconnectPolicy.WINDOW_5M, SettingsDataStore.settings().errorReconnectPolicy)
        } finally {
            SettingsDataStore.updateSettings { before }
        }
    }

    @Test
    fun missingAndUnknownPreferencesUseNone() = runBlocking {
        val before = SettingsDataStore.settings()
        val key = stringPreferencesKey("error_reconnect_policy")
        // Exercise the actual Preferences decoder without adding a production test-only API.
        val field = SettingsDataStore::class.java.getDeclaredField("dataStore").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val store = field.get(SettingsDataStore) as DataStore<Preferences>
        try {
            store.edit { it.remove(key) }
            assertEquals(ErrorReconnectPolicy.NONE, SettingsDataStore.errorReconnectPolicyFlow().first())
            store.edit { it[key] = "future_policy" }
            assertEquals(ErrorReconnectPolicy.NONE, SettingsDataStore.settings().errorReconnectPolicy)
            SettingsDataStore.setMemoryEnabled(false)
            assertEquals("none", store.data.first()[key])
        } finally {
            SettingsDataStore.updateSettings { before }
        }
    }

    @Test
    fun backupRoundTripIncludesEveryPolicy() = runBlocking {
        val before = SettingsDataStore.backupSnapshot()
        try {
            ErrorReconnectPolicy.entries.forEach { policy ->
                SettingsDataStore.setErrorReconnectPolicy(policy)
                val backup = SettingsDataStore.backupSnapshot()
                assertEquals(policy.persistedValue, backup.errorReconnectPolicy)
                val decoded = Json.decodeFromString<EtaSettingsBackup>(Json.encodeToString(backup))
                SettingsDataStore.setErrorReconnectPolicy(ErrorReconnectPolicy.NONE)
                SettingsDataStore.restoreBackup(decoded)
                assertEquals(policy, SettingsDataStore.errorReconnectPolicyFlow().first())
            }
        } finally {
            SettingsDataStore.restoreBackup(before)
        }
    }

    @Test
    fun oldNullAndUnknownBackupValuesRestoreToNone() = runBlocking {
        val before = SettingsDataStore.backupSnapshot()
        val compatibleBackups = listOf(
            Json.decodeFromString<EtaSettingsBackup>("{}"),
            Json.decodeFromString<EtaSettingsBackup>("""{"errorReconnectPolicy":null}"""),
            Json.decodeFromString<EtaSettingsBackup>("""{"errorReconnectPolicy":"future_policy"}"""),
        )
        try {
            compatibleBackups.forEach { backup ->
                SettingsDataStore.setErrorReconnectPolicy(ErrorReconnectPolicy.CONTINUOUS)
                SettingsDataStore.restoreBackup(backup)
                assertEquals(ErrorReconnectPolicy.NONE, SettingsDataStore.settings().errorReconnectPolicy)
                assertEquals("none", SettingsDataStore.backupSnapshot().errorReconnectPolicy)
            }
        } finally {
            SettingsDataStore.restoreBackup(before)
        }
    }
}
