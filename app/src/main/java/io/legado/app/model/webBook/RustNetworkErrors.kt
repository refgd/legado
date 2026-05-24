package io.legado.app.model.webBook

fun Throwable.isRustNetworkAccessError(): Boolean {
    var error: Throwable? = this
    while (error != null) {
        val name = error::class.java.name
        val message = error.message.orEmpty()
        if (
            name.contains("UnknownHostException") ||
            name.contains("ConnectException") ||
            name.contains("SocketException") ||
            name.contains("SocketTimeoutException") ||
            name.contains("SSLException") ||
            message.contains("Request:") && (
                    message.contains("error sending request", ignoreCase = true) ||
                            message.contains("error reading response body", ignoreCase = true) ||
                            message.contains("timed out", ignoreCase = true) ||
                            message.contains("timeout", ignoreCase = true) ||
                            message.contains("dns", ignoreCase = true) ||
                            message.contains("failed to lookup address", ignoreCase = true) ||
                            message.contains("network is unreachable", ignoreCase = true) ||
                            message.contains("connection refused", ignoreCase = true) ||
                            message.contains("connection reset", ignoreCase = true)
                    )
        ) {
            return true
        }
        error = error.cause
    }
    return false
}
