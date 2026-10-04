package io.github.mangi.eta.ui.navigation

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ErrorReconnectRouteTest {
    @Test
    fun reconnectSettingsIsAnIndependentSerializableRoute() {
        val route: AppRoute = AppRoute.ErrorReconnectSettings
        val restored = Json.decodeFromString<AppRoute>(Json.encodeToString(route))
        assertEquals(route, restored)
        assertNotEquals(AppRoute.Settings, restored)
        assertNotEquals(AppRoute.TitleModel, restored)
        assertNotEquals(AppRoute.ModelProviders, restored)
    }
}
