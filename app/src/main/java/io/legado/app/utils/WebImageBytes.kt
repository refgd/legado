package io.legado.app.utils

import io.legado.app.data.entities.BaseSource
import io.legado.app.model.webBook.RustAnalyzerBridge

object WebImageBytes {

    fun fetch(data: String, source: BaseSource?, rulePath: String): ByteArray {
        return if (source != null) {
            RustAnalyzerBridge.fetchRaw(source, data, rulePath)
        } else {
            RustAnalyzerBridge.fetchRawUrl(data, rulePath).body
        }
    }
}
