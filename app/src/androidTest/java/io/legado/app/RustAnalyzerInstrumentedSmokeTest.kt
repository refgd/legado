package io.legado.app

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.system.Os
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.ReplaceBook
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ContentRule
import io.legado.app.data.entities.rule.TocRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.ai.AiChatService
import io.legado.app.help.ai.AiMcpClient
import io.legado.app.help.ai.AiTavilyTool
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getExportFileName
import io.legado.app.help.config.AppConfig
import io.legado.app.help.gsyVideo.DanmakuAdapter
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.source.removeSortCache
import io.legado.app.help.source.sortUrls
import io.legado.app.help.source.SourceVerificationHelp
import io.legado.app.help.webView.fetchModifiedContentWithRust
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.model.BookCover
import io.legado.app.model.RuleUpdate
import io.legado.app.model.SourceCallBack
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.TextFile
import io.legado.app.model.localBook.evalTxtTocReplacementWithRust
import io.legado.app.model.localBook.LocalBook
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.model.webBook.SearchModel
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.rss.Rss
import io.legado.app.ui.book.info.BookInfoViewModel
import io.legado.app.ui.book.info.evalBookInfoButtonClickByRust
import io.legado.app.ui.book.read.evalReadBookImageClickByRust
import io.legado.app.ui.book.read.evalReadBookPayActionByRust
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.login.evalLoginButtonClickByRust
import io.legado.app.ui.login.evalLoginUiJsByRust
import io.legado.app.ui.login.resolveLoginHeaderMapByRust
import io.legado.app.ui.main.explore.evalExploreButtonClickByRust
import io.legado.app.ui.main.explore.evalExploreUiJsByRust
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.main.rss.RssViewModel
import io.legado.app.ui.rss.read.evalRssShouldOverrideUrlLoadingByRust
import io.legado.app.ui.rss.read.RssJsExtensions
import io.legado.app.ui.rss.read.resolveStartHtmlByRustEval
import io.legado.app.ui.video.evalVideoButtonClickByRust
import io.legado.app.utils.GSON
import io.legado.app.utils.InfoMap
import io.legado.app.utils.RustRemoteFetch
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.WebImageBytes
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.replace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import splitties.init.appCtx
import java.io.File
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class RustAnalyzerInstrumentedSmokeTest {

    @Test
    fun directHttpSourcesUseRustAnalyzerBridge() = runBlocking {
        prepareRustAnalyzerFixtures()

        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        smokeSource("bookSource_七猫小说.json")
        smokeSource("bookSource_阅友小说.json")
        smokeSource("bookSource_有度中文.json")
        smokeSource("bookSource_光遇聚合.json")

        assertQimaoExploreCategoryUsesRuleBookUrlScript(readSource("bookSource_七猫小说.json"))
        assertDahuilangExploreCategoryIgnoresMissingJsonFields(readSource("bookSource_大灰狼.json"))

        val guangyu = readSource("bookSource_光遇聚合.json")
        val rawExplore = RustAnalyzerBridge.analyzeRaw(
            guangyu,
            "explore",
            """{"exploreUrl":"","page":1}"""
        )
        assertTrue(rawExplore.contains("UnsupportedPlatformApi"))
        assertTrue(rawExplore.contains("startBrowser"))
    }

    @Test
    fun appSearchInfoTocContentFlowUsesRustAnalyzerBridge() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val sources = listOf(
            readSource("bookSource_七猫小说.json"),
            readSource("bookSource_阅友小说.json"),
            readSource("bookSource_有度中文.json"),
            readSource("bookSource_光遇聚合.json")
        )
        sources.forEach { source ->
            source.enabled = true
            appDb.bookSourceDao.delete(source.bookSourceUrl)
            appDb.bookSourceDao.insert(source)
        }

        sources.forEach { source ->
            RustAnalyzerBridge.resetCallCounts()

            val search = searchViaAppModel(source, "我的")
            assertTrue("${source.bookSourceName} SearchModel returned no books", search.isNotEmpty())
            assertTrue("${source.bookSourceName} did not use Rust search", RustAnalyzerBridge.callCount("search") > 0)

            var content = ""
            var lastContentError: Throwable? = null
            for (searchBook in search.take(5)) {
                clearExistingBook(searchBook)
                val infoBook = loadInfoAndTocViaAppModel(searchBook)
                val chapters = appDb.bookChapterDao.getChapterList(infoBook.bookUrl)
                assertTrue("${source.bookSourceName} BookInfoViewModel stored no chapters", chapters.isNotEmpty())
                val chapter = chapters.firstOrNull { !it.isVolume } ?: chapters.first()
                try {
                    content = WebBook.getContentAwait(source, infoBook, chapter)
                } catch (throwable: Throwable) {
                    lastContentError = throwable
                    continue
                }
                if (content.isNotBlank()) break
            }
            assertTrue(
                "${source.bookSourceName} content is blank; lastError=${lastContentError?.message.orEmpty()}",
                content.isNotBlank()
            )
            assertTrue("${source.bookSourceName} did not use Rust detail", RustAnalyzerBridge.callCount("detail") > 0)
            assertTrue("${source.bookSourceName} did not use Rust toc", RustAnalyzerBridge.callCount("toc") > 0)
            assertTrue("${source.bookSourceName} did not use Rust content", RustAnalyzerBridge.callCount("content") > 0)

            val exploreJson = RustAnalyzerBridge.analyzeRaw(
                source,
                "explore",
                """{"exploreUrl":"","page":1}"""
            )
            assertExploreRan(source, exploreJson)
            assertTrue("${source.bookSourceName} did not use Rust explore", RustAnalyzerBridge.callCount("explore") > 0)
        }

        val guangyu = sources.last()
        val rawExplore = RustAnalyzerBridge.analyzeRaw(
            guangyu,
            "explore",
            """{"exploreUrl":"","page":1}"""
        )
        assertTrue(rawExplore.contains("UnsupportedPlatformApi"))
        assertTrue(rawExplore.contains("startBrowser"))
    }

    @Test
    fun webBookContentUsesRustNextContentUrlAndNeedSave() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val baseUrl = serveContentPages()
        val source = minimalSource().apply {
            bookSourceUrl = baseUrl
            bookSourceName = "Rust Content Paging"
            ruleContent = ContentRule(
                content = ".content@text",
                title = "h1@text",
                nextContentUrl = ".next@href"
            )
        }
        val book = Book(
            bookUrl = "$baseUrl/book-${System.nanoTime()}",
            tocUrl = "$baseUrl/toc",
            name = "Rust Content Paging",
            origin = source.bookSourceUrl
        )
        val chapter = BookChapter(
            bookUrl = book.bookUrl,
            title = "Chapter One",
            url = "$baseUrl/c1",
            index = 0
        )

        RustAnalyzerBridge.resetCallCounts()
        val content = WebBook.getContentAwait(
            source,
            book,
            chapter,
            nextChapterUrl = "$baseUrl/c2",
            needSave = true
        )

        assertEquals("One\nTwo", content)
        assertTrue("WebBook content should use Rust content operation", RustAnalyzerBridge.callCount("content") > 0)
        assertEquals(content, BookHelp.getContent(book, chapter))
    }

    @Test
    fun webBookContentHandlesSubContentThroughRust() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val textSource = minimalSource().apply {
            bookSourceUrl = "https://sub-content.example/text"
            bookSourceName = "Rust Text SubContent"
            ruleContent = ContentRule(
                content = ".content@text",
                subContent = ".extra@text"
            )
        }
        val textBook = Book(
            bookUrl = "https://sub-content.example/book-text-${System.nanoTime()}",
            name = "Rust Text SubContent",
            origin = textSource.bookSourceUrl,
            type = BookType.text
        )
        val textChapter = BookChapter(
            bookUrl = textBook.bookUrl,
            title = "Text",
            url = htmlDataUrl("""<div class="content">Main</div><div class="extra">Extra</div>""")
        )

        RustAnalyzerBridge.resetCallCounts()
        assertEquals(
            "Main\nExtra",
            WebBook.getContentAwait(textSource, textBook, textChapter, needSave = false)
        )
        assertTrue("Text subContent should use Rust content", RustAnalyzerBridge.callCount("content") > 0)

        val audioSource = minimalSource().apply {
            bookSourceUrl = "https://sub-content.example/audio"
            bookSourceName = "Rust Audio SubContent"
            ruleContent = ContentRule(
                content = ".content@text",
                subContent = ".lyric@text"
            )
        }
        val audioBook = Book(
            bookUrl = "https://sub-content.example/book-audio-${System.nanoTime()}",
            name = "Rust Audio SubContent",
            origin = audioSource.bookSourceUrl,
            type = BookType.audio
        )
        val audioChapter = BookChapter(
            bookUrl = audioBook.bookUrl,
            title = "Audio",
            url = htmlDataUrl("""<div class="content">Audio URL</div><div class="lyric">audio-extra</div>""")
        )

        RustAnalyzerBridge.resetCallCounts()
        assertEquals(
            "Audio URL",
            WebBook.getContentAwait(audioSource, audioBook, audioChapter, needSave = false)
        )
        assertTrue("Audio subContent should use Rust content", RustAnalyzerBridge.callCount("content") > 0)
        assertTrue(audioChapter.variable.orEmpty().contains("audio-extra"))
    }

    @Test
    fun webBookNonDefaultSourceTypesUseRustAnalyzerBridge() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        listOf(
            BookSourceType.audio to BookType.audio,
            BookSourceType.image to BookType.image,
            BookSourceType.file to (BookType.text or BookType.webFile),
            BookSourceType.video to BookType.video
        ).forEach { (sourceType, bookType) ->
            val source = minimalSource().apply {
                bookSourceType = sourceType
                bookSourceUrl = "https://non-default-source.example/$sourceType"
                bookSourceName = "Rust Non Default Source $sourceType"
                ruleContent = ContentRule(content = ".content@text")
            }
            val book = Book(
                bookUrl = "https://non-default-source.example/book-$sourceType-${System.nanoTime()}",
                name = "Rust Non Default Source $sourceType",
                origin = source.bookSourceUrl,
                type = bookType
            )
            val chapter = BookChapter(
                bookUrl = book.bookUrl,
                title = "Type $sourceType",
                url = htmlDataUrl("""<div class="content">content-$sourceType</div>""")
            )

            RustAnalyzerBridge.resetCallCounts()
            assertEquals(
                "content-$sourceType",
                WebBook.getContentAwait(source, book, chapter, needSave = false)
            )
            assertTrue(
                "BookSourceType $sourceType should use Rust content",
                RustAnalyzerBridge.callCount("content") > 0
            )
        }
    }

    @Test
    fun urlUtilFileNameUsesRustHeadFetch() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val observedRequest = CompletableDeferred<String>()
        val url = serveOnce(
            body = "",
            contentType = "application/octet-stream",
            observedRequest = observedRequest,
            responseHeaders = listOf(
                "Content-Disposition" to "attachment; filename*=UTF-8''rust%20file.txt"
            )
        )

        RustAnalyzerBridge.resetCallCounts()
        assertEquals(
            "rust file.txt",
            UrlUtil.getFileName(url, mapOf("X-Rust-Head" to "1"))
        )
        assertTrue("UrlUtil.getFileName should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") > 0)
        val request = observedRequest.await()
        assertTrue(request, request.startsWith("HEAD /remote "))
        assertTrue(request, request.lineSequence().any { it.equals("X-Rust-Head: 1", ignoreCase = true) })
    }

    @Test
    fun danmakuInlineIconBytesUseRustRawFetch() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val observedRequest = CompletableDeferred<String>()
        val url = serveOnce(
            body = "icon-bytes",
            contentType = "image/x-icon",
            observedRequest = observedRequest
        )

        RustAnalyzerBridge.resetCallCounts()
        assertEquals(
            "icon-bytes",
            DanmakuAdapter.fetchInlineIconBytes(url).toString(Charsets.UTF_8)
        )
        assertTrue("Danmaku inline icon should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") > 0)
        assertTrue(observedRequest.await().startsWith("GET /remote "))
    }

    @Test
    fun aiTavilySearchUsesRustRawFetch() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val oldEnabled = AppConfig.aiTavilyEnabled
        val oldKey = AppConfig.aiTavilyApiKey
        val oldBaseUrl = AppConfig.aiTavilyBaseUrl
        val oldTopic = AppConfig.aiTavilyTopic
        val oldDepth = AppConfig.aiTavilySearchDepth
        val oldMax = AppConfig.aiTavilyMaxResults
        val observedRequest = CompletableDeferred<String>()
        val baseUrl = serveTavilySearchOnce(observedRequest)
        try {
            AppConfig.aiTavilyEnabled = true
            AppConfig.aiTavilyApiKey = "rust-key"
            AppConfig.aiTavilyBaseUrl = baseUrl
            AppConfig.aiTavilyTopic = "news"
            AppConfig.aiTavilySearchDepth = "advanced"
            AppConfig.aiTavilyMaxResults = 4

            val tool = AiTavilyTool.resolvedTools().first { it.name == "search_web_tavily" }
            RustAnalyzerBridge.resetCallCounts()
            val output = JSONObject(
                tool.execute(
                    JSONObject().apply {
                        put("query", "rust tavily")
                        put("maxResults", 2)
                        put("includeAnswer", true)
                    }
                )
            )

            assertTrue(output.toString(), output.getBoolean("ok"))
            assertEquals("rust tavily", output.getString("query"))
            assertEquals("Rust answer", output.getString("answer"))
            assertEquals("Rust result", output.getJSONArray("results").getJSONObject(0).getString("title"))
            assertTrue("Tavily search should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") > 0)

            val request = observedRequest.await()
            assertTrue(request, request.startsWith("POST /search "))
            assertTrue(request, request.lineSequence().any { it.equals("Accept: application/json", ignoreCase = true) })
            assertTrue(request, request.lineSequence().any { it.equals("Authorization: Bearer rust-key", ignoreCase = true) })
            val body = request.substringAfter("\r\n\r\n")
            val sent = JSONObject(body)
            assertEquals("rust tavily", sent.getString("query"))
            assertEquals("news", sent.getString("topic"))
            assertEquals("advanced", sent.getString("search_depth"))
            assertEquals(2, sent.getInt("max_results"))
        } finally {
            AppConfig.aiTavilyEnabled = oldEnabled
            AppConfig.aiTavilyApiKey = oldKey
            AppConfig.aiTavilyBaseUrl = oldBaseUrl
            AppConfig.aiTavilyTopic = oldTopic
            AppConfig.aiTavilySearchDepth = oldDepth
            AppConfig.aiTavilyMaxResults = oldMax
        }
    }

    @Test
    fun aiFetchModelsUsesRustRawFetch() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val observedRequest = CompletableDeferred<String>()
        val baseUrl = serveAiModelsOnce(observedRequest)
        RustAnalyzerBridge.resetCallCounts()
        val models = AiChatService.fetchModels(
            AiProviderConfig(
                name = "Rust Models",
                baseUrl = baseUrl,
                apiKey = "model-key",
                headers = """{"X-Model-Rust":"1"}"""
            )
        )

        assertEquals(listOf("alpha", "beta"), models)
        assertTrue("AI fetchModels should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") > 0)
        val request = observedRequest.await()
        assertTrue(request, request.startsWith("GET /v1/models "))
        assertTrue(request, request.lineSequence().any { it.equals("Accept: application/json", ignoreCase = true) })
        assertTrue(request, request.lineSequence().any { it.equals("Authorization: Bearer model-key", ignoreCase = true) })
        assertTrue(request, request.lineSequence().any { it.equals("X-Model-Rust: 1", ignoreCase = true) })
    }

    @Test
    fun aiMcpClientUsesRustRawFetch() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val observedRequests = CompletableDeferred<List<String>>()
        val endpoint = serveMcpServerOnce(observedRequests)
        val server = AiMcpServerConfig(
            id = "rust-mcp-${System.nanoTime()}",
            name = "Rust MCP",
            endpoint = endpoint,
            apiKey = "mcp-key"
        )

        RustAnalyzerBridge.resetCallCounts()
        val tools = AiMcpClient.resolveTools(listOf(server))
        assertEquals(1, tools.size)
        val callOutput = JSONObject(
            tools.first().execute(
                JSONObject().apply { put("message", "hello") }
            )
        )
        assertEquals("echo", callOutput.getString("content"))
        assertTrue("AI MCP should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") >= 4)

        val requests = observedRequests.await()
        assertEquals(4, requests.size)
        requests.forEach { request ->
            assertTrue(request, request.startsWith("POST /mcp "))
            assertTrue(request, request.lineSequence().any { it.equals("Accept: application/json, text/event-stream", ignoreCase = true) })
            assertTrue(request, request.lineSequence().any { it.equals("Authorization: Bearer mcp-key", ignoreCase = true) })
        }
        assertFalse(requests[0].lineSequence().any { it.startsWith("MCP-Protocol-Version:", ignoreCase = true) })
        requests.drop(1).forEach { request ->
            assertTrue(request, request.lineSequence().any { it.equals("MCP-Protocol-Version: 2025-06-18", ignoreCase = true) })
            assertTrue(request, request.lineSequence().any { it.equals("Mcp-Session-Id: rust-session", ignoreCase = true) })
        }
        assertEquals("initialize", JSONObject(requests[0].substringAfter("\r\n\r\n")).getString("method"))
        assertEquals("notifications/initialized", JSONObject(requests[1].substringAfter("\r\n\r\n")).getString("method"))
        assertEquals("tools/list", JSONObject(requests[2].substringAfter("\r\n\r\n")).getString("method"))
        val callBody = JSONObject(requests[3].substringAfter("\r\n\r\n"))
        assertEquals("tools/call", callBody.getString("method"))
        assertEquals("echo", callBody.getJSONObject("params").getString("name"))
        assertEquals("hello", callBody.getJSONObject("params").getJSONObject("arguments").getString("message"))
    }

    @Test
    fun aiChatStreamUsesRustRawFetch() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val oldProviders = AppConfig.aiProviderList
        val oldModels = AppConfig.aiModelConfigList
        val oldProviderId = AppConfig.aiCurrentProviderId
        val oldModelId = AppConfig.aiCurrentModelId
        val oldEnabledTools = AppConfig.aiEnabledToolNames
        val oldMcpServers = AppConfig.aiMcpServerList
        val oldSkills = AppConfig.aiSkillList
        val observedRequest = CompletableDeferred<String>()
        val baseUrl = serveAiChatCompletionOnce(observedRequest)
        val provider = AiProviderConfig(
            id = "rust-chat-provider-${System.nanoTime()}",
            name = "Rust Chat",
            baseUrl = baseUrl,
            apiKey = "chat-key",
            headers = """{"X-Chat-Rust":"1"}"""
        )
        val model = AiModelConfig(
            id = "rust-chat-model-${System.nanoTime()}",
            providerId = provider.id,
            modelId = "rust-model"
        )
        try {
            AppConfig.aiProviderList = listOf(provider)
            AppConfig.aiModelConfigList = listOf(model)
            AppConfig.aiCurrentProviderId = provider.id
            AppConfig.aiCurrentModelId = model.id
            AppConfig.aiEnabledToolNames = setOf("__disabled_for_rust_chat_test__")
            AppConfig.aiMcpServerList = emptyList()
            AppConfig.aiSkillList = emptyList()

            val partials = mutableListOf<String>()
            val thinking = mutableListOf<String>()
            RustAnalyzerBridge.resetCallCounts()
            val output = AiChatService.chatStream(
                messages = listOf(
                    AiChatMessage(
                        role = AiChatMessage.Role.USER,
                        content = "hello"
                    )
                ),
                onPartial = { partials += it },
                onThinking = { thinking += it },
                includeStructuredBlocks = false
            )

            assertEquals("Hello", output)
            assertEquals(listOf("Hel", "Hello"), partials)
            assertEquals(listOf("think"), thinking)
            assertTrue("AI chat stream should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") > 0)
            val request = observedRequest.await()
            assertTrue(request, request.startsWith("POST /v1/chat/completions "))
            assertTrue(request, request.lineSequence().any { it.equals("Accept: text/event-stream, application/json", ignoreCase = true) })
            assertTrue(request, request.lineSequence().any { it.equals("Authorization: Bearer chat-key", ignoreCase = true) })
            assertTrue(request, request.lineSequence().any { it.equals("X-Chat-Rust: 1", ignoreCase = true) })
            val body = JSONObject(request.substringAfter("\r\n\r\n"))
            assertEquals("rust-model", body.getString("model"))
            assertTrue(body.getBoolean("stream"))
            assertFalse(body.has("tools"))
            assertEquals("user", body.getJSONArray("messages").getJSONObject(1).getString("role"))
        } finally {
            AppConfig.aiProviderList = oldProviders
            AppConfig.aiModelConfigList = oldModels
            AppConfig.aiCurrentProviderId = oldProviderId
            AppConfig.aiCurrentModelId = oldModelId
            AppConfig.aiEnabledToolNames = oldEnabledTools
            AppConfig.aiMcpServerList = oldMcpServers
            AppConfig.aiSkillList = oldSkills
        }
    }

    @Test
    fun webDavUsesRustRawFetchForProtocolRequests() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val observedRequests = CompletableDeferred<List<Pair<String, ByteArray>>>()
        val baseUrl = serveWebDavSequence(observedRequests)
        val authorization = Authorization("user", "pass")

        RustAnalyzerBridge.resetCallCounts()
        val webDav = WebDav(baseUrl, authorization)
        val files = webDav.listFiles()
        assertEquals(1, files.size)
        assertEquals("book.txt", files.first().displayName)
        assertEquals("book-body", files.first().download().toString(Charsets.UTF_8))

        val upload = WebDav("${baseUrl}upload.bin", authorization)
        upload.upload(byteArrayOf(0, 65, -1), "application/octet-stream")
        assertTrue(upload.delete())

        assertTrue("WebDav should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") >= 4)
        val requests = observedRequests.await()
        assertTrue(requests[0].first.startsWith("PROPFIND /dav/ "))
        assertTrue(requests[0].first.lineSequence().any { it.equals("Depth: 1", ignoreCase = true) })
        assertTrue(requests[0].first.lineSequence().any { it.startsWith("Authorization: Basic ", ignoreCase = true) })
        assertTrue(requests[1].first.startsWith("GET /dav/book.txt "))
        assertTrue(requests[2].first.startsWith("PUT /dav/upload.bin "))
        assertEquals(byteArrayOf(0, 65, -1).toList(), requests[2].second.toList())
        assertTrue(requests[3].first.startsWith("DELETE /dav/upload.bin "))
    }

    @Test
    fun htmlDocumentHelpersUseRustUniFfi() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        RustAnalyzerBridge.resetCallCounts()
        assertEquals(
            listOf("Title", "Alpha", "Beta"),
            RustAnalyzerBridge.htmlTextArray(
                "<article><h1>Title</h1><p>Alpha<br>Beta</p></article>",
                "test.htmlTextArray"
            )
        )
        assertEquals(
            "GBK",
            RustAnalyzerBridge.htmlCharset(
                """<head><meta http-equiv="Content-Type" content="text/html; charset=GBK"></head>""",
                "test.htmlCharset"
            )
        )
        val formatted = RustAnalyzerBridge.htmlFormat("<p>Alpha</p>", "test.htmlFormat")
        assertTrue(formatted, formatted.contains("Alpha"))
        assertEquals(
            "Chapter One",
            RustAnalyzerBridge.htmlTitle(
                "<html><head><title> Chapter   One </title></head><body></body></html>",
                "test.htmlTitle"
            )
        )
        assertEquals(
            "right",
            RustAnalyzerBridge.htmlFirstAlignment(
                """<section><p style="text-align: right">Alpha</p></section>""",
                "test.htmlFirstAlignment"
            )
        )
        assertEquals(
            listOf("a.xhtml", "b.xhtml"),
            RustAnalyzerBridge.epubNativeEntryHrefs(
                """<epub-native data-href="fallback.xhtml" data-hrefs="a.xhtml| b.xhtml |a.xhtml"></epub-native>""",
                "test.epubNativeEntry"
            )
        )
        assertEquals(
            "正文标题",
            RustAnalyzerBridge.epubReadableTitle(
                "<html><head><title>Fallback</title></head><body><h2>正文标题</h2></body></html>",
                "test.epubReadableTitle"
            )
        )
        val bookInfo = RustAnalyzerBridge.epubBookInfo(
            """<div class="book-info" title="书籍信息"><p>作者：张三</p><p>简介：第一行</p><p>第二行</p></div>""",
            "test.epubBookInfo"
        )
        assertTrue(bookInfo.isBookInfo)
        assertEquals("张三", bookInfo.author)
        assertEquals("第一行\n第二行", bookInfo.intro)
        assertEquals(
            listOf("fn1", "note-2"),
            RustAnalyzerBridge.epubFootnoteIds(
                """<section id="fn1" epub:type="footnote">A</section><p id="plain">B</p><aside id="note-2" role="doc-endnote">C</aside>""",
                "test.epubFootnoteIds"
            )
        )
        val footnoteTarget = RustAnalyzerBridge.epubFootnoteTarget(
            """<section id="fn1" title="脚注"><a href="#fn1">self</a><a href="#ref-back">back</a><p>Note <img data-src="img/note.png"></p></section>""",
            "fn1",
            "test.epubFootnoteTarget"
        )
        assertTrue(footnoteTarget.found)
        assertEquals("脚注", footnoteTarget.title)
        assertEquals("back Note", footnoteTarget.text)
        assertFalse(footnoteTarget.html.contains("self"))
        assertTrue(footnoteTarget.html.contains("__LEGADO_EPUB_FOOTNOTE_IMG_0__"))
        assertEquals(listOf("img/note.png"), footnoteTarget.imageSources)
        val readableLines = RustAnalyzerBridge.epubReadableLines(
            """<body><p>第一 <strong>粗体</strong><br><em>斜体</em><sup>1</sup></p><p style="display:none">隐藏</p><img src="cover.jpeg"><img src="cover.jpeg"><img src="pic.png"><p><ruby>汉<rt>han</rt></ruby></p></body>""",
            deleteRuby = true,
            rulePath = "test.epubReadableLines"
        )
        assertEquals(
            listOf(
                "第一 ${EpubFile.INLINE_STYLE_MARK}B粗体${EpubFile.INLINE_STYLE_MARK}b",
                "${EpubFile.INLINE_STYLE_MARK}I斜体${EpubFile.INLINE_STYLE_MARK}i ${EpubFile.INLINE_STYLE_MARK}P1${EpubFile.INLINE_STYLE_MARK}p",
                """<img src="cover.jpeg">""",
                """<img src="pic.png">""",
                "汉"
            ),
            readableLines
        )
        val bodyHtml = RustAnalyzerBridge.epubBodyHtml(
            """<html><head><script>alert(1)</script></head><body style="background: red"><p id="start">Start</p><image xlink:href="pic.svg"/><aside id="fn1" epub:type="footnote">Note</aside><p id="end">End</p></body></html>""",
            startFragmentId = "start",
            endFragmentId = "end",
            rulePath = "test.epubBodyHtml"
        )
        assertTrue(bodyHtml.sliced)
        assertTrue(bodyHtml.bodyHtml.contains("""<p id="start">"""))
        assertTrue(bodyHtml.bodyOuterHtml.startsWith("<body"))
        assertEquals("", bodyHtml.bodyStyle)
        assertEquals("", bodyHtml.bodyBackground)
        assertTrue(bodyHtml.html.contains("""<p id="start">"""))
        assertTrue(bodyHtml.html.contains("<img"))
        assertTrue(bodyHtml.html.contains("src=\"pic.svg\""))
        assertTrue(bodyHtml.html.contains("display:none"))
        assertTrue(bodyHtml.html.contains("data-epub-page-bg=\"FFFF0000\""))
        assertFalse(bodyHtml.html.contains("<script"))
        assertFalse(bodyHtml.html.contains("id=\"end\""))
        val fullBodyHtml = RustAnalyzerBridge.epubBodyHtml(
            """<html><head><title>章节名</title></head><body style="color:red" background="bg.png"><p>A</p></body></html>""",
            startFragmentId = null,
            endFragmentId = null,
            rulePath = "test.epubBodyHtml.full"
        )
        assertEquals("章节名", fullBodyHtml.title)
        assertEquals("color:red", fullBodyHtml.bodyStyle)
        assertEquals("bg.png", fullBodyHtml.bodyBackground)
        assertTrue(fullBodyHtml.bodyOuterHtml.contains("""style="color:red""""))
        val debugChapterHtml = RustAnalyzerBridge.epubDebugChapterHtml(
            listOf(
                """<body><title>Hidden</title><p>A</p><p style="display:none">Hide</p><img src="cover.jpeg"><ruby>字<rt>zi</rt></ruby></body>""",
                """<body><img src="cover.jpeg"><p>B</p></body>"""
            ),
            deleteRuby = true,
            rulePath = "test.epubDebugChapterHtml"
        )
        assertTrue(debugChapterHtml.contains("<p>A</p>"))
        assertTrue(debugChapterHtml.contains("<p>B</p>"))
        assertFalse(debugChapterHtml.contains("Hidden"))
        assertFalse(debugChapterHtml.contains("Hide"))
        assertFalse(debugChapterHtml.contains("<rt>"))
        assertEquals(1, Regex("""src="cover\.jpeg"""").findAll(debugChapterHtml).count())
        val epubImageOptions = RustAnalyzerBridge.epubImageOptions(
            """<img data-src="pic.png" alt="Cover" data-epub-background="true" width="80px" height="2em">""",
            "test.epubImageOptions"
        )
        assertEquals("pic.png", epubImageOptions.src)
        assertEquals("Cover", epubImageOptions.alt)
        assertTrue(epubImageOptions.isBackground)
        assertEquals("80", epubImageOptions.width)
        assertEquals("2em", epubImageOptions.height)
        assertEquals("text", epubImageOptions.style)
        val imagePageMarks = RustAnalyzerBridge.epubImagePageMarks(
            """<div class="gallery"><img src="a.png"><img src="b.png"></div>""",
            "test.epubImagePageMarks"
        )
        assertTrue(imagePageMarks.bodyStyleAppend.contains("text-align:center"))
        assertTrue(imagePageMarks.html.contains("data-legado-width=\"100%\""))
        assertTrue(imagePageMarks.html.contains("data-legado-style=\"SINGLE\""))
        assertTrue(imagePageMarks.html.contains("display:block"))
        val materializedImages = RustAnalyzerBridge.epubMaterializedImages(
            """<p><img data-src="../Images/pic.png" alt="A" width="10%" height="2em"><img src="cover.jpeg" data-epub-background="true"></p>""",
            "OPS/text/ch1.xhtml",
            listOf("OPS/Images/pic.png", "cover.jpeg"),
            "test.epubMaterializedImages"
        )
        assertTrue(materializedImages.contains("""src="OPS/Images/pic.png,{&quot;width&quot;:&quot;10%&quot;,&quot;height&quot;:&quot;2em&quot;,&quot;style&quot;:&quot;text&quot;}""""))
        assertTrue(materializedImages.contains("""data-legado-width="10%""""))
        assertTrue(materializedImages.contains("""data-legado-style="text""""))
        assertTrue(materializedImages.contains("""alt="A""""))
        assertTrue(materializedImages.contains("""src="cover.jpeg""""))
        assertTrue(materializedImages.contains("""data-epub-background="true""""))
        val mediaHtml = RustAnalyzerBridge.epubMediaPlaceholders(
            """<div><video title="Clip"><source src="../media/a b.mp4"></video><audio src="sound.mp3" alt="Sound"></audio><iframe></iframe><p>Keep</p></div>""",
            "OPS/text/ch1.xhtml",
            "test.epubMediaPlaceholders"
        )
        assertTrue(mediaHtml.contains("epub-media-placeholder"))
        assertTrue(mediaHtml.contains("[EPUB视频] Clip"))
        assertTrue(mediaHtml.contains("legado-epub-media:OPS/media/a%20b.mp4"))
        assertTrue(mediaHtml.contains("[EPUB音频] Sound"))
        assertTrue(mediaHtml.contains("legado-epub-media:OPS/text/sound.mp3"))
        assertTrue(mediaHtml.contains("legado-epub-media:missing"))
        assertFalse(mediaHtml.contains("<video"))
        assertFalse(mediaHtml.contains("<audio"))
        assertTrue(mediaHtml.contains("<p>Keep</p>"))
        val inlineHtml = RustAnalyzerBridge.epubInlineStyles(
            """<p style="text-align:center;color:red;font-weight:700;font-style:italic;text-decoration:underline line-through;font-size:120%;background-color:#abc">Hi</p><span style="display:none">Hide</span>""",
            "font-size:80%;color:green",
            "test.epubInlineStyles"
        )
        assertTrue(inlineHtml.contains("align=\"center\""))
        assertTrue(inlineHtml.contains("<font color=\"#FF0000\">Hi</font>"))
        assertTrue(inlineHtml.contains("<epubbgFFAABBCC>"))
        assertTrue(inlineHtml.contains("<big>"))
        assertTrue(inlineHtml.startsWith("<small><font color=\"#008000\">"))
        assertFalse(inlineHtml.contains("Hide"))
        val inheritedStyles = RustAnalyzerBridge.epubInheritedStyles(
            """<section><p style="font-weight:bold">A <span>B</span></p><p style="color:blue">C</p></section>""",
            "color:red;font-size:120%;text-align:center;background:#fff",
            "test.epubInheritedStyles"
        )
        assertTrue(inheritedStyles.contains("""<section style="color:red;font-size:120%;text-align:center">"""))
        assertTrue(inheritedStyles.contains("""<span style="font-weight:bold;color:red;font-size:120%;text-align:center">B</span>"""))
        assertTrue(inheritedStyles.contains("""<p style="color:blue;font-size:120%;text-align:center">C</p>"""))
        assertFalse(inheritedStyles.contains("background:#fff"))
        val generatedContent = RustAnalyzerBridge.epubGeneratedContent(
            """<body data-title="Book"><h1 class="title" title="One">Chapter</h1><p>Text</p></body>""",
            """[{"selector":"body","before":true,"declarations":[{"name":"content","value":"'Start ' attr(data-title)","important":false,"order":0},{"name":"color","value":"red","important":false,"order":1}]},{"selector":"h1.title","before":false,"declarations":[{"name":"content","value":"open-quote attr(title) close-quote counter(chapter)","important":false,"order":0},{"name":"font-weight","value":"bold","important":false,"order":1}]}]""",
            "test.epubGeneratedContent"
        )
        assertTrue(generatedContent.startsWith("""<span data-epub-generated="true" style="color:red">Start Book</span>"""))
        assertTrue(generatedContent.contains("""class="title""""))
        assertTrue(generatedContent.contains("""title="One""""))
        assertTrue(generatedContent.contains("""Chapter<span data-epub-generated="true" style="font-weight:bold">“One”1</span></h1>"""))
        val nativeDom = RustAnalyzerBridge.epubNativeDom(
            """<body><p class="note" align="right">A <span style="font-size:smaller">B</span></p><ruby>字<rp>(</rp><rt>zi</rt><rp>)</rp></ruby><a href="../nav.xhtml">Next</a></body>""",
            """[{"selector":"p.note","style":"color:blue;font-size:120%","specificity":11,"order":0,"declarations":[{"name":"color","value":"blue","important":false,"order":0},{"name":"font-size","value":"120%","important":false,"order":1}]},{"selector":"span","style":"font-weight:bold;background-image:url(../img/bg.png)","specificity":1,"order":1,"declarations":[{"name":"font-weight","value":"bold","important":false,"order":0},{"name":"background-image","value":"url(../img/bg.png)","important":false,"order":1}]}]""",
            "OPS/text/ch1.xhtml",
            "test.epubNativeDom"
        )
        val nativeParagraph = nativeDom.body.children.first { it.tagName == "p" }
        assertEquals("note", nativeParagraph.attributes["class"])
        assertEquals("blue", nativeParagraph.style.declarations["color"]?.value)
        assertEquals("right", nativeParagraph.style.declarations["text-align"]?.value)
        val nativeSpan = nativeParagraph.children.first { it.tagName == "span" }
        assertEquals("bold", nativeSpan.style.declarations["font-weight"]?.value)
        assertEquals("url(OPS/img/bg.png)", nativeSpan.style.declarations["background-image"]?.value)
        val rubyFallback = nativeDom.body.children.first { it.sourcePath == "body/body[1]" }
        assertEquals("span", rubyFallback.tagName)
        assertEquals("字（zi）", rubyFallback.children.first().text)
        assertEquals("OPS/nav.xhtml", nativeDom.body.children.first { it.tagName == "a" }.attributes["href"])
        val appliedCss = RustAnalyzerBridge.epubAppliedCss(
            """<body background="bg.png"><p class="note" style="color:green">A <span style="color:yellow">B</span></p><em>Keep</em></body>""",
            """[{"selector":"body","style":"background:#fff;color:black","specificity":1,"order":0,"declarations":[{"name":"background","value":"#fff","important":false,"order":0},{"name":"color","value":"black","important":false,"order":1}]},{"selector":"p.note","style":"color:blue;font-weight:bold","specificity":11,"order":1,"declarations":[{"name":"color","value":"blue","important":false,"order":0},{"name":"font-weight","value":"bold","important":false,"order":1}]},{"selector":"span","style":"color:red !important","specificity":1,"order":2,"declarations":[{"name":"color","value":"red","important":true,"order":0}]}]""",
            "test.epubAppliedCss"
        )
        assertEquals("background:#fff;color:black", appliedCss.bodyStyle)
        assertEquals("bg.png", appliedCss.bodyBackground)
        assertTrue(appliedCss.html.contains("<p "))
        assertTrue(appliedCss.html.contains("""class="note""""))
        assertTrue(appliedCss.html.contains("""style="color:green;font-weight:bold""""))
        assertTrue(appliedCss.html.contains("""<span style="color:red !important">B</span>"""))
        assertTrue(appliedCss.html.contains("<em>Keep</em>"))
        val resolvedLinks = RustAnalyzerBridge.epubResolvedLinks(
            """<p><a href="../note.xhtml#n1">Note</a><a href="#local">Local</a><a href="https://example.test/x">Web</a></p>""",
            "OPS/text/ch1.xhtml",
            "test.epubResolvedLinks"
        )
        assertTrue(resolvedLinks.contains("""href="OPS/note.xhtml#n1""""))
        assertTrue(resolvedLinks.contains("""href="#local""""))
        assertTrue(resolvedLinks.contains("""href="https://example.test/x""""))
        assertEquals(
            "../Images/bg cover.png",
            RustAnalyzerBridge.epubBodyBackgroundImage(
                """background-image: url("../Images/bg cover.png"); color: red""",
                "",
                "test.epubBodyBackgroundImage"
            )
        )
        val cssAssets = RustAnalyzerBridge.epubCssAssets(
            documentHtml = """<html><head><style>h1{color:red}</style><link rel="alternate stylesheet" href="head.css"></head><body></body></html>""",
            bodyHtml = """<section><style>p{font-weight:bold}</style><link rel="stylesheet" href="../body.css"></section>""",
            rulePath = "test.epubCssAssets"
        )
        assertEquals(4, cssAssets.assets.size)
        assertEquals("inline", cssAssets.assets[0].kind)
        assertEquals("h1{color:red}", cssAssets.assets[0].content)
        assertEquals("stylesheet", cssAssets.assets[1].kind)
        assertEquals("head.css", cssAssets.assets[1].href)
        assertEquals("p{font-weight:bold}", cssAssets.assets[2].content)
        assertEquals("../body.css", cssAssets.assets[3].href)
        assertFalse(cssAssets.html.contains("<style"))
        assertFalse(cssAssets.html.contains("stylesheet"))
        assertTrue(cssAssets.html.contains("<section>"))
        assertEquals(
            """<p align="center"><b>A</b>　BALT</p><p align="center"><i>C</i><br>D　<img src="pic.png"></p>""",
            RustAnalyzerBridge.htmlReadableTable(
                """<table align="center"><tr><th><strong>A</strong></th><td>B<img alt="ALT"></td></tr><tr><td><em>C</em><br>D</td><td><img src="pic.png"></td></tr></table>""",
                "test.htmlReadableTable"
            )
        )
        val renderFlags = RustAnalyzerBridge.htmlRenderFlags(
            """<div style="margin-top:1.2em;page-break-after:always;background:#fff"><p style="border:1px solid #333">Box</p><span><img src="pic.png"></span></div>""",
            "test.htmlRenderFlags"
        )
        assertEquals("div", renderFlags.tagName)
        assertTrue(renderFlags.isBlock)
        assertTrue(renderFlags.hasImage)
        assertTrue(renderFlags.hasBlockBoxStyle)
        assertTrue(renderFlags.hasBlockBoxDescendant)
        assertFalse(renderFlags.pageBreakBefore)
        assertTrue(renderFlags.pageBreakAfter)
        assertTrue(renderFlags.blockSpacingBefore)
        assertFalse(renderFlags.blockSpacingAfter)
        val pageBackground = RustAnalyzerBridge.htmlPageBackground(
            """<span data-epub-page-bg="epubbg11223344"></span><p>Text</p><img data-epub-background="true" src="bg.png"><img src="inline.png">""",
            "test.htmlPageBackground"
        )
        assertEquals("epubbg11223344", pageBackground.pageColor)
        assertEquals("bg.png", pageBackground.backgroundSrc)
        assertTrue(pageBackground.html.contains("<p>Text</p>"))
        assertTrue(pageBackground.html.contains("inline.png"))
        assertFalse(pageBackground.html.contains("data-epub-page-bg"))
        val imageInfo = RustAnalyzerBridge.htmlImageInfo(
            """<img src="pic.png" data-epub-background="true" data-legado-style="full" data-legado-click="go" width="10" style="width:50%">""",
            "test.htmlImageInfo"
        )
        assertEquals("pic.png", imageInfo.src)
        assertTrue(imageInfo.isBackground)
        assertEquals("full", imageInfo.style)
        assertEquals("10", imageInfo.width)
        assertEquals("go", imageInfo.click)
        val renderPlan = RustAnalyzerBridge.htmlRenderPlan(
            """<p>Alpha <img src="pic.png" data-legado-width="40"></p><table align="center"><tr><td>A</td><td>B</td></tr></table><div style="margin-top:1.2em;page-break-after:always;background:#fff">Box</div>""",
            classicEpub = true,
            rulePath = "test.htmlRenderPlan"
        )
        val renderPlanKinds = renderPlan.map { it.kind }
        assertTrue(renderPlanKinds.contains("image"))
        assertTrue(renderPlanKinds.contains("htmlText"))
        assertTrue(renderPlanKinds.contains("spacingBefore"))
        assertTrue(renderPlanKinds.contains("blockBox"))
        assertTrue(renderPlanKinds.contains("pageBreak"))
        assertEquals("pic.png", renderPlan.first { it.kind == "image" }.image.src)
        assertEquals("40", renderPlan.first { it.kind == "image" }.image.width)
        assertTrue(renderPlan.first { it.kind == "htmlText" }.html.contains("A　B"))
        val mobiHtml = RustAnalyzerBridge.mobiContentHtml(
            """<title>T</title><p>Keep</p><p style="display: none">Hide</p><img recindex="42" alt="x">""",
            rewriteRecindexImages = true,
            rulePath = "test.mobiContentHtml"
        )
        assertFalse(mobiHtml.contains("<title>"))
        assertFalse(mobiHtml.contains("Hide"))
        assertTrue(mobiHtml.contains("""<img src="recindex:42">"""))
        assertTrue("HTML textArray should use Rust", RustAnalyzerBridge.callCount("htmlTextArray") > 0)
        assertTrue("HTML charset should use Rust", RustAnalyzerBridge.callCount("htmlCharset") > 0)
        assertTrue("HTML format should use Rust", RustAnalyzerBridge.callCount("htmlFormat") > 0)
        assertTrue("HTML title should use Rust", RustAnalyzerBridge.callCount("htmlTitle") > 0)
        assertTrue("HTML alignment should use Rust", RustAnalyzerBridge.callCount("htmlFirstAlignment") > 0)
        assertTrue("EPUB native entry should use Rust", RustAnalyzerBridge.callCount("epubNativeEntry") > 0)
        assertTrue("EPUB readable title should use Rust", RustAnalyzerBridge.callCount("epubReadableTitle") > 0)
        assertTrue("EPUB book info should use Rust", RustAnalyzerBridge.callCount("epubBookInfo") > 0)
        assertTrue("EPUB footnote ids should use Rust", RustAnalyzerBridge.callCount("epubFootnoteIds") > 0)
        assertTrue("EPUB footnote target should use Rust", RustAnalyzerBridge.callCount("epubFootnoteTarget") > 0)
        assertTrue("EPUB readable lines should use Rust", RustAnalyzerBridge.callCount("epubReadableLines") > 0)
        assertTrue("EPUB body HTML should use Rust", RustAnalyzerBridge.callCount("epubBodyHtml") > 0)
        assertTrue("EPUB debug chapter HTML should use Rust", RustAnalyzerBridge.callCount("epubDebugChapterHtml") > 0)
        assertTrue("EPUB image options should use Rust", RustAnalyzerBridge.callCount("epubImageOptions") > 0)
        assertTrue("EPUB image page marks should use Rust", RustAnalyzerBridge.callCount("epubImagePageMarks") > 0)
        assertTrue("EPUB materialized images should use Rust", RustAnalyzerBridge.callCount("epubMaterializedImages") > 0)
        assertTrue("EPUB media placeholders should use Rust", RustAnalyzerBridge.callCount("epubMediaPlaceholders") > 0)
        assertTrue("EPUB inline styles should use Rust", RustAnalyzerBridge.callCount("epubInlineStyles") > 0)
        assertTrue("EPUB inherited styles should use Rust", RustAnalyzerBridge.callCount("epubInheritedStyles") > 0)
        assertTrue("EPUB generated content should use Rust", RustAnalyzerBridge.callCount("epubGeneratedContent") > 0)
        assertTrue("EPUB native DOM should use Rust", RustAnalyzerBridge.callCount("epubNativeDom") > 0)
        assertTrue("EPUB applied CSS should use Rust", RustAnalyzerBridge.callCount("epubAppliedCss") > 0)
        assertTrue("EPUB resolved links should use Rust", RustAnalyzerBridge.callCount("epubResolvedLinks") > 0)
        assertTrue("EPUB body background image should use Rust", RustAnalyzerBridge.callCount("epubBodyBackgroundImage") > 0)
        assertTrue("EPUB CSS assets should use Rust", RustAnalyzerBridge.callCount("epubCssAssets") > 0)
        assertTrue("HTML readable table should use Rust", RustAnalyzerBridge.callCount("htmlReadableTable") > 0)
        assertTrue("HTML render flags should use Rust", RustAnalyzerBridge.callCount("htmlRenderFlags") > 0)
        assertTrue("HTML page background should use Rust", RustAnalyzerBridge.callCount("htmlPageBackground") > 0)
        assertTrue("HTML image info should use Rust", RustAnalyzerBridge.callCount("htmlImageInfo") > 0)
        assertTrue("HTML render plan should use Rust", RustAnalyzerBridge.callCount("htmlRenderPlan") > 0)
        assertTrue("MOBI content HTML should use Rust", RustAnalyzerBridge.callCount("mobiContentHtml") > 0)
    }

    @Test
    fun rustEvalEntrypointsUseRquickjsSemantics() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val source = minimalSource().apply {
            jsLib = "function suffix(value){ return value + '-lib'; }"
        }
        assertEquals("ok-lib", source.evalJS("suffix(result)") {
            put("result", "ok")
        })

        assertEquals(
            "needle:3:https://example.com",
            RustAnalyzerBridge.evalJs(
                source = source,
                script = "key + ':' + page + ':' + baseUrl",
                key = "needle",
                page = 3,
                baseUrl = "https://example.com",
                rulePath = "test.eval.urlContext"
            )
        )

        assertEquals(
            "item-rule",
            RustAnalyzerBridge.evalJs(
                source = source,
                script = "result + '-rule'",
                result = "item",
                rulePath = "test.eval.ruleContext"
            )
        )

        assertEquals(
            "https://chapter.example/1|https://img.example/a.jpg|Image Book|Image Chapter",
            source.evalJS("baseUrl + '|' + result + '|' + book.name + '|' + chapter.title") {
                put("baseUrl", "https://chapter.example/1")
                put("result", "https://img.example/a.jpg")
                put("book", Book(name = "Image Book"))
                put("chapter", BookChapter(title = "Image Chapter"))
            }
        )

        val appMetadata = RustAnalyzerBridge.evalJs(
            source = source,
            script = """
                [
                  java.getWebViewUA(),
                  java.androidId(),
                  java.getAppVersionName(),
                  java.getAppVersionCode(),
                  java.getAppVariant()
                ].join('|')
            """.trimIndent(),
            rulePath = "test.eval.appMetadata"
        )
        assertEquals(
            listOf(
                WebSettings.getDefaultUserAgent(appCtx),
                AppConst.androidId,
                AppConst.appInfo.versionName,
                AppConst.appInfo.versionCode.toString(),
                AppConst.appInfo.appVariant
            ).joinToString("|"),
            appMetadata
        )
        assertFalse("Rust metadata placeholder must not leak into Android eval", appMetadata.contains("0.0.0-rust"))

        val resolvedRssUrl = RustAnalyzerBridge.resolveUrl(
            url = "article.html,{\"headers\":{\"X-Rss\":\"1\"}}",
            source = RssSource(
                sourceUrl = "https://rss.example/feed/",
                sourceName = "RSS URL state"
            ),
            baseUrl = "https://rss.example/feed/index.xml",
            rulePath = "test.rss.loadUrl"
        )
        assertEquals("https://rss.example/feed/article.html", resolvedRssUrl.url)
        assertTrue(resolvedRssUrl.headers.any { it == listOf("X-Rss", "1") })

        val fetchedLocalHtml = RustAnalyzerBridge.fetchText(
            url = "data:text/html,%3Cp%3Ebridge%3C%2Fp%3E",
            source = null,
            rulePath = "test.webviewModel.fetchText"
        )
        assertEquals("<p>bridge</p>", fetchedLocalHtml.body)

        val fetchedRssImage = RustAnalyzerBridge.fetchRawUrl(
            "data:image/png;base64,AQIDBA==",
            "ReadRssViewModel.saveImage"
        )
        assertEquals(listOf(1, 2, 3, 4), fetchedRssImage.body.map { it.toInt() and 0xff })
        assertEquals(
            listOf(5, 6, 7, 8),
            WebImageBytes.fetch(
                "data:image/png;base64,BQYHCA==",
                source = null,
                rulePath = "WebImageBytes.instrumented"
            ).map { it.toInt() and 0xff }
        )

        val legacyExploreSource = minimalSource().apply {
            bookSourceUrl = "https://explore-kinds.example/"
            bookSourceName = "Rust Explore Kinds"
            exploreUrl = "分类一::/one&&分类二::/two"
        }
        RustAnalyzerBridge.resetCallCounts()
        val legacyKinds = legacyExploreSource.exploreKinds()
        assertEquals(listOf("分类一", "分类二"), legacyKinds.map { it.title })
        assertEquals(listOf("/one", "/two"), legacyKinds.map { it.url })
        assertTrue("exploreKinds should use Rust explore operation", RustAnalyzerBridge.callCount("explore") > 0)

        assertEquals(
            "remote text",
            RustRemoteFetch.text(
                "data:text/plain,remote%20text",
                "test.remoteFetch.text"
            )
        )
        val remoteBytes = RustRemoteFetch.bytes(
            "data:application/octet-stream;base64,CQo=",
            "test.remoteFetch.bytes"
        )
        assertEquals(200, remoteBytes.code)
        assertEquals(listOf(9, 10), remoteBytes.body.map { it.toInt() and 0xff })
        val observedRequest = CompletableDeferred<String>()
        val noUaUrl = serveOnce("no-ua", "text/plain", observedRequest)
        assertEquals(
            "no-ua",
            RustRemoteFetch.text("$noUaUrl#requestWithoutUA", "test.remoteFetch.noUa")
        )
        assertTrue(
            "#requestWithoutUA should send User-Agent: null through Rust request engine",
            observedRequest.await().lines().any { it.equals("User-Agent: null", ignoreCase = true) }
        )

        val oldBookTreeUri = AppConfig.defaultBookTreeUri
        val localBookDir = File(
            ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
            "rust-local-book-${System.nanoTime()}"
        )
        try {
            assertTrue(localBookDir.mkdirs())
            AppConfig.defaultBookTreeUri = Uri.fromFile(localBookDir).toString()

            RustAnalyzerBridge.resetCallCounts()
            val dataBookUri = LocalBook.saveBookFile(
                "data:text/plain;base64,cnVzdC1kYXRhLWJvb2s=",
                "rust-data-book.txt"
            )
            assertEquals("rust-data-book", File(dataBookUri.path!!).readText())
            assertTrue("LocalBook.saveBookFile data URL should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") > 0)

            val observedLocalBookRequest = CompletableDeferred<String>()
            val localBookUrl = serveOnce(
                body = "rust-http-book",
                contentType = "text/plain",
                observedRequest = observedLocalBookRequest
            )
            RustAnalyzerBridge.resetCallCounts()
            val httpBookUri = LocalBook.saveBookFile(
                localBookUrl,
                "rust-http-book.txt",
                minimalSource().apply {
                    bookSourceUrl = localBookUrl
                    bookSourceName = "Rust LocalBook Source"
                    header = """{"X-LocalBook-Rust":"1"}"""
                }
            )
            assertEquals("rust-http-book", File(httpBookUri.path!!).readText())
            assertTrue("LocalBook.saveBookFile HTTP should use Rust raw fetch", RustAnalyzerBridge.callCount("fetchRaw") > 0)
            val localBookRequestText = observedLocalBookRequest.await()
            assertTrue(
                localBookRequestText,
                localBookRequestText.lineSequence()
                    .any { it.equals("X-LocalBook-Rust: 1", ignoreCase = true) }
            )
        } finally {
            AppConfig.defaultBookTreeUri = oldBookTreeUri
            localBookDir.deleteRecursively()
        }

        val ruleUpdateSource = BookSource(
            bookSourceUrl = "https://rule-update.example/source",
            bookSourceName = "Rust RuleUpdate Source",
            lastUpdateTime = 1
        )
        val ruleUpdateJson = GSON.toJson(listOf(ruleUpdateSource))
        val ruleUpdateUrl = "data:application/json;base64,${
            Base64.encodeToString(ruleUpdateJson.toByteArray(), Base64.NO_WRAP)
        }"
        RuleUpdate.cacheBookSourceMap.remove(ruleUpdateUrl)
        assertTrue(
            RuleUpdate.cacheSource(
                RuleSub(
                    name = "Rust RuleUpdate",
                    url = ruleUpdateUrl,
                    type = 0,
                    update = 0,
                    updateInterval = 0,
                    silentUpdate = false
                )
            )
        )
        assertEquals(
            "https://rule-update.example/source",
            RuleUpdate.cacheBookSourceMap.remove(ruleUpdateUrl)?.firstOrNull()?.bookSourceUrl
        )

        val observedWebViewRequest = CompletableDeferred<String>()
        val webViewUrl = serveOnce(
            body = "<html><head></head><body>web</body></html>",
            contentType = "text/html; charset=UTF-8",
            observedRequest = observedWebViewRequest,
            responseHeaders = listOf(
                "Set-Cookie" to "webview_a=1",
                "Set-Cookie" to "webview_b=2"
            )
        )
        val webViewCookies = arrayListOf<String>()
        val webViewResponse = fetchModifiedContentWithRust(
            url = webViewUrl,
            request = object : WebResourceRequest {
                override fun getUrl(): Uri = Uri.parse(webViewUrl)
                override fun isForMainFrame(): Boolean = true
                override fun isRedirect(): Boolean = false
                override fun hasGesture(): Boolean = false
                override fun getMethod(): String = "GET"
                override fun getRequestHeaders(): MutableMap<String, String> =
                    linkedMapOf("X-WebView-Rust" to "1")
            },
            source = minimalSource().apply {
                bookSourceUrl = webViewUrl
                bookSourceName = "Rust WebView Fetch Source"
            },
            cookie = "session=webview",
            rulePath = "test.webViewRustFetch",
            setCookie = { webViewCookies += it }
        )
        assertNotNull(webViewResponse)
        val webViewBody = webViewResponse!!.data.readBytes().toString(Charsets.UTF_8)
        assertTrue(webViewBody.contains("<script src=\"https://", ignoreCase = true))
        assertEquals(listOf("webview_a=1", "webview_b=2"), webViewCookies)
        val webViewRequestText = observedWebViewRequest.await()
        assertTrue(
            webViewRequestText,
            webViewRequestText.lineSequence().any { it.equals("X-WebView-Rust: 1", ignoreCase = true) }
        )
        assertTrue(
            webViewRequestText,
            webViewRequestText.lineSequence().any { it.equals("Cookie: session=webview", ignoreCase = true) }
        )

        val rssJs = RssJsExtensions(null, source)
        rssJs.setContent(
            """<div class="item"><a href="/a">Alpha</a></div><div class="item"><a href="/b">Beta</a></div>""",
            "https://rss.example/root/"
        )
        assertEquals("Alpha\nBeta", rssJs.getString(".item@text"))
        assertEquals(2, rssJs.getElements(".item").size)
        assertEquals(
            "https://rss.example/root/|Alpha\nBeta",
            rssJs.analyzeRule.evalJS("baseUrl + '|' + java.getString('.item@text')")
        )

        val exportBook = Book(name = "ExportName", author = "ExportAuthor")
        assertEquals(
            "ExportName-ExportAuthor.txt",
            exportBook.getExportFileName("txt", "name + '-' + author")
        )
        assertEquals(
            "ExportName-2.txt",
            exportBook.getExportFileName("txt", 2, "name + '-' + epubIndex")
        )

        val regexResult = "abc123def".replace(
            name = "rust-regex-js",
            regex = "\\d+".toRegex(),
            replacement = "@js:'N' + result",
            timeout = 3_000
        )
        assertEquals("abcN123def", regexResult)

        val dictRule = DictRule(
            name = "Rust Dict",
            urlRule = "data:text/html,%3Cdiv%20class%3D%22def%22%3Eword-hit%3C%2Fdiv%3E",
            showRule = ".def@text"
        )
        RustAnalyzerBridge.resetCallCounts()
        assertEquals("word-hit", dictRule.search("word"))
        assertTrue("DictRule.search should use Rust dictSearch", RustAnalyzerBridge.callCount("dictSearch") > 0)
        RustAnalyzerBridge.resetCallCounts()
        dictRule.buttonClick("dict-result", "if (result !== 'dict-result') throw 'bad dict result'; 'ok';")
        assertTrue("DictRule.buttonClick should use Rust eval", RustAnalyzerBridge.callCount("eval") > 0)

        val coverHtml = """<html><body><img class="cover" src="https://img.example/cover.jpg"></body></html>"""
        BookCover.saveCoverRule(
            BookCover.CoverRule(
                enable = true,
                searchUrl = "data:text/html;base64,${
                    Base64.encodeToString(coverHtml.toByteArray(), Base64.NO_WRAP)
                }",
                coverRule = ".cover@src"
            )
        )
        try {
            RustAnalyzerBridge.resetCallCounts()
            assertEquals(
                "https://img.example/cover.jpg",
                BookCover.searchCover(Book(name = "Cover Book"))
            )
            assertTrue("BookCover.searchCover should use Rust coverSearch", RustAnalyzerBridge.callCount("coverSearch") > 0)
        } finally {
            BookCover.delCoverRule()
        }

        val observedUploadRequest = CompletableDeferred<String>()
        val uploadUrl = serveUploadOnce(
            body = """{"data":"https://download.example/rule.json"}""",
            contentType = "application/json",
            observedRequest = observedUploadRequest
        )
        RustAnalyzerBridge.resetCallCounts()
        assertEquals(
            "https://download.example/rule.json",
            DirectLinkUpload.upLoad(
                fileName = "rule.json",
                file = """{"name":"source"}""",
                contentType = "application/json",
                rule = DirectLinkUpload.Rule(
                    uploadUrl = "$uploadUrl,{\"method\":\"POST\",\"body\":{\"file\":\"fileRequest\"},\"type\":\"multipart/form-data\"}",
                    downloadUrlRule = "$.data",
                    summary = "Rust direct upload test"
                )
            )
        )
        assertTrue("DirectLinkUpload.upLoad should use Rust directLinkUpload", RustAnalyzerBridge.callCount("directLinkUpload") > 0)
        val uploadRequestText = observedUploadRequest.await()
        assertTrue(uploadRequestText.startsWith("POST /remote HTTP/1.1"))
        assertTrue(uploadRequestText.contains("multipart/form-data"))
        assertTrue(uploadRequestText.contains("name=\"file\"; filename=\"rule.json\""))
        assertTrue(uploadRequestText.contains("application/json"))
        assertTrue(uploadRequestText.contains("""{"name":"source"}"""))

        val textFileToc = arrayListOf(BookChapter(title = "上一章", start = 0, end = 88))
        val lastVolumeTitle = TextFile.MutableRef("旧卷")
        val textFileTitle = evalTxtTocReplacementWithRust(
            replaceBook = ReplaceBook(bookUrl = "local://txt", name = "本地书名", author = "作者"),
            lastVolumeTitle = lastVolumeTitle,
            content = "第1章 原题",
            jsStr = """
                java.putVolume(book.name + '-' + index);
                result.replace('原题', prevTitle + '-' + prevLength + '-' + lastVolumeTitle);
            """.trimIndent(),
            index = 2,
            prevTitle = "上一章",
            prevLength = 12,
            toc = textFileToc
        )
        assertEquals("第1章 上一章-12-本地书名-2", textFileTitle)
        assertEquals("本地书名-2", lastVolumeTitle.value)
        assertEquals(2, textFileToc.size)
        assertEquals("本地书名-2", textFileToc.last().title)
        assertTrue(textFileToc.last().isVolume)
        assertEquals(88L, textFileToc.last().start)

        val rssSource = RssSource(
            sourceUrl = "https://rss.example.com/",
            sourceName = "RSS Eval",
            jsLib = "function rssSuffix(value){ return value + '-rss'; }"
        )
        assertEquals("feed-rss", rssSource.evalJS("rssSuffix(result)") {
            put("result", "feed")
        })
        rssSource.header = "@js:JSON.stringify({'X-Rust-Rss': rssSuffix('header')})"
        assertEquals("header-rss", rssSource.getHeaderMap()["X-Rust-Rss"])

        val rssViewModel = RssViewModel(ApplicationProvider.getApplicationContext())
        val singleUrl = CompletableDeferred<String>()
        rssViewModel.getSingleUrl(
            rssSource.copy(sortUrl = "@js:'Rss::' + rssSuffix('sort')"),
            onSuccess = { singleUrl.complete(it) },
            onError = { singleUrl.completeExceptionally(it) }
        )
        assertEquals("sort-rss", withTimeout(5_000) { singleUrl.await() })

        val startHtml = CompletableDeferred<String>()
        val startHtmlSource = rssSource.copy(startHtml = "@js:'<main>' + rssSuffix('html') + '</main>'")
        assertEquals("<main>html-rss</main>", startHtmlSource.resolveStartHtmlByRustEval())
        rssViewModel.launchRssWithHtml(
            startHtmlSource,
            noStartHtml = { startHtml.completeExceptionally(AssertionError("startHtml was blank")) },
            isStartHtml = { startHtml.complete(it) },
            onError = { startHtml.completeExceptionally(it) }
        )
        assertEquals("<main>html-rss</main>", withTimeout(5_000) { startHtml.await() })
        assertEquals(
            "true",
            evalRssShouldOverrideUrlLoadingByRust(
                source = rssSource,
                java = RssJsExtensions(null, rssSource),
                js = "url.indexOf('/block') >= 0 && typeof java.getString === 'function'",
                url = "https://rss.example.com/block"
            )
        )

        val callbackSource = minimalSource().apply {
            bookSourceUrl = "https://callback.example/"
            bookSourceName = "Rust Callback Source"
            eventListener = true
            ruleContent = ContentRule(
                callBackJs = """
                    source.setVariable(JSON.stringify({
                      event: event,
                      result: result,
                      book: book.name,
                      chapter: chapter.title
                    }));
                    true;
                """.trimIndent()
            )
        }
        val callbackBook = Book(name = "Callback Book", bookUrl = "https://callback.example/book")
        val callbackChapter = BookChapter(title = "Callback Chapter")
        SourceCallBack.callBackBook(
            SourceCallBack.SAVE_READ,
            callbackSource,
            callbackBook,
            callbackChapter,
            "42"
        )
        val callbackState = withTimeout(5_000) {
            while (true) {
                val value = callbackSource.evalJS("source.getVariable()").toString()
                if (value.contains("Callback Book")) return@withTimeout value
                delay(100)
            }
            error("unreachable")
        }
        val callbackJson = JsonParser.parseString(callbackState).asJsonObject
        assertEquals(SourceCallBack.SAVE_READ, callbackJson.get("event").asString)
        assertEquals("42", callbackJson.get("result").asString)
        assertEquals("Callback Book", callbackJson.get("book").asString)
        assertEquals("Callback Chapter", callbackJson.get("chapter").asString)

        val videoSource = minimalSource().apply {
            bookSourceUrl = "https://video-button.example/"
            bookSourceName = "Rust Video Button Source"
        }
        evalVideoButtonClickByRust(
            source = videoSource,
            book = Book(name = "Video Button Book"),
            java = SourceLoginJsExtensions(null, videoSource),
            click = """
                source.setVariable(JSON.stringify({
                  book: book.name,
                  javaType: typeof java.openUrl
                }));
            """.trimIndent()
        )
        val videoButtonJson = JsonParser.parseString(
            videoSource.evalJS("source.getVariable()").toString()
        ).asJsonObject
        assertEquals("Video Button Book", videoButtonJson.get("book").asString)
        assertEquals("function", videoButtonJson.get("javaType").asString)

        val bookInfoSource = minimalSource().apply {
            bookSourceUrl = "https://book-info-button.example/"
            bookSourceName = "Rust BookInfo Button Source"
        }
        evalBookInfoButtonClickByRust(
            source = bookInfoSource,
            book = Book(name = "BookInfo Button Book"),
            java = SourceLoginJsExtensions(null, bookInfoSource),
            click = """
                source.setVariable(JSON.stringify({
                  book: book.name,
                  javaType: typeof java.openUrl
                }));
            """.trimIndent()
        )
        val bookInfoButtonJson = JsonParser.parseString(
            bookInfoSource.evalJS("source.getVariable()").toString()
        ).asJsonObject
        assertEquals("BookInfo Button Book", bookInfoButtonJson.get("book").asString)
        assertEquals("function", bookInfoButtonJson.get("javaType").asString)

        val exploreSource = minimalSource().apply {
            bookSourceUrl = "https://explore-action.example/"
            bookSourceName = "Rust Explore Action Source"
        }
        val exploreInfoMap = InfoMap(exploreSource.bookSourceUrl)
        assertEquals(
            "https://explore-action.example/|object",
            evalExploreUiJsByRust(
                exploreSource,
                exploreInfoMap,
                "source.getKey() + '|' + typeof infoMap"
            )
        )
        evalExploreButtonClickByRust(
            source = exploreSource,
            infoMap = exploreInfoMap,
            java = SourceLoginJsExtensions(null, exploreSource),
            jsStr = """
                source.setVariable(JSON.stringify({
                  infoMapType: typeof infoMap,
                  javaType: typeof java.openUrl
                }));
            """.trimIndent()
        )
        val exploreActionJson = JsonParser.parseString(
            exploreSource.evalJS("source.getVariable()").toString()
        ).asJsonObject
        assertEquals("object", exploreActionJson.get("infoMapType").asString)
        assertEquals("function", exploreActionJson.get("javaType").asString)

        val loginHeaderSource = minimalSource().apply {
            bookSourceUrl = "https://login-header.example/"
            bookSourceName = "Rust Login Header Source"
            jsLib = "function loginHeaderValue(){ return 'login-header-rust'; }"
            header = "@js:JSON.stringify({'X-Login-Rust': loginHeaderValue()})"
        }
        assertEquals("login-header-rust", resolveLoginHeaderMapByRust(loginHeaderSource)["X-Login-Rust"])

        val loginUiSource = minimalSource().apply {
            bookSourceUrl = "https://login-ui.example/"
            bookSourceName = "Rust Login UI Source"
        }
        val loginJs = "function decorate(value){ return value + '-login'; }"
        assertEquals(
            "user-login|Login UI Book|Login UI Chapter",
            evalLoginUiJsByRust(
                source = loginUiSource,
                loginJs = loginJs,
                jsStr = "decorate(result.user) + '|' + book.name + '|' + chapter.title",
                result = mapOf("user" to "user"),
                book = Book(name = "Login UI Book"),
                chapter = BookChapter(title = "Login UI Chapter")
            )
        )
        evalLoginButtonClickByRust(
            source = loginUiSource,
            loginJs = loginJs,
            buttonFunctionJs = """
                source.setVariable(JSON.stringify({
                  result: decorate(result.user),
                  book: book.name,
                  chapter: chapter.title,
                  isLongClick: isLongClick,
                  javaType: typeof java.openUrl
                }));
            """.trimIndent(),
            java = SourceLoginJsExtensions(null, loginUiSource),
            result = mapOf("user" to "button"),
            book = Book(name = "Login Button Book"),
            chapter = BookChapter(title = "Login Button Chapter"),
            isLongClick = true
        )
        val loginButtonJson = JsonParser.parseString(
            loginUiSource.evalJS("source.getVariable()").toString()
        ).asJsonObject
        assertEquals("button-login", loginButtonJson.get("result").asString)
        assertEquals("Login Button Book", loginButtonJson.get("book").asString)
        assertEquals("Login Button Chapter", loginButtonJson.get("chapter").asString)
        assertTrue(loginButtonJson.get("isLongClick").asBoolean)
        assertEquals("function", loginButtonJson.get("javaType").asString)

        val readBookSource = minimalSource().apply {
            bookSourceUrl = "https://read-book-action.example/"
            bookSourceName = "Rust ReadBook Action Source"
        }
        val readBook = Book(
            name = "ReadBook Action Book",
            bookUrl = "https://read-book-action.example/book"
        )
        val readChapter = BookChapter(
            title = "ReadBook Action Chapter",
            url = "https://read-book-action.example/chapter"
        )
        assertEquals(
            "https://pay.example/ReadBook%20Action%20Chapter",
            evalReadBookPayActionByRust(
                source = readBookSource,
                book = readBook,
                chapter = readChapter,
                java = SourceLoginJsExtensions(null, readBookSource),
                payAction = """
                    if (result !== null || src !== null) throw 'unexpected pay bindings';
                    'https://pay.example/' + encodeURIComponent(title);
                """.trimIndent()
            )
        )
        evalReadBookImageClickByRust(
            source = readBookSource,
            book = readBook,
            chapter = readChapter,
            java = SourceLoginJsExtensions(null, readBookSource),
            click = """
                source.setVariable(JSON.stringify({
                  result: result,
                  book: book.name,
                  chapter: chapter.title,
                  javaType: typeof java.openUrl
                }));
            """.trimIndent(),
            src = "https://img.example/pic.jpg"
        )
        val readBookActionJson = JsonParser.parseString(
            readBookSource.evalJS("source.getVariable()").toString()
        ).asJsonObject
        assertEquals("https://img.example/pic.jpg", readBookActionJson.get("result").asString)
        assertEquals("ReadBook Action Book", readBookActionJson.get("book").asString)
        assertEquals("ReadBook Action Chapter", readBookActionJson.get("chapter").asString)
        assertEquals("function", readBookActionJson.get("javaType").asString)

        val method = LocalBook::class.java.getDeclaredMethod("analyzeNameAuthor", String::class.java)
        method.isAccessible = true
        val oldImportRule = io.legado.app.help.config.AppConfig.bookImportFileName
        try {
            io.legado.app.help.config.AppConfig.bookImportFileName = """
                var parts = src.split('-');
                var name = parts[0];
                var author = parts[1];
            """.trimIndent()
            @Suppress("UNCHECKED_CAST")
            val pair = method.invoke(LocalBook, "Imported-Writer.txt") as Pair<String, String>
            assertEquals("Imported", pair.first)
            assertEquals("Writer", pair.second)
        } finally {
            io.legado.app.help.config.AppConfig.bookImportFileName = oldImportRule
        }
    }

    @Test
    fun guangyuLoginAndExploreActionsPersistThroughRustEval() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val source = readSource("bookSource_光遇聚合.json")

        source.evalJS(
            """
            function hasPlatformBoundary() {
                java.startBrowserAwait('https://example.com/register', 'register');
            }
            source.setVariable(JSON.stringify({ "线路": "https://v2.gyks.cf" }));
            """.trimIndent()
        )
        val rustSourceVariable = source.evalJS("source.getVariable()").toString()
        assertTrue(
            "dead platform UI function definitions must not block non-UI source variable actions",
            rustSourceVariable.contains("https://v2.gyks.cf")
        )
        assertEquals(
            "java.reLoginView must be exposed to Rust/rquickjs so source environment checks match Android UI builds",
            "function",
            source.evalJS("typeof java.reLoginView").toString()
        )
        assertEquals(
            "光遇 checkEnv should detect the Android UI-capable Rust bridge as 改版",
            "改版",
            source.evalJS("checkEnv()").toString()
        )
        assertTrue(
            "光遇 dynamic jsLib/source-variable hosts must keep WebView cookies available in Rust eval/content session",
            source.evalJS("getToken()").toString().length > 10
        )
        assertEquals(
            "Android eval bindings must preserve original login UI scope",
            "rust@example.com|Scoped Book|Scoped Chapter|true",
            source.evalJS("result.邮箱 + '|' + book.name + '|' + chapter.title + '|' + isLongClick") {
                put("java", SourceLoginJsExtensions(null, source))
                put("result", mapOf("邮箱" to "rust@example.com"))
                put("book", Book(name = "Scoped Book"))
                put("chapter", BookChapter(title = "Scoped Chapter"))
                put("isLongClick", true)
            }.toString()
        )
        source.evalJS("source.putLoginInfo(result);") {
            put("java", SourceLoginJsExtensions(null, source))
            put("result", mapOf("邮箱" to "object@example.com", "密码" to "object-secret"))
        }
        val objectLoginInfo = JsonParser.parseString(
            source.evalJS("JSON.stringify(source.getLoginInfoMap())").toString()
        ).asJsonObject
        assertEquals("object@example.com", objectLoginInfo.get("邮箱").asString)
        assertEquals("object-secret", objectLoginInfo.get("密码").asString)
        assertEquals(
            "光遇 login button should not be stopped by java.longToast(1) or Android UI toast dispatch",
            "false",
            source.evalJS("${source.getLoginJs()}\nremoveAllCookies(); login(true)") {
                put("java", SourceLoginJsExtensions(null, source))
                put("result", mapOf("邮箱" to "", "密码" to ""))
            }.toString()
        )
        assertEquals(
            "光遇 checkStatus button should provide feedback instead of silently failing when not logged in",
            "ok",
            source.evalJS("${source.getLoginJs()}\nremoveAllCookies(); checkStatus(); 'ok'") {
                put("java", SourceLoginJsExtensions(null, source))
                put("result", mapOf("邮箱" to "", "密码" to ""))
            }.toString()
        )
        val awaitUrl = "https://example.com/login"
        val unblockVerification = launch(Dispatchers.IO) {
            delay(750)
            SourceVerificationHelp.setResult(source.getKey(), "<html>verified</html>", awaitUrl)
        }
        val awaitBody = source.evalJS("java.startBrowserAwait('$awaitUrl', 'Login').body()")
            .toString()
        unblockVerification.join()
        assertEquals(
            "startBrowserAwait did not return Android WebView verification body to Rust JS",
            "<html>verified</html>",
            awaitBody
        )

        source.evalJS("setVariable('发现页类型', '听书', false)")
        val rustExploreTypeVariable = source.evalJS("source.getVariable()").toString()
        assertTrue(
            "explore select action did not persist source variable through Rust eval",
            rustExploreTypeVariable.contains("\"发现页类型\":\"听书\"") ||
                    rustExploreTypeVariable.contains("\"发现页类型\": \"听书\"")
        )

        source.evalJS("${source.getLoginJs()}\nsetFindSource()") {
            put("result", mapOf("发现页来源(支持的平台请前往源变量中查看)" to "七猫"))
        }
        val rustFindSourceVariable = source.evalJS("source.getVariable()").toString()
        assertTrue(
            "login UI button action did not update source variable",
            rustFindSourceVariable.contains("\"发现页来源\":\"七猫\"") ||
                    rustFindSourceVariable.contains("\"发现页来源\": \"七猫\"")
        )

        source.evalJS("source.putLoginInfo(JSON.stringify(result));") {
            put("result", mapOf("邮箱" to "rust@example.com", "密码" to "secret"))
        }
        val loginInfo = JsonParser.parseString(
            source.evalJS("JSON.stringify(source.getLoginInfoMap())").toString()
        ).asJsonObject
        assertEquals("rust@example.com", loginInfo.get("邮箱").asString)
        assertEquals("secret", loginInfo.get("密码").asString)
    }

    @Test
    fun xiguaRssSearchUsesRustEvalWithAndroidWebViewBoundary() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val source = readRssSource("rssSource_西瓜卡通.json")
        appDb.rssSourceDao.delete(source.sourceUrl)
        appDb.rssSourceDao.insert(source)
        seedXiguaActivatedSession(source)
        RustAnalyzerBridge.resetCallCounts()

        val (articles, _) = withTimeout(90_000) {
            Rss.getArticlesAwait(
                sortName = "搜索",
                sortUrl = source.searchUrl ?: source.sourceUrl,
                rssSource = source,
                page = 1,
                key = "柯南"
            )
        }

        assertTrue("西瓜卡通 RSS search returned no articles", articles.isNotEmpty())
        assertTrue(
            "西瓜卡通 RSS search did not execute through Rust eval",
            RustAnalyzerBridge.callCount("rssArticles") > 0
        )
    }

    @Test
    fun xiguaRssContentUsesRustEvalWithAndroidWebViewBoundary() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val source = readRssSource("rssSource_西瓜卡通.json")
        appDb.rssSourceDao.delete(source.sourceUrl)
        appDb.rssSourceDao.insert(source)
        seedXiguaActivatedSession(source)
        RustAnalyzerBridge.resetCallCounts()

        val article = withTimeout(90_000) {
            val (articles, _) = Rss.getArticlesAwait(
                sortName = "搜索",
                sortUrl = source.searchUrl ?: source.sourceUrl,
                rssSource = source,
                page = 1,
                key = "柯南"
            )
            articles.firstOrNull()
        }
        assertNotNull("西瓜卡通 RSS search returned no article for content smoke", article)

        val content = withTimeout(90_000) {
            Rss.getContentAwait(
                rssArticle = article!!,
                ruleContent = source.ruleContent ?: "",
                rssSource = source
            )
        }

        assertTrue("西瓜卡通 RSS content is blank", content.isNotBlank())
        assertTrue(
            "西瓜卡通 RSS content did not look like the expected video HTML",
            content.contains("<!DOCTYPE html>", ignoreCase = true) &&
                    content.contains("<html", ignoreCase = true) &&
                    content.contains("<video", ignoreCase = true) &&
                    content.contains("video-container", ignoreCase = true)
        )
        assertTrue(
            "西瓜卡通 RSS content did not execute through Rust eval",
            RustAnalyzerBridge.callCount("rssContent") > 0
        )
    }

    @Test
    fun rssContentUsesCallSiteRuleContentOverrideThroughRust() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val source = RssSource(
            sourceUrl = "https://example.test/rss",
            sourceName = "Rust RSS Override",
            ruleContent = "<js>'source-rule'</js>"
        )
        val article = RssArticle(
            origin = source.sourceUrl,
            title = "Override",
            link = "data:text/html,%3Chtml%3E%3Cbody%3E%3Ch1%3EOverride%3C%2Fh1%3E%3C%2Fbody%3E%3C%2Fhtml%3E"
        )

        RustAnalyzerBridge.resetCallCounts()
        val content = Rss.getContentAwait(
            rssArticle = article,
            ruleContent = "<js>'override:' + java.getString('h1@text')</js>",
            rssSource = source
        )

        assertEquals("override:Override", content)
        assertTrue("RSS content override should use Rust rssContent", RustAnalyzerBridge.callCount("rssContent") > 0)
    }

    @Test
    fun xiguaRssRefreshSortCacheReloadsCategoriesThroughRustEval() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val source = readRssSource("rssSource_西瓜卡通.json")
        appDb.rssSourceDao.delete(source.sourceUrl)
        appDb.rssSourceDao.insert(source)
        seedXiguaActivatedSession(source)
        RustAnalyzerBridge.resetCallCounts()

        val sorts = withTimeout(90_000) {
            source.removeSortCache()
            source.sortUrls()
        }

        assertTrue("西瓜卡通 RSS sort refresh returned no categories", sorts.isNotEmpty())
        assertTrue(
            "西瓜卡通 RSS sort refresh only returned the source fallback URL",
            sorts.size > 1 || sorts.first().first.isNotBlank()
        )
        assertTrue(
            "西瓜卡通 RSS sort refresh did not execute through Rust eval",
            RustAnalyzerBridge.callCount("rssSorts") > 0
        )
    }

    @Test
    fun xiguaRssGeneratedCategoryUrlUsesSortScopeHelpersThroughRustEval() = runBlocking {
        prepareRustAnalyzerFixtures()
        assertTrue("RustAnalyzerBridge must be available", RustAnalyzerBridge.isAvailable)

        val source = readRssSource("rssSource_西瓜卡通.json")
        appDb.rssSourceDao.delete(source.sourceUrl)
        appDb.rssSourceDao.insert(source)
        seedXiguaActivatedSession(source)
        RustAnalyzerBridge.resetCallCounts()

        val sort = withTimeout(90_000) {
            source.removeSortCache()
            source.sortUrls()
                .firstOrNull { it.first.isNotBlank() && it.second.contains("{{") }
        }
        assertNotNull("西瓜卡通 RSS sort refresh did not produce a generated category URL", sort)

        val (articles, _) = withTimeout(90_000) {
            Rss.getArticlesAwait(
                sortName = sort!!.first,
                sortUrl = sort.second,
                rssSource = source,
                page = 1,
                key = null
            )
        }

        assertTrue("西瓜卡通 generated category URL returned no articles", articles.isNotEmpty())
        assertTrue(
            "西瓜卡通 generated category URL did not execute through Rust eval",
            RustAnalyzerBridge.callCount("rssArticles") > 0
        )
    }

    private fun prepareRustAnalyzerFixtures() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "rust-analyzer").deleteRecursively()
        RustAnalyzerBridge.configurePersistentStore(context)
        val cacheDir = File(context.filesDir, "legado-http-cache")
        copyHttpCacheAssets(cacheDir)
        Os.setenv("LEGADO_HTTP_CACHE_DIR", cacheDir.absolutePath, true)
        installRustSessionCookies()
    }

    private fun seedXiguaActivatedSession(source: RssSource) {
        source.evalJS(
            """
            source.setVariable(JSON.stringify({
                ci0: 0,
                ci1: 0,
                ci2: 0,
                ci3: 0,
                o: 0,
                p: 0,
                q: 0,
                a: 0,
                url: 'https://cn.xgcartoon.com',
                urls: ['https://cn.xgcartoon.com', 'https://cn.xgcartoon.com']
            }, null, '\t'));
            cache.put('XHaccount', 'xiaohan52131', 1728000000);
            cache.put('XHtime', Date.now(), 1728000000);
            cache.put('XHuser', 1, 1728000000);
            cache.put('client_type', '2', 1728000000);
            cache.put('is_limit', '0', 1728000000);
            cache.put('https://ycoo.net', 'ok', 1728000000);
            cache.put('https://ycoo.net_time', Date.now(), 1728000000);
            cache.put('https://pc.sysbbs.com', 'ok', 1728000000);
            cache.put('https://pc.sysbbs.com_time', Date.now(), 1728000000);
            cache.put('https://m.sysbbs.com', 'ok', 1728000000);
            cache.put('https://m.sysbbs.com_time', Date.now(), 1728000000);
            """.trimIndent()
        )
    }

    private suspend fun smokeSource(assetName: String) {
        val source = readSource(assetName)
        assertTrue("Rust bridge should be available", RustAnalyzerBridge.isAvailable)

        val search = WebBook.searchBookAwait(source, "我的", 1)
        assertTrue("${source.bookSourceName} search returned no books", search.isNotEmpty())

        val book = search.first().toBook()
        WebBook.getBookInfoAwait(source, book)
        assertFalse("${source.bookSourceName} detail did not set bookUrl", book.bookUrl.isBlank())
        assertFalse("${source.bookSourceName} detail did not set tocUrl", book.tocUrl.isBlank())

        val chapters = WebBook.getChapterListAwait(source, book).getOrThrow()
        assertTrue("${source.bookSourceName} toc returned no chapters", chapters.isNotEmpty())

        val chapter = chapters.firstOrNull { !it.isVolume } ?: chapters.first()
        val content = WebBook.getContentAwait(source, book, chapter)
        assertTrue("${source.bookSourceName} content is blank", content.isNotBlank())

        val exploreJson = RustAnalyzerBridge.analyzeRaw(
            source,
            "explore",
            """{"exploreUrl":"","page":1}"""
        )
        assertExploreRan(source, exploreJson)
    }

    private fun assertExploreRan(source: BookSource, json: String) {
        val output = JsonParser.parseString(json).asJsonObject
        val error = output.get("error")?.asString.orEmpty()
        assertTrue("${source.bookSourceName} explore failed: $error", error.isBlank())
        val explore = output.getAsJsonArray("explore")
        assertNotNull("${source.bookSourceName} explore did not return discover entries", explore)
        assertTrue("${source.bookSourceName} explore returned no discover entries", explore!!.size() > 0)
    }

    private fun assertQimaoExploreCategoryUsesRuleBookUrlScript(source: BookSource) {
        val kindsJson = RustAnalyzerBridge.analyzeRaw(
            source,
            "explore",
            """{"exploreUrl":"","page":1}"""
        )
        val kinds = JsonParser.parseString(kindsJson)
            .asJsonObject
            .getAsJsonArray("explore")
        val categoryUrl = kinds
            .first { it.asJsonObject.get("title").asString == "历史" }
            .asJsonObject
            .get("url")
            .asString
        assertTrue(
            "七猫 exploreUrl should keep the placeholder URL that ruleExplore JS parses as baseUrl",
            categoryUrl.contains("baidu.com/category/")
        )

        val booksJson = RustAnalyzerBridge.analyzeRaw(
            source,
            "explore",
            GSON.toJson(mapOf("exploreUrl" to categoryUrl, "page" to 1))
        )
        val books = JsonParser.parseString(booksJson)
            .asJsonObject
            .getAsJsonArray("books")
        assertTrue("七猫发现分类没有返回书籍", books.size() > 0)
        val bookUrl = books[0].asJsonObject.get("book_url").asString
        assertTrue(
            "七猫 ruleExplore.bookUrl JS should rewrite the placeholder into the real detail API: $bookUrl",
            bookUrl.contains("api-bc.wtzw.com/api/v4/book/detail")
        )
        assertFalse(
            "七猫 bookUrl must not leak the baidu placeholder URL: $bookUrl",
            bookUrl.contains("baidu.com/category")
        )
    }

    private fun assertDahuilangExploreCategoryIgnoresMissingJsonFields(source: BookSource) {
        val kindsJson = RustAnalyzerBridge.analyzeRaw(
            source,
            "explore",
            """{"exploreUrl":"","page":1}"""
        )
        val kindsOutput = JsonParser.parseString(kindsJson).asJsonObject
        assertTrue(
            "大灰狼发现分类失败: ${kindsOutput.get("error")?.asString.orEmpty()}",
            kindsOutput.get("error")?.asString.orEmpty().isBlank()
        )
        val categoryUrl = kindsOutput
            .getAsJsonArray("explore")
            .first { it.asJsonObject.get("title").asString == "巅峰榜(男女合频)" }
            .asJsonObject
            .get("url")
            .asString

        val booksJson = RustAnalyzerBridge.analyzeRaw(
            source,
            "explore",
            GSON.toJson(mapOf("exploreUrl" to categoryUrl, "page" to 1))
        )
        val booksOutput = JsonParser.parseString(booksJson).asJsonObject
        assertTrue(
            "大灰狼发现书籍失败: ${booksOutput.get("error")?.asString.orEmpty()}",
            booksOutput.get("error")?.asString.orEmpty().isBlank()
        )
        val books = booksOutput.getAsJsonArray("books")
        assertTrue("大灰狼发现分类没有返回书籍", books.size() > 0)
        val first = books[0].asJsonObject
        assertTrue("大灰狼发现书籍没有书名", first.get("name").asString.isNotBlank())
        assertTrue(
            "大灰狼发现 bookUrl 应该走 data URL 详情入口",
            first.get("book_url").asString.startsWith("data:")
        )
    }

    private fun minimalSource(): BookSource {
        return BookSource(
            bookSourceUrl = "https://example.com/",
            bookSourceName = "Rust Eval Source"
        )
    }

    private fun htmlDataUrl(html: String): String {
        return "data:text/html;base64,${
            Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
        }"
    }

    private fun serveOnce(
        body: String,
        contentType: String,
        observedRequest: CompletableDeferred<String>,
        responseHeaders: List<Pair<String, String>> = emptyList()
    ): String {
        val server = ServerSocket(0)
        thread(name = "rust-remote-fetch-test-server") {
            server.use {
                val socket = it.accept()
                socket.use { client ->
                    val input = client.getInputStream()
                    val buffer = ByteArray(4096)
                    val bytes = ArrayList<Byte>()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        for (index in 0 until read) {
                            bytes.add(buffer[index])
                        }
                        val text = bytes.toByteArray().toString(Charsets.UTF_8)
                        if (text.contains("\r\n\r\n")) {
                            observedRequest.complete(text)
                            break
                        }
                    }
                    val responseBytes = body.toByteArray()
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: $contentType\r\n")
                        responseHeaders.forEach { (name, value) ->
                            append("$name: $value\r\n")
                        }
                        append("Content-Length: ${responseBytes.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }.toByteArray() + responseBytes
                    client.getOutputStream().write(response)
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/remote"
    }

    private fun serveWebDavSequence(
        observedRequests: CompletableDeferred<List<Pair<String, ByteArray>>>
    ): String {
        val server = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${server.localPort}/dav/"
        thread(name = "rust-webdav-test-server") {
            val requests = mutableListOf<Pair<String, ByteArray>>()
            server.use {
                repeat(4) { index ->
                    val socket = it.accept()
                    socket.use { client ->
                        val request = readHttpRequestWithBody(client.getInputStream())
                        requests.add(request)
                        val response = when (index) {
                            0 -> webDavResponse(
                                code = 207,
                                reason = "Multi-Status",
                                contentType = "application/xml",
                                body = """
                                    <?xml version="1.0" encoding="utf-8"?>
                                    <d:multistatus xmlns:d="DAV:">
                                      <d:response>
                                        <d:href>/dav/</d:href>
                                        <d:propstat><d:prop><d:displayname>dav</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
                                      </d:response>
                                      <d:response>
                                        <d:href>/dav/book.txt</d:href>
                                        <d:propstat><d:prop><d:displayname>book.txt</d:displayname><d:getcontentlength>9</d:getcontentlength><d:getlastmodified>Wed, 21 Oct 2015 07:28:00 GMT</d:getlastmodified><d:resourcetype/></d:prop></d:propstat>
                                      </d:response>
                                    </d:multistatus>
                                """.trimIndent().toByteArray()
                            )

                            1 -> webDavResponse(body = "book-body".toByteArray())
                            2 -> webDavResponse(code = 201, reason = "Created", body = ByteArray(0))
                            else -> webDavResponse(code = 204, reason = "No Content", body = ByteArray(0))
                        }
                        client.getOutputStream().write(response)
                    }
                }
            }
            observedRequests.complete(requests)
        }
        return baseUrl
    }

    private fun readHttpRequestWithBody(input: java.io.InputStream): Pair<String, ByteArray> {
        val bytes = ArrayList<Byte>()
        val buffer = ByteArray(4096)
        var headerEnd = -1
        var contentLength = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            for (index in 0 until read) {
                bytes.add(buffer[index])
            }
            val raw = bytes.toByteArray()
            if (headerEnd == -1) {
                headerEnd = raw.toString(Charsets.ISO_8859_1)
                    .indexOf("\r\n\r\n")
                    .takeIf { it >= 0 }
                    ?.plus(4)
                    ?: -1
                if (headerEnd >= 0) {
                    val header = raw.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
                    contentLength = header
                        .lineSequence()
                        .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(":")
                        ?.trim()
                        ?.toIntOrNull()
                        ?: 0
                }
            }
            if (headerEnd >= 0 && raw.size >= headerEnd + contentLength) {
                val head = raw.copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
                val body = raw.copyOfRange(headerEnd, headerEnd + contentLength)
                return head to body
            }
        }
        val raw = bytes.toByteArray()
        val split = raw.toString(Charsets.ISO_8859_1).indexOf("\r\n\r\n").takeIf { it >= 0 } ?: raw.size
        return raw.copyOfRange(0, split).toString(Charsets.ISO_8859_1) to ByteArray(0)
    }

    private fun webDavResponse(
        code: Int = 200,
        reason: String = "OK",
        contentType: String = "application/octet-stream",
        body: ByteArray
    ): ByteArray {
        return buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray() + body
    }

    private fun serveTavilySearchOnce(observedRequest: CompletableDeferred<String>): String {
        val server = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        thread(name = "rust-tavily-test-server") {
            server.use {
                val socket = it.accept()
                socket.use { client ->
                    val input = client.getInputStream()
                    val buffer = ByteArray(4096)
                    val bytes = ArrayList<Byte>()
                    var request = ""
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        for (index in 0 until read) {
                            bytes.add(buffer[index])
                        }
                        request = bytes.toByteArray().toString(Charsets.UTF_8)
                        val headersEnd = request.indexOf("\r\n\r\n")
                        if (headersEnd >= 0) {
                            val contentLength = request
                                .lineSequence()
                                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                                ?.substringAfter(":")
                                ?.trim()
                                ?.toIntOrNull()
                                ?: 0
                            val bodyLength = request.toByteArray().size - (headersEnd + 4)
                            if (bodyLength >= contentLength) {
                                observedRequest.complete(request)
                                break
                            }
                        }
                    }
                    val body = JSONObject().apply {
                        put("query", "rust tavily")
                        put("answer", "Rust answer")
                        put("response_time", 0.1)
                        put("results", JSONArray().apply {
                            put(JSONObject().apply {
                                put("title", "Rust result")
                                put("url", "https://example.com/rust")
                                put("content", "Rust content")
                                put("score", 0.9)
                                put("favicon", "https://example.com/favicon.ico")
                            })
                        })
                    }.toString()
                    val responseBytes = body.toByteArray()
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${responseBytes.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }.toByteArray() + responseBytes
                    client.getOutputStream().write(response)
                }
            }
        }
        return baseUrl
    }

    private fun serveAiModelsOnce(observedRequest: CompletableDeferred<String>): String {
        val server = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        thread(name = "rust-ai-models-test-server") {
            server.use {
                val socket = it.accept()
                socket.use { client ->
                    val input = client.getInputStream()
                    val buffer = ByteArray(4096)
                    val bytes = ArrayList<Byte>()
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        for (index in 0 until read) {
                            bytes.add(buffer[index])
                        }
                        val request = bytes.toByteArray().toString(Charsets.UTF_8)
                        if (request.contains("\r\n\r\n")) {
                            observedRequest.complete(request)
                            break
                        }
                    }
                    val body = JSONObject().apply {
                        put("data", JSONArray().apply {
                            put(JSONObject().apply { put("id", "alpha") })
                            put(JSONObject().apply { put("id", "beta") })
                            put(JSONObject().apply { put("id", "alpha") })
                            put(JSONObject().apply { put("id", "") })
                        })
                    }.toString()
                    val responseBytes = body.toByteArray()
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${responseBytes.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }.toByteArray() + responseBytes
                    client.getOutputStream().write(response)
                }
            }
        }
        return baseUrl
    }

    private fun serveMcpServerOnce(observedRequests: CompletableDeferred<List<String>>): String {
        val server = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${server.localPort}/mcp"
        thread(name = "rust-mcp-test-server") {
            val requests = mutableListOf<String>()
            server.use { socketServer ->
                repeat(4) {
                    val socket = socketServer.accept()
                    socket.use { client ->
                        val request = readHttpRequest(client.getInputStream())
                        requests += request
                        val body = request.substringAfter("\r\n\r\n")
                        val json = JSONObject(body)
                        val method = json.optString("method")
                        val id = json.opt("id")?.toString()
                        val responseBody = when (method) {
                            "initialize" -> JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("id", id)
                                put(
                                    "result",
                                    JSONObject().apply {
                                        put("protocolVersion", "2025-06-18")
                                        put("capabilities", JSONObject())
                                    }
                                )
                            }.toString()

                            "tools/list" -> JSONObject().apply {
                                put("jsonrpc", "2.0")
                                put("id", id)
                                put(
                                    "result",
                                    JSONObject().apply {
                                        put(
                                            "tools",
                                            JSONArray().apply {
                                                put(JSONObject().apply {
                                                    put("name", "echo")
                                                    put("title", "Echo")
                                                    put("description", "Echo test")
                                                    put(
                                                        "inputSchema",
                                                        JSONObject().apply {
                                                            put("type", "object")
                                                            put(
                                                                "properties",
                                                                JSONObject().apply {
                                                                    put(
                                                                        "message",
                                                                        JSONObject().apply { put("type", "string") }
                                                                    )
                                                                }
                                                            )
                                                        }
                                                    )
                                                })
                                            }
                                        )
                                    }
                                )
                            }.toString()

                            "tools/call" -> "data: ${
                                JSONObject().apply {
                                    put("jsonrpc", "2.0")
                                    put("id", id)
                                    put(
                                        "result",
                                        JSONObject().apply { put("content", "echo") }
                                    )
                                }
                            }\n\n"

                            else -> "{}"
                        }
                        val contentType = if (method == "tools/call") {
                            "text/event-stream"
                        } else {
                            "application/json"
                        }
                        val responseBytes = responseBody.toByteArray()
                        val response = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: $contentType\r\n")
                            append("Mcp-Session-Id: rust-session\r\n")
                            append("Content-Length: ${responseBytes.size}\r\n")
                            append("Connection: close\r\n")
                            append("\r\n")
                        }.toByteArray() + responseBytes
                        client.getOutputStream().write(response)
                    }
                }
            }
            observedRequests.complete(requests)
        }
        return baseUrl
    }

    private fun serveAiChatCompletionOnce(observedRequest: CompletableDeferred<String>): String {
        val server = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        thread(name = "rust-ai-chat-test-server") {
            server.use {
                val socket = it.accept()
                socket.use { client ->
                    val request = readHttpRequest(client.getInputStream())
                    observedRequest.complete(request)
                    val chunkOne = JSONObject().apply {
                        put(
                            "choices",
                            JSONArray().apply {
                                put(JSONObject().apply {
                                    put(
                                        "delta",
                                        JSONObject().apply {
                                            put("reasoning_content", "think")
                                            put("content", "Hel")
                                        }
                                    )
                                })
                            }
                        )
                    }
                    val chunkTwo = JSONObject().apply {
                        put(
                            "choices",
                            JSONArray().apply {
                                put(JSONObject().apply {
                                    put(
                                        "delta",
                                        JSONObject().apply {
                                            put("content", "lo")
                                        }
                                    )
                                })
                            }
                        )
                    }
                    val body = "data: $chunkOne\n\ndata: $chunkTwo\n\ndata: [DONE]\n\n"
                    val responseBytes = body.toByteArray()
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: text/event-stream\r\n")
                        append("Content-Length: ${responseBytes.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }.toByteArray() + responseBytes
                    client.getOutputStream().write(response)
                }
            }
        }
        return baseUrl
    }

    private fun readHttpRequest(input: java.io.InputStream): String {
        val buffer = ByteArray(4096)
        val bytes = ArrayList<Byte>()
        var request = ""
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            for (index in 0 until read) {
                bytes.add(buffer[index])
            }
            request = bytes.toByteArray().toString(Charsets.UTF_8)
            val headersEnd = request.indexOf("\r\n\r\n")
            if (headersEnd >= 0) {
                val contentLength = request
                    .lineSequence()
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(":")
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0
                val bodyLength = request.toByteArray().size - (headersEnd + 4)
                if (bodyLength >= contentLength) {
                    return request
                }
            }
        }
        return request
    }

    private fun serveContentPages(): String {
        val server = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${server.localPort}"
        thread(name = "rust-content-pages-test-server") {
            server.use { socketServer ->
                repeat(2) {
                    val socket = socketServer.accept()
                    socket.use { client ->
                        val input = client.getInputStream()
                        val buffer = ByteArray(4096)
                        val bytes = ArrayList<Byte>()
                        var request = ""
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            for (index in 0 until read) {
                                bytes.add(buffer[index])
                            }
                            request = bytes.toByteArray().toString(Charsets.UTF_8)
                            if (request.contains("\r\n\r\n")) {
                                break
                            }
                        }
                        val body = if (request.startsWith("GET /c1p2 ")) {
                            """<html><body><h1>Ignored</h1><div class="content">Two</div><a class="next" href="/c2">next chapter</a></body></html>"""
                        } else {
                            """<html><body><h1>One Title</h1><div class="content">One</div><a class="next" href="/c1p2">next page</a></body></html>"""
                        }
                        val responseBytes = body.toByteArray()
                        val response = buildString {
                            append("HTTP/1.1 200 OK\r\n")
                            append("Content-Type: text/html; charset=UTF-8\r\n")
                            append("Content-Length: ${responseBytes.size}\r\n")
                            append("Connection: close\r\n")
                            append("\r\n")
                        }.toByteArray() + responseBytes
                        client.getOutputStream().write(response)
                    }
                }
            }
        }
        return baseUrl
    }

    private fun serveUploadOnce(
        body: String,
        contentType: String,
        observedRequest: CompletableDeferred<String>
    ): String {
        val server = ServerSocket(0)
        thread(name = "rust-direct-upload-test-server") {
            server.use {
                val socket = it.accept()
                socket.use { client ->
                    val input = client.getInputStream()
                    val buffer = ByteArray(4096)
                    val bytes = ArrayList<Byte>()
                    var bodyStart = -1
                    var contentLength = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        for (index in 0 until read) {
                            bytes.add(buffer[index])
                        }
                        val text = bytes.toByteArray().toString(Charsets.UTF_8)
                        if (bodyStart < 0) {
                            bodyStart = text.indexOf("\r\n\r\n").takeIf { index -> index >= 0 }
                                ?.let { index -> index + 4 } ?: -1
                            if (bodyStart >= 0) {
                                contentLength = text.lineSequence()
                                    .firstOrNull { line -> line.startsWith("Content-Length:", ignoreCase = true) }
                                    ?.substringAfter(":")
                                    ?.trim()
                                    ?.toIntOrNull()
                                    ?: 0
                            }
                        }
                        if (bodyStart >= 0 && bytes.size - bodyStart >= contentLength) {
                            observedRequest.complete(text)
                            break
                        }
                    }
                    val responseBytes = body.toByteArray()
                    val response = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: $contentType\r\n")
                        append("Content-Length: ${responseBytes.size}\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                    }.toByteArray() + responseBytes
                    client.getOutputStream().write(response)
                }
            }
        }
        return "http://127.0.0.1:${server.localPort}/remote"
    }

    private suspend fun searchViaAppModel(source: BookSource, key: String): List<SearchBook> = coroutineScope {
        val latest = arrayListOf<SearchBook>()
        val finished = CompletableDeferred<List<SearchBook>>()
        val model = SearchModel(this, object : SearchModel.CallBack {
            override fun getSearchScope(): SearchScope = SearchScope(source)
            override fun onSearchStart() = Unit
            override fun onSearchSuccess(searchBooks: List<SearchBook>) {
                latest.clear()
                latest.addAll(searchBooks)
            }

            override fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean) {
                finished.complete(latest.toList())
            }

            override fun onSearchCancel(exception: Throwable?) {
                finished.completeExceptionally(exception ?: AssertionError("search canceled"))
            }
        })
        model.search(System.currentTimeMillis(), key)
        try {
            withTimeout(60_000) { finished.await() }
        } finally {
            model.close()
        }
    }

    private suspend fun loadInfoAndTocViaAppModel(searchBook: SearchBook): Book {
        val app = ApplicationProvider.getApplicationContext<App>()
        val viewModel = BookInfoViewModel(app)
        val chaptersDeferred = awaitLiveData(viewModel.chapterListData) { it.isNotEmpty() }
        viewModel.initData(
            Intent()
                .putExtra("name", searchBook.name)
                .putExtra("author", searchBook.author)
                .putExtra("bookUrl", searchBook.bookUrl)
        )
        val chapters = withTimeout(60_000) { chaptersDeferred.await() }
        val book = viewModel.bookData.value
            ?: appDb.searchBookDao.getSearchBook(searchBook.bookUrl)?.toBook()
        assertNotNull("BookInfoViewModel did not resolve book", book)
        appDb.bookDao.insert(book!!)
        appDb.bookChapterDao.delByBook(book.bookUrl)
        appDb.bookChapterDao.insert(*chapters.toTypedArray())
        return book
    }

    private fun clearExistingBook(searchBook: SearchBook) {
        val oldByUrl = appDb.bookDao.getBook(searchBook.bookUrl)
        if (oldByUrl != null) {
            appDb.bookChapterDao.delByBook(oldByUrl.bookUrl)
            appDb.bookDao.delete(oldByUrl)
        }
        val oldByNameAuthor = appDb.bookDao.getBook(searchBook.name, searchBook.author)
        if (oldByNameAuthor != null) {
            appDb.bookChapterDao.delByBook(oldByNameAuthor.bookUrl)
            appDb.bookDao.delete(oldByNameAuthor)
        }
        appDb.bookChapterDao.delByBook(searchBook.bookUrl)
    }

    private fun <T> awaitLiveData(
        liveData: LiveData<T>,
        predicate: (T) -> Boolean
    ): CompletableDeferred<T> {
        val deferred = CompletableDeferred<T>()
        lateinit var observer: Observer<T>
        observer = Observer { value ->
            if (value != null && predicate(value) && deferred.complete(value)) {
                liveData.removeObserver(observer)
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            liveData.observeForever(observer)
        }
        deferred.invokeOnCompletion {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                liveData.removeObserver(observer)
            } else {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    liveData.removeObserver(observer)
                }
            }
        }
        return deferred
    }

    private fun readSource(assetName: String): BookSource {
        val context = InstrumentationRegistry.getInstrumentation().context
        val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val sourceJson = JsonParser.parseString(json).let { element ->
            if (element.isJsonArray) element.asJsonArray.first().toString() else element.toString()
        }
        return GSON.fromJsonObject<BookSource>(sourceJson).getOrThrow()
    }

    private fun readRssSource(assetName: String): RssSource {
        val context = InstrumentationRegistry.getInstrumentation().context
        val json = context.assets.open(assetName).bufferedReader().use { it.readText() }
        val sourceJson = JsonParser.parseString(json).let { element ->
            if (element.isJsonArray) element.asJsonArray.first().toString() else element.toString()
        }
        return GSON.fromJsonObject<RssSource>(sourceJson).getOrThrow()
    }

    private fun copyHttpCacheAssets(target: File) {
        val context = InstrumentationRegistry.getInstrumentation().context
        target.mkdirs()
        context.assets.list("http-cache").orEmpty().forEach { name ->
            context.assets.open("http-cache/$name").use { input ->
                File(target, name).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun installRustSessionCookies() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val session = context.assets.open("session.json").bufferedReader().use { reader ->
            JsonParser.parseReader(reader).asJsonObject
        }
        val cookies = session.getAsJsonObject("cookies") ?: return
        val guangyu = readSource("bookSource_光遇聚合.json")
        cookies.entrySet().forEach { (host, value) ->
            guangyu.evalJS(
                "cookie.setCookie(${GSON.toJson(host)}, ${GSON.toJson(value.asString)}); 'ok'"
            )
            if (host.contains("gyks")) {
                guangyu.evalJS(
                    "setAllCookies(${GSON.toJson(value.asString)}); 'ok'"
                )
            }
        }
    }
}
