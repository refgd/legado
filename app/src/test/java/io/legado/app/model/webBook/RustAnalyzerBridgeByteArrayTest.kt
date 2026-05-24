package io.legado.app.model.webBook

import io.legado.app.data.entities.RustScriptBindings
import io.legado.app.exception.NoStackTraceException
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RustAnalyzerBridgeByteArrayTest {

    @Test
    fun rustBindingsEncodeByteArrayAsJavaBytesHex() {
        val bindings = RustScriptBindings().apply {
            put("result", byteArrayOf(0x01, 0x02, 0x7f))
        }

        val json = bindings.toRustBindingsJson()

        assertTrue(json.contains("\"__javaBytesHex\""))
        assertTrue(json.contains("\"01027f\""))
    }

    @Test
    fun rustBindingsEncodeInputStreamAsJavaBytesHex() {
        val bindings = RustScriptBindings().apply {
            put("result", ByteArrayInputStream(byteArrayOf(0x0a, 0x0b)))
        }

        val json = bindings.toRustBindingsJson()

        assertTrue(json.contains("\"__javaBytesHex\""))
        assertTrue(json.contains("\"0a0b\""))
    }

    @Test
    fun rustEvalResultDecodesJavaBytesObjectToByteArray() {
        val result = RustAnalyzerBridge.decodeEvalResult(
            "__LEGADO_JSON_VALUE__{\"__hex\":\"0102ff\",\"length\":3}"
        )

        assertArrayEquals(byteArrayOf(0x01, 0x02, 0xff.toByte()), result as ByteArray)
    }

    @Test
    fun rustEvalResultFailsFastForInvalidJsonMarker() {
        assertNoStackTrace("invalid JSON value") {
            RustAnalyzerBridge.decodeEvalResult("__LEGADO_JSON_VALUE__{not-json")
        }
    }

    @Test
    fun rustEvalResultFailsFastForUnsupportedJsonMarkerPrimitive() {
        assertNoStackTrace("unsupported JSON marker value") {
            RustAnalyzerBridge.decodeEvalResult("__LEGADO_JSON_VALUE__123")
        }
    }

    @Test
    fun rustEvalResultFailsFastForInvalidStrResponseMarker() {
        assertNoStackTrace("invalid StrResponse JSON") {
            RustAnalyzerBridge.decodeEvalResult("__LEGADO_STR_RESPONSE_JSON__{not-json")
        }
    }

    @Test
    fun rustEvalResultFailsFastForInvalidByteHex() {
        assertNoStackTrace("invalid byte hex") {
            RustAnalyzerBridge.decodeEvalResult("__LEGADO_JSON_VALUE__{\"__hex\":\"zz\"}")
        }
    }

    private fun assertNoStackTrace(messagePart: String, block: () -> Unit) {
        try {
            block()
            fail("Expected NoStackTraceException")
        } catch (e: NoStackTraceException) {
            assertTrue(e.message.orEmpty().contains(messagePart))
        }
    }
}
