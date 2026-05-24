package io.legado.app.help.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StrResponseTest {

    @Test
    fun statusAndBodyAreAvailableWithoutOkHttpResponse() {
        val response = StrResponse("https://example.com/a", "body", 201, "Created")

        assertEquals("https://example.com/a", response.url())
        assertEquals("body", response.body())
        assertEquals(201, response.code())
        assertEquals("Created", response.message())
        assertTrue(response.isSuccessful())
    }

    @Test
    fun statusIsClampedToHttpRange() {
        val response = StrResponse("https://example.com/a", "body", 999, "")

        assertEquals(599, response.code())
        assertEquals("OK", response.message())
        assertFalse(StrResponse("https://example.com/a", "body", 599, "Bad").isSuccessful())
    }
}
