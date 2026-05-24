package io.legado.app.lib.webdav

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets

class AuthorizationTest {

    @Test
    fun basicAuthorizationMatchesHttpBasicShape() {
        assertEquals("Basic dXNlcjpwYXNz", Authorization("user", "pass").data)
    }

    @Test
    fun basicAuthorizationHonorsConfiguredCharset() {
        assertEquals(
            "Basic xNDwOnBhc3M=",
            basicAuthorization("ÄÐð", "pass", StandardCharsets.ISO_8859_1)
        )
    }

    @Test
    fun normalizeHttpUrlMapsDavSchemesBeforeRequests() {
        assertEquals("https://example.com/dav/a%20b", normalizeHttpUrl("https://example.com/dav/a b"))
    }
}
