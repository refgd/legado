package io.legado.app.model

import androidx.collection.LruCache
import io.legado.app.utils.MD5Utils

/**
 * Rust/rquickjs owns source jsLib loading per analyzer session. This object only keeps the
 * old invalidation surface used by source editors, so callers can clear the remembered key
 * without reintroducing an Android-side JavaScript scope.
 */
object SharedJsScope {

    private val scopeKeys = LruCache<String, String>(16)

    fun remember(jsLib: String?) {
        if (!jsLib.isNullOrBlank()) {
            scopeKeys.put(MD5Utils.md5Encode(jsLib), jsLib)
        }
    }

    fun remove(jsLib: String?) {
        if (!jsLib.isNullOrBlank()) {
            scopeKeys.remove(MD5Utils.md5Encode(jsLib))
        }
    }
}
