package io.legado.app.help.http

import androidx.annotation.Keep

/**
 * An HTTP response.
 */
@Keep
@Suppress("unused", "MemberVisibilityCanBePrivate")
class StrResponse {
    var body: String? = null
        private set
    var errorBody: String? = null
        private set
    var callTime = 0
    private var responseUrl: String = "http://localhost/"
    private var responseCode: Int = 200
    private var responseMessage: String = "OK"
    private var responseHeaders: Map<String, List<String>> = emptyMap()

    constructor(url: String, body: String?) : this(url, body, 200, "OK")

    constructor(url: String, body: String?, code: Int, message: String) {
        responseUrl = url.ifBlank { "http://localhost/" }
        responseCode = code.coerceIn(100, 599)
        responseMessage = message.ifBlank { "OK" }
        this.body = body
    }

    constructor(
        url: String,
        errorBody: String?,
        code: Int,
        message: String,
        headers: Map<String, List<String>>
    ) {
        responseUrl = url.ifBlank { "http://localhost/" }
        responseCode = code.coerceIn(100, 599)
        responseMessage = message.ifBlank { "OK" }
        responseHeaders = headers
        this.errorBody = errorBody
    }

    fun putCallTime(callTime: Int) {
        this.callTime = callTime
    }
    fun raw() = this
    fun callTime() = callTime

    fun url(): String = responseUrl

    val url: String get() = url()

    fun body() = body

    fun code(): Int {
        return responseCode
    }

    fun message(): String {
        return responseMessage
    }

    fun headers(): Map<String, List<String>> {
        return responseHeaders
    }

    fun isSuccessful(): Boolean = responseCode in 200..299

    fun errorBody(): String? {
        return errorBody
    }

    override fun toString(): String {
        return "Response{code=$responseCode, message=$responseMessage, url=$responseUrl}"
    }

}
