package io.legado.app.help.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class ExoPlayerHelperTest {

    @Test
    fun exoPlayerRequestHeadersKeepCacheControlAndSourceHeaders() {
        val headers = ExoPlayerHelper.exoPlayerRequestHeaders(
            mapOf(
                "User-Agent" to "media-agent",
                "Referer" to "https://example.com/book"
            )
        )

        assertEquals("max-age=86400", headers["Cache-Control"])
        assertEquals("media-agent", headers["User-Agent"])
        assertEquals("https://example.com/book", headers["Referer"])
    }

    @Test
    fun sourceHeadersCanOverrideDefaultCacheControl() {
        val headers = ExoPlayerHelper.exoPlayerRequestHeaders(
            mapOf("Cache-Control" to "no-cache")
        )

        assertEquals("no-cache", headers["Cache-Control"])
    }
}
