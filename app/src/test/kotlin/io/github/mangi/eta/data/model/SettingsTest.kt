package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun memoryIsEnabledByDefault() {
        assertTrue(Settings().memoryEnabled)
    }

    @Test
    fun fileLoggingIsEnabledByDefault() {
        assertEquals(true, Settings().fileLoggingEnabled)
    }

    @Test
    fun errorReconnectIsDisabledByDefault() {
        assertEquals(ErrorReconnectPolicy.NONE, Settings().errorReconnectPolicy)
    }

    @Test
    fun appearanceUsesBackwardCompatibleDefaults() {
        assertEquals(AppearanceSettings(), Settings().appearance)
    }
}
