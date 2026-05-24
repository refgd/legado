package io.legado.app.ui.login

import android.webkit.CookieManager
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject

object RustPlatformAction {
    private const val PREFIX = "__LEGADO_UNSUPPORTED_PLATFORM_API__:"

    fun handle(result: Any?, java: SourceLoginJsExtensions): Boolean {
        val marker = result?.toString()?.takeIf { it.startsWith(PREFIX) } ?: return false
        val parts = marker.removePrefix(PREFIX).split(":", limit = 3)
        if (parts.size < 3) {
            throw NoStackTraceException("Rust platform action marker is malformed: $marker")
        }
        val api = parts[1]
        val args = GSON.fromJsonArray<String>(parts[2]).getOrElse {
            throw NoStackTraceException(
                "Rust platform action $api returned invalid args JSON: ${it.localizedMessage}"
            )
        }
        val url = args.getOrNull(0).orEmpty()
        val title = args.getOrNull(1)?.ifBlank { api } ?: api
        when (api) {
            "startBrowser", "startBrowserAwait" -> {
                requireUrl(api, url)
                java.startBrowser(url, title)
                return true
            }
            "showBrowser" -> {
                requireUrl(api, url)
                java.showBrowser(
                    url = url,
                    html = args.getOrNull(1),
                    preloadJs = args.getOrNull(2),
                    config = args.getOrNull(3)
                )
                return true
            }
            "openVideoPlayer" -> {
                requireUrl(api, url)
                java.openVideoPlayer(url, title)
                return true
            }
            "reLoginView" -> {
                java.reLoginView(parseBooleanArg(api, args.getOrNull(0)))
                return true
            }
            "refreshExplore" -> {
                java.refreshExplore()
                return true
            }
            "copyText" -> {
                java.copyText(args.getOrNull(0).orEmpty())
                return true
            }
            "upLoginData" -> {
                val raw = args.getOrNull(0)
                val data = raw?.let {
                    GSON.fromJsonObject<Map<String, Any?>>(it).getOrElse { error ->
                        throw NoStackTraceException(
                            "Rust platform action $api returned invalid login-data JSON: ${error.localizedMessage}"
                        )
                    }
                }
                java.upLoginData(data)
                return true
            }
            "refreshBookInfo" -> {
                java.refreshBookInfo()
                return true
            }
            "refreshBookToc" -> {
                java.refreshBookToc()
                return true
            }
            "refreshContent" -> {
                java.refreshContent()
                return true
            }
            "clearTtsCache" -> {
                java.clearTtsCache()
                return true
            }
            "showPhoto" -> {
                java.showPhoto(args.getOrNull(0).orEmpty())
                return true
            }
            "searchBook" -> {
                java.searchBook(args.getOrNull(0).orEmpty(), args.getOrNull(1))
                return true
            }
            "addBook" -> {
                java.addBook(args.getOrNull(0).orEmpty())
                return true
            }
            "open" -> {
                java.open(
                    name = args.getOrNull(0).orEmpty(),
                    url = args.getOrNull(1),
                    title = args.getOrNull(2),
                    origin = args.getOrNull(3)
                )
                return true
            }
            "openUrl" -> {
                requireUrl(api, url)
                java.openUrl(url, args.getOrNull(1))
                return true
            }
            "setWebCookie" -> {
                requireUrl(api, url)
                val cookieManager = CookieManager.getInstance()
                cookieManager.removeSessionCookies(null)
                args.getOrNull(1).orEmpty().split(';')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach { cookieManager.setCookie(url, it) }
                cookieManager.flush()
                return true
            }
        }
        return false
    }

    private fun requireUrl(api: String, url: String) {
        if (url.isBlank()) {
            throw NoStackTraceException("Rust platform action $api returned blank URL")
        }
    }

    private fun parseBooleanArg(api: String, raw: String?): Boolean {
        if (raw == null) {
            return false
        }
        return raw.toBooleanStrictOrNull()
            ?: throw NoStackTraceException("Rust platform action $api returned invalid boolean: $raw")
    }
}
