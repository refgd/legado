package io.legado.app.data.entities

import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject

internal fun parseVariableMap(owner: String, variable: String?): HashMap<String, String> {
    if (variable.isNullOrBlank()) {
        return hashMapOf()
    }
    return GSON.fromJsonObject<HashMap<String, String>>(variable).getOrElse {
        throw NoStackTraceException(
            "$owner variable JSON is invalid for Rust analyzer state handoff: ${variable.take(300)}"
        )
    }
}
