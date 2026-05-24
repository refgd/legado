package io.legado.app.utils

@Suppress("unused")
class CustomUrl(url: String) {

    private val mUrl: String
    private val attribute = hashMapOf<String, Any>()

    init {
        val urlMatcher = UrlOptions.paramPattern.matcher(url)
        mUrl = if (urlMatcher.find()) {
            val attr = url.substring(urlMatcher.end())
            val parsedAttr = GSON.fromJsonObject<Map<String, Any>>(attr).getOrElse {
                throw IllegalArgumentException(
                    "CustomUrl attribute JSON is invalid for Rust analyzer handoff: " +
                        (it.localizedMessage ?: it::class.java.name)
                )
            }
            attribute.putAll(parsedAttr)
            url.take(urlMatcher.start())
        } else {
            url
        }
    }

    fun putAttribute(key: String, value: Any?): CustomUrl {
        if (value == null) {
            attribute.remove(key)
        } else {
            attribute[key] = value
        }
        return this
    }

    fun getUrl(): String {
        return mUrl
    }

    fun getAttr(): Map<String, Any> {
        return attribute
    }

    override fun toString(): String {
        if (attribute.isEmpty()) {
            return mUrl
        }
        return mUrl + "," + GSON.toJson(attribute)
    }
}
