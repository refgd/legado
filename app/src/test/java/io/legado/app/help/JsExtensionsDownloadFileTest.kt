package io.legado.app.help

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

class JsExtensionsDownloadFileTest {

    @Test
    fun rustConnectionResponseKeepsHeadersContentTypeAndCookies() {
        val response = RustConnectionResponse(
            responseUrl = URL("https://example.test/start"),
            responseBody = "body",
            responseCode = 302,
            responseMessage = "Found",
            responseMethod = RustConnectionMethod.GET,
            responseContentType = "text/html; charset=UTF-8",
            headers = linkedMapOf(
                "Location" to "/next",
                "Content-Type" to "text/html; charset=UTF-8",
                "Set-Cookie" to "sid=abc; Path=/"
            ),
            headersList = emptyList()
        )

        assertEquals(302, response.statusCode())
        assertEquals("/next", response.header("location"))
        assertTrue(response.hasHeaderWithValue("LOCATION", "/next"))
        assertEquals("text/html; charset=UTF-8", response.contentType())
        assertEquals("UTF-8", response.charset())
        assertEquals("abc", response.cookie("sid"))
        assertEquals(mapOf("sid" to "abc"), response.cookies())
    }

    @Test
    fun rustConnectionResponsePreservesDuplicateHeadersAndCookies() {
        val response = RustConnectionResponse(
            responseUrl = URL("https://example.test/multi"),
            responseBody = "body",
            responseCode = 200,
            responseMessage = "OK",
            responseMethod = RustConnectionMethod.GET,
            responseContentType = null,
            headers = linkedMapOf(
                "Set-Cookie" to "sid=abc; Path=/",
                "X-Multi" to "one"
            ),
            headersList = listOf(
                listOf("Set-Cookie", "sid=abc; Path=/"),
                listOf("Set-Cookie", "theme=dark; Path=/"),
                listOf("X-Multi", "one"),
                listOf("X-Multi", "two")
            )
        )

        assertEquals("sid=abc; Path=/", response.header("set-cookie"))
        assertEquals(
            mutableListOf("sid=abc; Path=/", "theme=dark; Path=/"),
            response.headers("SET-cookie")
        )
        assertEquals(mutableListOf("one", "two"), response.headers("x-multi"))
        assertEquals("abc", response.cookie("sid"))
        assertEquals("dark", response.cookie("theme"))
        assertEquals(mapOf("sid" to "abc", "theme" to "dark"), response.cookies())
    }

    @Test
    fun rustConnectionResponseParseFailsFastInsteadOfUsingAndroidJsoup() {
        val response = RustConnectionResponse(
            responseUrl = URL("https://example.test/page"),
            responseBody = "<p>body</p>",
            responseCode = 200,
            responseMessage = "OK",
            responseMethod = RustConnectionMethod.GET,
            responseContentType = "text/html; charset=GBK",
            headers = linkedMapOf("Content-Type" to "text/html; charset=GBK"),
            headersList = emptyList()
        )

        assertEquals("GBK", response.charset())
        val error = assertThrows(Exception::class.java) {
            response.parse()
        }
        assertTrue(error.message.orEmpty().contains("Rust HTTP response parse"))
    }

}
