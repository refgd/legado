package io.legado.app

import io.legado.app.data.entities.HttpTTS
import io.legado.app.model.webBook.RustAnalyzerBridge
import org.junit.Assert.assertEquals
import org.junit.Test

class HttpTtsTest {

    @Test
    fun ttsUrlRuleRunsInRustWithSpeakBindings() {
        val httpTts = HttpTTS(
            name = "Rust TTS",
            url = "@js:`data:audio/mpeg;base64,${'$'}{java.base64Encode(speakText + ':' + speakSpeed)}`"
        )

        val response = RustAnalyzerBridge.fetchTtsAudio(httpTts, "魔神", 15)

        assertEquals("audio/mpeg", response.contentType)
        assertEquals("魔神:15", response.body.toString(Charsets.UTF_8))
    }

}
