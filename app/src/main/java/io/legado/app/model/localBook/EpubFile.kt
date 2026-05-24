package io.legado.app.model.localBook

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.ParcelFileDescriptor
import android.text.TextUtils
import android.util.Size
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.compressPreservingAlpha
import io.legado.app.utils.decodeBase64DataUrlBytes
import io.legado.app.utils.encodeURI
import io.legado.app.utils.isXml
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.preferredCoverExtension
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.model.webBook.RustAnalyzerBridge
import me.ag2s.epublib.domain.EpubBook
import me.ag2s.epublib.domain.Resource
import me.ag2s.epublib.domain.TOCReference
import me.ag2s.epublib.epub.EpubReader
import me.ag2s.epublib.util.zip.AndroidZipFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.system.measureTimeMillis
import splitties.init.appCtx

class EpubFile(var book: Book) {

    private enum class NativeLayoutRequestSource {
        FOREGROUND,
        BACKGROUND_PRELOAD
    }

    private data class NativeViewport(val width: Int, val height: Int, val exact: Boolean)

    private data class EpubBody(
        var html: String,
        var style: String,
        var background: String,
        val documentHtml: String,
        val title: String
    )

    companion object : BaseLocalBookParse {
        const val NATIVE_CONTENT_FLAG = "<epub-native"
        const val NATIVE_LAYOUT_FLAG = "data-href="
        const val NATIVE_CONTENT_VERSION_FLAG = "data-native-ver=\"2\""
        const val READABLE_CONTENT_VERSION_FLAG = "\uE10Aepub-readable-v3\uE10B"
        const val INLINE_STYLE_MARK = '\uE10C'
        private const val NATIVE_LAYOUT_DISK_CACHE_VERSION = 5
        private const val ENABLE_EPUB_DEBUG_DUMP = false
        private val maxNativeDomCache: Int
            get() = if (Runtime.getRuntime().maxMemory() <= 256L * 1024L * 1024L) 160 else 320
        private val maxNativeLayoutCache: Int
            get() = if (Runtime.getRuntime().maxMemory() <= 256L * 1024L * 1024L) 320 else 640
        private var eFile: EpubFile? = null
        private val preloadExecutor = Executors.newSingleThreadExecutor()
        private val preloadedNativeLayoutKeys = linkedSetOf<String>()
        private val globalNativeDomCache = object : LinkedHashMap<String, EpubDomDocument>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpubDomDocument>?): Boolean {
                return size > maxNativeDomCache
            }
        }
        private val globalNativeLayoutCache = object : LinkedHashMap<String, EpubLayoutDocument>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, EpubLayoutDocument>?): Boolean {
                return size > maxNativeLayoutCache
            }
        }

        @Synchronized
        private fun getEFile(book: Book): EpubFile {
            if (eFile == null || eFile?.book?.bookUrl != book.bookUrl) {
                eFile?.close()
                eFile = EpubFile(book)
                //对于Epub文件默认不启用替换
                //io.legado.app.data.entities.Book getUseReplaceRule
                return eFile!!
            }
            eFile?.book = book
            return eFile!!
        }

        @Synchronized
        override fun getChapterList(book: Book): ArrayList<BookChapter> {
            return getEFile(book).getChapterList()
        }

        @Synchronized
        override fun getContent(book: Book, chapter: BookChapter): String? {
            return getEFile(book).getContent(chapter)
        }

        @Synchronized
        internal fun getNativeLayout(book: Book, href: String): EpubLayoutDocument? {
            return getEFile(book).getNativeLayout(href, NativeLayoutRequestSource.FOREGROUND)
        }

        @Synchronized
        internal fun preloadNativeLayouts(book: Book, hrefs: List<String>) {
            if (hrefs.isEmpty()) return
            val file = getEFile(book)
            val viewport = file.resolveNativeViewport()
            val styleKey = file.currentNativeLayoutStyleKey()
            val pendingHrefs = hrefs.distinct().filter { href ->
                val key = file.nativeLayoutCacheKey(href, viewport.width, viewport.height, styleKey)
                synchronized(preloadedNativeLayoutKeys) {
                    preloadedNativeLayoutKeys.add(key)
                }
            }
            if (pendingHrefs.isEmpty()) return
            preloadExecutor.execute {
                pendingHrefs.forEach { href ->
                    runCatching {
                        synchronized(file) {
                            file.getNativeLayout(href, NativeLayoutRequestSource.BACKGROUND_PRELOAD)
                        }
                    }
                }
            }
        }

        @Synchronized
        internal fun warmImportIndex(book: Book) {
            getEFile(book).warmChapterSpanIndex()
        }

        @Synchronized
        internal fun getFootnote(book: Book, href: String): EpubFootnote? {
            return getEFile(book).getFootnote(href)
        }

        @Synchronized
        internal fun preloadFootnotes(book: Book, hrefs: Collection<String>) {
            val noteHrefs = hrefs.asSequence()
                .filter { it.contains("#") }
                .distinct()
                .toList()
            if (noteHrefs.isEmpty()) return
            val file = getEFile(book)
            preloadExecutor.execute {
                synchronized(file) {
                    file.buildFootnoteIndex()
                }
                noteHrefs.forEach { href ->
                    runCatching {
                        synchronized(file) {
                            file.getFootnote(href)
                        }
                    }
                }
            }
        }

        @Synchronized
        override fun getImage(
            book: Book,
            href: String
        ): InputStream? {
            return getEFile(book).getImage(href)
        }

        @Synchronized
        override fun upBookInfo(book: Book) {
            return getEFile(book).upBookInfo()
        }

        fun clear() {
            eFile?.close()
            eFile = null
            synchronized(preloadedNativeLayoutKeys) {
                preloadedNativeLayoutKeys.clear()
            }
        }

        @Synchronized
        fun clearCache(book: Book) {
            if (eFile?.book?.bookUrl == book.bookUrl) {
                eFile?.close()
                eFile = null
            }
            val keyPrefix = "${book.bookUrl}|"
            synchronized(globalNativeDomCache) {
                globalNativeDomCache.keys.removeAll { it.startsWith(keyPrefix) }
            }
            synchronized(globalNativeLayoutCache) {
                globalNativeLayoutCache.keys.removeAll { it.startsWith(keyPrefix) }
            }
            synchronized(preloadedNativeLayoutKeys) {
                preloadedNativeLayoutKeys.removeAll { it.startsWith(keyPrefix) }
            }
        }
    }

    private var mCharset: Charset = Charset.defaultCharset()
    private val cssTextCache = linkedMapOf<String, String>()
    private val cssRuleCache = linkedMapOf<String, List<EpubCss.Rule>>()
    private val nativeDomCache = linkedMapOf<String, EpubDomDocument>()
    private val nativeLayoutCache = linkedMapOf<String, EpubLayoutDocument>()
    private val imageSizeCache = linkedMapOf<String, Size>()
    private val fontTypefaceCache = linkedMapOf<String, Typeface?>()
    private val fontFaceMatchCache = linkedMapOf<String, EpubFontFace?>()
    private val footnoteCache = linkedMapOf<String, EpubFootnote?>()
    private val footnoteSourceCache = linkedMapOf<String, FootnoteSource?>()
    private val footnoteIdHrefIndex = linkedMapOf<String, String>()
    private var footnoteIndexBuilt = false
    private val scheduledNearbyPreloadKeys = linkedSetOf<String>()
    private var nativeLayoutWidth = 0
    private var nativeLayoutHeight = 0
    private var nativeLayoutStyleKey = ""
    private var chapterResourceIndexByHref: Map<String, Int>? = null
    private var coverLoadChecked = false

    /**
     *持有引用，避免被回收
     */
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var epubBook: EpubBook? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = readEpub()
            }
            return field
        }
    private var epubBookContents: List<Resource>? = null
        get() {
            if (field == null || fileDescriptor == null) {
                field = epubBook?.contents
            }
            return field
        }
    private var epubSpineContents: List<Resource>? = null
        get() {
            if (field == null || fileDescriptor == null) {
                val spineResources = epubBook?.spine?.spineReferences
                    ?.mapNotNull { it.resource }
                    ?.filter { it.href.isNotBlank() }
                    .orEmpty()
                field = spineResources.ifEmpty { epubBook?.contents.orEmpty() }
            }
            return field
        }

    /**
     * 重写epub文件解析代码，直接读出压缩包文件生成Resources给epublib，这样的好处是可以逐一修改某些文件的格式错误
     */
    private fun readEpub(): EpubBook? {
        invalidateBookCache(closeDescriptor = true)
        return kotlin.runCatching {
            //ContentScheme拷贝到私有文件夹采用懒加载防止OOM
            //val zipFile = BookHelp.getEpubFile(book)
            var result: EpubBook? = null
            val cost = measureTimeMillis {
                result = BookHelp.getBookPFD(book)?.let {
                    fileDescriptor = it
                    val zipFile = AndroidZipFile(it, book.originName)
                    EpubReader().readEpubLazy(zipFile, "utf-8")
                }
            }
            AppLog.putDebug("EPUB readEpubLazy done: book=${book.name}, cost=${cost}ms")
            result
        }.onFailure {
            invalidateBookCache(closeDescriptor = true)
            AppLog.put("读取Epub文件失败\n${it.localizedMessage}", it)
            it.printOnDebug()
        }.getOrThrow()
    }

    private fun invalidateBookCache(closeDescriptor: Boolean) {
        if (closeDescriptor) {
            runCatching { fileDescriptor?.close() }
            fileDescriptor = null
        }
        epubBook = null
        epubBookContents = null
        epubSpineContents = null
        chapterResourceIndexByHref = null
        footnoteIndexBuilt = false
        footnoteIdHrefIndex.clear()
        footnoteCache.clear()
        footnoteSourceCache.clear()
        cssTextCache.clear()
        cssRuleCache.clear()
        nativeDomCache.clear()
        nativeLayoutCache.clear()
        imageSizeCache.clear()
        fontTypefaceCache.clear()
        scheduledNearbyPreloadKeys.clear()
        nativeLayoutWidth = 0
        nativeLayoutHeight = 0
        nativeLayoutStyleKey = ""
        coverLoadChecked = false
    }

    private fun getContent(chapter: BookChapter): String? {
        if (chapter.isVolume && chapter.url.startsWith("skip:")) return ""
        var result: String? = null
        val cost = measureTimeMillis {
            result = getContentInternal(chapter)
        }
        AppLog.putDebug(
            "EPUB getContent done: chapter=${chapter.index}:${chapter.title}, " +
                "cost=${cost}ms, native=${result?.startsWith(NATIVE_CONTENT_FLAG) == true}"
        )
        return result
    }

    private fun getContentInternal(chapter: BookChapter): String? {
        /*获取当前章节文本*/
        val contents = epubSpineContents ?: epubBookContents ?: return null
        val nextChapterFirstResourceHref = chapter.getVariable("nextUrl").substringBeforeLast("#")
        val currentChapterFirstResourceHref = chapter.url.substringBeforeLast("#")
        findEpubResource(currentChapterFirstResourceHref)?.takeIf { it.isEpubBookInfoResource() }?.let {
            return ""
        }
        val isLastChapter = nextChapterFirstResourceHref.isBlank()
        val startFragmentId = chapter.startFragmentId
        val endFragmentId = chapter.endFragmentId
        val rawResources = linkedMapOf<String, String>()
        val nativeHrefs = arrayListOf<String>()
        val debugBodies = arrayListOf<String>()
        fun collectRawResource(res: Resource) {
            nativeHrefs.add(res.href)
            if (ENABLE_EPUB_DEBUG_DUMP) {
                rawResources[res.href] = String(res.data, mCharset)
            }
        }
        val includeNextChapterResource = !endFragmentId.isNullOrBlank()
        val chapterResources = collectChapterResources(
            contents = contents,
            currentHref = currentChapterFirstResourceHref,
            nextHref = nextChapterFirstResourceHref,
            includeNextResource = includeNextChapterResource,
            isLastChapter = isLastChapter
        )
        if (AppConfig.epubParseMode != AppConfig.EPUB_PARSE_MODE_CLASSIC) {
            return getReadableChapterContent(
                chapter = chapter,
                chapterResources = chapterResources,
                startFragmentId = startFragmentId,
                endFragmentId = endFragmentId,
                includeNextChapterResource = includeNextChapterResource,
                nextChapterFirstResourceHref = nextChapterFirstResourceHref,
                isLastChapter = isLastChapter
            )
        }
        chapterResources.forEachIndexed { index, res ->
            collectRawResource(res)
            // Native layout cache is keyed by href, so keep the cached DOM as the full resource.
            // Fragment slicing would make chapters sharing one XHTML overwrite each other.
            val body = getBody(res, null, null)
            if (ENABLE_EPUB_DEBUG_DUMP) {
                debugBodies.add(
                    when {
                        index == 0 -> getBody(res, startFragmentId, endFragmentId).outerHtml()
                        index == chapterResources.lastIndex && includeNextChapterResource && !isLastChapter &&
                            res.href == nextChapterFirstResourceHref -> getBody(res, null, endFragmentId).outerHtml()
                        else -> body.outerHtml()
                    }
                )
            }
        }
        val html = if (ENABLE_EPUB_DEBUG_DUMP) {
            RustAnalyzerBridge.epubDebugChapterHtml(
                debugBodies,
                book.getDelTag(Book.rubyTag),
                "EpubFile.debugChapterHtml"
            )
        } else {
            ""
        }
        if (ENABLE_EPUB_DEBUG_DUMP) {
            dumpEpubChapterDebug(chapter, rawResources, html)
        }
        if (nativeHrefs.isEmpty()) {
            AppLog.put("EPUB Native Content empty: chapter=${chapter.index}:${chapter.title}, href=$currentChapterFirstResourceHref")
        }
        val nativeHref = currentChapterFirstResourceHref.escapeXmlAttr()
        val nativeHrefList = nativeHrefs.distinct().joinToString("|") { it.escapeXmlAttr() }
        val title = chapter.title.escapeXmlAttr()
        return """<epub-native data-native-ver="2" data-href="$nativeHref" data-hrefs="$nativeHrefList" data-title="$title" />"""
    }

    private fun getReadableChapterContent(
        chapter: BookChapter,
        chapterResources: List<Resource>,
        startFragmentId: String?,
        endFragmentId: String?,
        includeNextChapterResource: Boolean,
        nextChapterFirstResourceHref: String,
        isLastChapter: Boolean
    ): String {
        val bodies = arrayListOf<String>()
        val rawResources = linkedMapOf<String, String>()
        chapterResources.forEachIndexed { index, res ->
            if (ENABLE_EPUB_DEBUG_DUMP) {
                rawResources[res.href] = String(res.data, mCharset)
            }
            bodies.add(
                when {
                    index == 0 && index == chapterResources.lastIndex ->
                        getBody(res, startFragmentId, endFragmentId, buildNativeDom = false).outerHtml()
                    index == 0 ->
                        getBody(res, startFragmentId, null, buildNativeDom = false).outerHtml()
                    index == chapterResources.lastIndex && includeNextChapterResource && !isLastChapter &&
                        res.href == nextChapterFirstResourceHref -> getBody(res, null, endFragmentId, buildNativeDom = false)
                        .outerHtml()
                    else -> getBody(res, null, null, buildNativeDom = false).outerHtml()
                }
            )
        }
        val deleteRuby = book.getDelTag(Book.rubyTag)
        val lines = bodies.asSequence()
            .flatMap { body ->
                RustAnalyzerBridge.epubReadableLines(
                    body,
                    deleteRuby,
                    "EpubFile.readableLines"
                ).asSequence()
            }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
        if (chapter.isVolume && lines.size == 1 && lines.first().isDuplicateReadableTitle(chapter.title)) {
            lines.clear()
        }
        val text = lines.joinToString("\n")
        if (ENABLE_EPUB_DEBUG_DUMP) {
            dumpEpubChapterDebug(chapter, rawResources, text)
        }
        return READABLE_CONTENT_VERSION_FLAG + text
    }

    private fun String.isDuplicateReadableTitle(title: String): Boolean {
        fun String.normalizedTitleText(): String {
            return replace(INLINE_STYLE_MARK.toString(), "")
                .replace(READABLE_CONTENT_VERSION_FLAG, "")
                .replace(Regex("\\s+"), "")
                .replace(Regex("[　\\p{Punct}，。！？、；：“”‘’（）《》〈〉【】［］〔〕—…·]"), "")
                .lowercase(Locale.ROOT)
        }
        val contentTitle = normalizedTitleText()
        val chapterTitle = title.normalizedTitleText()
        if (contentTitle.isBlank() || chapterTitle.isBlank()) return false
        return contentTitle == chapterTitle ||
            contentTitle.contains(chapterTitle) ||
            chapterTitle.contains(contentTitle)
    }

    private fun collectChapterResources(
        contents: List<Resource>,
        currentHref: String,
        nextHref: String,
        includeNextResource: Boolean,
        isLastChapter: Boolean
    ): List<Resource> {
        val indexMap = chapterResourceIndexByHref ?: buildChapterResourceIndex(contents).also {
            chapterResourceIndexByHref = it
        }
        val startIndex = indexMap[currentHref] ?: contents.indexOfFirst { it.href == currentHref }
        if (startIndex < 0) return emptyList()
        if (isLastChapter || nextHref.isBlank()) {
            return contents.subList(startIndex, contents.size)
        }
        val nextIndex = indexMap[nextHref] ?: contents.indexOfFirst { it.href == nextHref }
        if (nextIndex < 0 || nextIndex < startIndex) {
            return contents.subList(startIndex, contents.size)
        }
        val endExclusive = if (includeNextResource) (nextIndex + 1).coerceAtMost(contents.size) else nextIndex
        if (endExclusive <= startIndex) {
            return listOf(contents[startIndex])
        }
        return contents.subList(startIndex, endExclusive)
    }

    private fun buildChapterResourceIndex(contents: List<Resource>): Map<String, Int> {
        val map = linkedMapOf<String, Int>()
        contents.forEachIndexed { index, resource ->
            if (resource.href.isNotBlank() && !map.containsKey(resource.href)) {
                map[resource.href] = index
            }
        }
        return map
    }

    private fun getBody(
        res: Resource,
        startFragmentId: String?,
        endFragmentId: String?,
        buildNativeDom: Boolean = true
    ): EpubBody {
        /**
         * <image width="1038" height="670" xlink:href="..."/>
         * ...titlepage.xhtml
         * 大多数epub文件的封面页都会带有cover，可以一定程度上解决封面读取问题
        */
        val rawHtml = String(res.data, mCharset)
        val preparedBody = RustAnalyzerBridge.epubBodyHtml(
            html = rawHtml,
            startFragmentId = startFragmentId,
            endFragmentId = endFragmentId,
            rulePath = "EpubFile.bodyHtml"
        )
        val body = EpubBody(
            html = preparedBody.bodyHtml.ifBlank { preparedBody.html },
            style = preparedBody.bodyStyle,
            background = preparedBody.bodyBackground,
            documentHtml = preparedBody.documentHtml.ifBlank { preparedBody.html },
            title = preparedBody.title
        )
        body.applyEpubCss(res)
        body.html =
            RustAnalyzerBridge.epubInheritedStyles(
                body.html,
                body.style,
                "EpubFile.inheritedStyles"
            )
        body.html =
            RustAnalyzerBridge.epubMediaPlaceholders(
                body.html,
                res.href,
                "EpubFile.mediaPlaceholders"
            )
        body.html =
            RustAnalyzerBridge.epubInlineStyles(
                body.html,
                body.style,
                "EpubFile.inlineStyles"
            )
        val backgroundHref = RustAnalyzerBridge.epubBodyBackgroundImage(
            body.style,
            body.background,
            "EpubFile.bodyBackgroundImage"
        )
        if (backgroundHref.isNotBlank()) {
            val imageHref = resolveEpubResourceHref(res.href, backgroundHref)
            if (canRenderEpubImage(imageHref)) {
                body.html = buildString {
                    append("<img src=\"").append(imageHref.escapeHtmlAttribute()).append('"')
                    append(" data-legado-width=\"100%\"")
                    append(" data-legado-style=\"").append(Book.imgStyleSingle.escapeHtmlAttribute()).append('"')
                    append(" data-epub-background=\"true\">")
                    append(body.html)
                }
            } else {
                AppLog.putDebug("EPUB skip invalid background image: href=$imageHref, source=${res.href}")
            }
        }
        val imagePageMarks = RustAnalyzerBridge.epubImagePageMarks(
            body.html,
            "EpubFile.imagePageMarks"
        )
        if (imagePageMarks.bodyStyleAppend.isNotBlank()) {
            body.style = "${body.style}${imagePageMarks.bodyStyleAppend}"
        }
        body.html = imagePageMarks.html
        body.html =
            RustAnalyzerBridge.epubMaterializedImages(
                body.html,
                res.href,
                epubBook?.resources?.all.orEmpty().mapNotNull { it.href },
                "EpubFile.materializedImages"
            )
        body.html =
            RustAnalyzerBridge.epubResolvedLinks(
                body.html,
                res.href,
                "EpubFile.resolvedLinks"
            )
        if (buildNativeDom) {
            buildNativeDom(body, res)
        }
        return body
    }

    private fun buildNativeDom(body: EpubBody, res: Resource) {
        runCatching {
            val document = EpubDomBuilder(
                loadCss = ::loadCss,
                resolveHref = ::resolveEpubResourceHref
            ).build(
                documentHtml = body.documentHtml,
                bodyHtml = body.html,
                bodyOuterHtml = body.outerHtml(),
                title = body.title,
                baseHref = res.href
            )
            nativeDomCache[res.href] = document
            synchronized(globalNativeDomCache) {
                globalNativeDomCache[nativeDomCacheKey(res.href)] = document
            }
            AppLog.put(
                "EPUB Native DOM ready: href=${res.href}, " +
                    "children=${document.body.children.size}, title=${document.title.orEmpty()}"
            )
        }.onFailure {
            AppLog.putDebug("构建 EPUB 原生 DOM 失败: ${res.href}\n${it.localizedMessage}", it)
        }
    }

    private fun getFootnote(href: String): EpubFootnote? {
        val cleanHref = href.substringBeforeLast("#")
        val targetId = href.substringAfterLast("#", "")
            .decodeEpubFragment()
            .takeIf { it.isNotBlank() }
            ?: return null
        val cacheKey = "${cleanHref.ifBlank { "*" }}#$targetId"
        if (footnoteCache.containsKey(cacheKey)) {
            return footnoteCache[cacheKey]
        }
        val noteSource = findFootnoteSource(cleanHref, targetId) ?: run {
            footnoteCache[cacheKey] = null
            return null
        }
        val target = RustAnalyzerBridge.epubFootnoteTarget(
            noteSource.html,
            targetId,
            "EpubFile.footnoteTarget"
        )
        if (!target.found) {
            footnoteCache[cacheKey] = null
            return null
        }
        var html = target.html
        target.imageSources.forEachIndexed { index, src ->
            html = html.replace(
                "__LEGADO_EPUB_FOOTNOTE_IMG_${index}__",
                resolveEpubResourceHref(noteSource.href, src).escapeHtmlAttribute()
            )
        }
        val text = target.text.cleanEpubInfoText()
        val footnote = EpubFootnote(
            title = target.title.ifBlank { "注解" },
            html = html.takeIf { it.isNotBlank() } ?: text
        ).takeIf { text.isNotBlank() || it.html.isNotBlank() }
        footnoteCache[cacheKey] = footnote
        return footnote
    }

    private fun findFootnoteSource(cleanHref: String, targetId: String): FootnoteSource? {
        val cacheKey = "${cleanHref.ifBlank { "*" }}#$targetId"
        if (footnoteSourceCache.containsKey(cacheKey)) {
            return footnoteSourceCache[cacheKey]
        }
        val primary = findEpubResource(cleanHref)?.let { resource ->
            val html = runCatching { String(resource.data, mCharset) }.getOrNull()
            if (html != null && RustAnalyzerBridge.epubFootnoteTarget(
                    html,
                    targetId,
                    "EpubFile.footnoteSource.primary"
                ).found
            ) {
                FootnoteSource(resource.href ?: cleanHref, html)
            } else {
                null
            }
        }
        if (primary != null) {
            footnoteSourceCache[cacheKey] = primary
            return primary
        }
        buildFootnoteIndex()
        footnoteIdHrefIndex[targetId]?.let { indexedHref ->
            findEpubResource(indexedHref)?.let { resource ->
                val html = runCatching { String(resource.data, mCharset) }.getOrNull()
                if (html != null && RustAnalyzerBridge.epubFootnoteTarget(
                        html,
                        targetId,
                        "EpubFile.footnoteSource.index"
                    ).found
                ) {
                    return FootnoteSource(indexedHref, html).also {
                        footnoteSourceCache[cacheKey] = it
                    }
                }
            }
        }
        epubBook?.resources?.all.orEmpty().forEach { resource ->
            val href = resource.href ?: return@forEach
            val source = runCatching { String(resource.data, mCharset) }.getOrNull() ?: return@forEach
            if (!source.contains(targetId)) return@forEach
            if (RustAnalyzerBridge.epubFootnoteTarget(
                    source,
                    targetId,
                    "EpubFile.footnoteSource.scan"
                ).found
            ) {
                return FootnoteSource(href, source).also {
                    footnoteSourceCache[cacheKey] = it
                }
            }
        }
        footnoteSourceCache[cacheKey] = null
        return null
    }

    private fun buildFootnoteIndex() {
        if (footnoteIndexBuilt) return
        footnoteIndexBuilt = true
        epubBook?.resources?.all.orEmpty().forEach { resource ->
            val href = resource.href ?: return@forEach
            if (!href.isReadableEpubHtml()) return@forEach
            val source = runCatching { String(resource.data, mCharset) }.getOrNull() ?: return@forEach
            if (!source.mayContainFootnote()) return@forEach
            RustAnalyzerBridge.epubFootnoteIds(source, "EpubFile.footnoteIndex").forEach { id ->
                footnoteIdHrefIndex.putIfAbsent(id, href)
            }
        }
        AppLog.put("EPUB Footnote index built: count=${footnoteIdHrefIndex.size}")
    }

    private fun String.isReadableEpubHtml(): Boolean {
        val clean = lowercase(Locale.ROOT)
        return clean.endsWith(".xhtml") || clean.endsWith(".html") || clean.endsWith(".htm")
    }

    private fun String.mayContainFootnote(): Boolean {
        return contains("footnote", ignoreCase = true) ||
            contains("endnote", ignoreCase = true) ||
            contains("noteref", ignoreCase = true) ||
            contains("duokan-footnote", ignoreCase = true) ||
            contains("doc-footnote", ignoreCase = true) ||
            contains("doc-endnote", ignoreCase = true) ||
            contains("epub:type", ignoreCase = true) ||
            contains("role=", ignoreCase = true) ||
            contains("id=", ignoreCase = true)
    }

    private fun String.decodeEpubFragment(): String {
        return runCatching { URLDecoder.decode(this, "UTF-8") }.getOrDefault(this)
    }

    private fun String.escapeHtmlAttribute(): String {
        return replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun EpubBody.outerHtml(): String {
        val attrs = buildString {
            if (style.isNotBlank()) {
                append(" style=\"").append(style.escapeHtmlAttribute()).append('"')
            }
            if (background.isNotBlank()) {
                append(" background=\"").append(background.escapeHtmlAttribute()).append('"')
            }
        }
        return "<body$attrs>$html</body>"
    }

    private fun getNativeLayout(
        href: String,
        source: NativeLayoutRequestSource
    ): EpubLayoutDocument? {
        val viewport = resolveNativeViewport()
        val width = viewport.width
        val height = viewport.height
        AppLog.putDebug(
            "EPUB Native Layout enter: href=$href, view=${width}x$height, " +
                "domCache=${nativeDomCache.containsKey(href)}, layoutCache=${nativeLayoutCache.containsKey(href)}, " +
                "source=$source, exactViewport=${viewport.exact}"
        )
        if (width <= 0 || height <= 0) {
            AppLog.put("EPUB Native Layout abort: 阅读区尺寸无效, href=$href, view=${width}x$height")
            return null
        }
        val styleKey = currentNativeLayoutStyleKey()
        if (nativeLayoutWidth != width || nativeLayoutHeight != height || nativeLayoutStyleKey != styleKey) {
            AppLog.putDebug(
                "EPUB Native Layout cache clear: old=${nativeLayoutWidth}x$nativeLayoutHeight, " +
                    "new=${width}x$height, styleChanged=${nativeLayoutStyleKey != styleKey}"
            )
            nativeLayoutCache.clear()
            nativeLayoutWidth = width
            nativeLayoutHeight = height
            nativeLayoutStyleKey = styleKey
        }
        nativeLayoutCache[href]?.let {
            AppLog.putDebug("EPUB Native Layout cache hit: href=$href, pages=${it.pages.size}")
            return it
        }
        val layoutCacheKey = nativeLayoutCacheKey(href, width, height, styleKey)
        synchronized(globalNativeLayoutCache) {
            globalNativeLayoutCache[layoutCacheKey]
        }?.let {
            nativeLayoutCache[href] = it
            AppLog.putDebug("EPUB Native Layout global cache hit: href=$href, pages=${it.pages.size}")
            return it
        }
        readNativeLayoutFromDisk(layoutCacheKey)?.let {
            nativeLayoutCache[href] = it
            synchronized(globalNativeLayoutCache) {
                globalNativeLayoutCache[layoutCacheKey] = it
            }
            AppLog.putDebug("EPUB Native Layout disk cache hit: href=$href, pages=${it.pages.size}")
            return it
        }
        AppLog.putDebug(
            "EPUB Native Layout cache miss: href=$href, reason=not_in_memory_or_disk, " +
                "view=${width}x$height, styleKey=$styleKey"
        )
        val document = nativeDomCache[href] ?: rebuildNativeDom(href) ?: return null
        var layoutCost = 0L
        return runCatching {
            var layout: EpubLayoutDocument? = null
            layoutCost = measureTimeMillis {
                layout = EpubLayoutEngine(
                    imageSizeResolver = ::getEpubImageSize,
                    fontResolver = ::getEpubTypeface,
                    viewportWidth = width,
                    viewportHeight = height
                ).layout(document)
            }
            layout
        }.onSuccess {
            if (it == null) return@onSuccess
            nativeLayoutCache[href] = it
            synchronized(globalNativeLayoutCache) {
                globalNativeLayoutCache[layoutCacheKey] = it
            }
            if (viewport.exact) {
                writeNativeLayoutToDisk(layoutCacheKey, it)
            } else {
                AppLog.putDebug("EPUB Native Layout skip disk cache write: href=$href, reason=fallback_viewport")
            }
            val linkAreas = it.pages.sumOf { page ->
                page.commands.count { command -> command is EpubLinkArea }
            }
            val linkedImages = it.pages.sumOf { page ->
                page.commands.count { command -> command is EpubImageBox && !command.linkHref.isNullOrBlank() }
            }
            val linkedText = it.pages.sumOf { page ->
                page.commands.count { command -> command is EpubTextRun && !command.linkHref.isNullOrBlank() }
            }
            AppLog.putDebug(
                "EPUB Native Layout built: href=$href, pages=${it.pages.size}, " +
                    "commands=${it.pages.sumOf { page -> page.commands.size }}, " +
                    "linkAreas=$linkAreas, linkedImages=$linkedImages, linkedText=$linkedText, " +
                    "cost=${layoutCost}ms"
            )
            if (viewport.exact) {
                scheduleNearbyNativeLayoutPreload(
                    width = width,
                    height = height,
                    styleKey = styleKey,
                    currentHref = href,
                    includePrevious = source == NativeLayoutRequestSource.FOREGROUND
                )
            }
        }.onFailure {
            AppLog.putDebug("构建 EPUB 原生布局失败: $href\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun scheduleNearbyNativeLayoutPreload(
        width: Int,
        height: Int,
        styleKey: String,
        currentHref: String,
        includePrevious: Boolean
    ) {
        val preloadKey = "${book.bookUrl}|$currentHref|${width}x$height|$styleKey"
        synchronized(scheduledNearbyPreloadKeys) {
            if (!scheduledNearbyPreloadKeys.add(preloadKey)) return
        }
        val readableHrefs = epubSpineContents
            ?.asSequence()
            ?.filter { it.isReadableEpubResource() }
            ?.filterNot { it.isEpubBookInfoResource() }
            ?.map { it.href }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            .orEmpty()
        val currentIndex = readableHrefs.indexOf(currentHref).takeIf { it >= 0 } ?: return
        val startIndex = currentIndex - if (includePrevious) 1 else 0
        val endIndex = currentIndex + 2
        val hrefs = readableHrefs
            .asSequence()
            .withIndex()
            .filter { (index, href) ->
                href != currentHref && index in startIndex..endIndex
            }
            .map { it.value }
            .toList()
        if (hrefs.isEmpty()) return
        AppLog.putDebug("EPUB Native Layout preload nearby: count=${hrefs.size}, current=$currentHref, view=${width}x$height")
        preloadNativeLayouts(book, hrefs)
    }

    private fun warmChapterSpanIndex() {
        val contents = epubSpineContents ?: epubBookContents ?: return
        if (chapterResourceIndexByHref == null) {
            chapterResourceIndexByHref = buildChapterResourceIndex(contents)
        }
    }

    private fun nativeDomCacheKey(href: String): String {
        return "${book.bookUrl}|$href"
    }

    private fun nativeLayoutCacheKey(href: String, width: Int, height: Int, styleKey: String): String {
        return "${book.bookUrl}|$href|${width}x$height|$styleKey|v$NATIVE_LAYOUT_DISK_CACHE_VERSION"
    }

    private fun currentNativeLayoutStyleKey(): String {
        val paint = ChapterProvider.contentPaint
        return buildString {
            append(paint.textSize)
            append('|').append(paint.color)
            append('|').append(paint.letterSpacing)
            append('|').append(paint.typeface?.style ?: 0)
            append('|').append(ChapterProvider.contentPaintTextHeight)
            append('|').append(ChapterProvider.lineSpacingExtra)
            append('|').append(ChapterProvider.paragraphSpacing)
        }
    }

    private fun rebuildNativeDom(href: String): EpubDomDocument? {
        AppLog.putDebug("EPUB Native DOM rebuild start: href=$href")
        synchronized(globalNativeDomCache) {
            globalNativeDomCache[nativeDomCacheKey(href)]
        }?.let {
            nativeDomCache[href] = it
            AppLog.putDebug("EPUB Native DOM global cache hit: href=$href, children=${it.body.children.size}")
            return it
        }
        val resource = findEpubResource(href) ?: run {
            AppLog.putDebug("EPUB Native DOM rebuild failed: 找不到资源 href=$href")
            return null
        }
        return runCatching {
            getBody(resource, null, null)
            nativeDomCache[href].also { document ->
                AppLog.putDebug(
                    "EPUB Native DOM rebuild result: href=$href, " +
                        "success=${document != null}, children=${document?.body?.children?.size ?: 0}"
                )
            }
        }.onFailure {
            AppLog.putDebug("重建 EPUB 原生 DOM 失败: $href\n${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun resolveNativeViewport(): NativeViewport {
        val visibleWidth = ChapterProvider.visibleWidth
        val visibleHeight = ChapterProvider.visibleHeight
        val exact = visibleWidth > 0 && visibleHeight > 0
        val width = if (exact) visibleWidth else appCtx.resources.displayMetrics.widthPixels
        val height = if (exact) visibleHeight else appCtx.resources.displayMetrics.heightPixels
        return NativeViewport(width, height, exact)
    }

    private fun nativeLayoutDiskFile(layoutCacheKey: String): File {
        val dir = File(BookHelp.cachePath, "${book.getFolderName()}/epub_layout")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val fileName = "${MD5Utils.md5Encode16(layoutCacheKey)}.bin"
        return File(dir, fileName)
    }

    private fun readNativeLayoutFromDisk(layoutCacheKey: String): EpubLayoutDocument? {
        val file = nativeLayoutDiskFile(layoutCacheKey)
        if (!file.exists()) return null
        return runCatching {
            ObjectInputStream(file.inputStream().buffered()).use { stream ->
                stream.readObject() as? EpubLayoutDocument
            }
        }.onFailure {
            file.delete()
            AppLog.putDebug("EPUB Native Layout disk cache read failed: ${it.localizedMessage}", it)
        }.getOrNull()
    }

    private fun writeNativeLayoutToDisk(layoutCacheKey: String, layout: EpubLayoutDocument) {
        val file = nativeLayoutDiskFile(layoutCacheKey)
        runCatching {
            ObjectOutputStream(file.outputStream().buffered()).use { stream ->
                stream.writeObject(layout)
            }
        }.onFailure {
            AppLog.putDebug("EPUB Native Layout disk cache write failed: ${it.localizedMessage}", it)
        }
    }

    private fun getEpubImageSize(href: String): Size? {
        val cleanHref = href.stripUrlOptions()
        imageSizeCache[cleanHref]?.let { return it }
        val data = when {
            cleanHref.startsWith("data:", true) -> cleanHref.decodeBase64DataUrlBytes()
            cleanHref.startsWith("http://", true) || cleanHref.startsWith("https://", true) -> null
            cleanHref == "cover.jpeg" -> epubBook?.coverImage?.data
            else -> findEpubResource(cleanHref)?.data
        } ?: return null
        val cacheKey = epubImageSizeCacheKey(cleanHref, data.size)
        readEpubImageSizeFromDisk(cacheKey)?.let {
            imageSizeCache[cleanHref] = it
            return it
        }
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        val size = if (options.outWidth > 0 && options.outHeight > 0) {
            Size(options.outWidth, options.outHeight)
        } else {
            SvgUtils.getSize(ByteArrayInputStream(data))
        } ?: run {
            AppLog.put("EPUB Native Image size unknown: href=$href")
            return null
        }
        imageSizeCache[cleanHref] = size
        writeEpubImageSizeToDisk(cacheKey, size)
        return size
    }

    private fun epubImageSizeCacheKey(href: String, byteSize: Int): String {
        return MD5Utils.md5Encode16("${book.bookUrl}|${book.originName}|$href|$byteSize")
    }

    private fun epubImageSizeCacheFile(cacheKey: String): File {
        return File(BookHelp.cachePath, "${book.getFolderName()}/epub_image_size/$cacheKey.txt")
    }

    private fun readEpubImageSizeFromDisk(cacheKey: String): Size? {
        val file = epubImageSizeCacheFile(cacheKey)
        if (!file.exists()) return null
        return runCatching {
            val parts = file.readText().split('x')
            val width = parts.getOrNull(0)?.toIntOrNull() ?: return@runCatching null
            val height = parts.getOrNull(1)?.toIntOrNull() ?: return@runCatching null
            if (width > 0 && height > 0) Size(width, height) else null
        }.getOrNull()
    }

    private fun writeEpubImageSizeToDisk(cacheKey: String, size: Size) {
        runCatching {
            val file = epubImageSizeCacheFile(cacheKey)
            file.parentFile?.mkdirs()
            file.writeText("${size.width}x${size.height}")
        }.onFailure {
            AppLog.putDebug("EPUB image size cache write failed: ${it.localizedMessage}", it)
        }
    }

    private fun canRenderEpubImage(href: String): Boolean {
        return getEpubImageSize(href) != null
    }

    private fun getEpubTypeface(
        family: String,
        bold: Boolean,
        italic: Boolean,
        fontFaces: List<EpubFontFace>
    ): Typeface? {
        if (fontFaces.isEmpty()) return null
        val families = family.split(',')
            .map { it.trim().trim('\'', '"') }
            .filter { it.isNotBlank() }
        if (families.isEmpty()) return null
        val matchKey = "${fontFaces.hashCode()}|${families.joinToString("|").lowercase(Locale.ROOT)}|$bold|$italic"
        val face = fontFaceMatchCache.getOrPut(matchKey) {
            families.firstNotNullOfOrNull { normalizedFamily ->
                fontFaces
                    .filter { it.family.equals(normalizedFamily, ignoreCase = true) }
                    .minByOrNull { it.fontMatchScore(bold, italic) }
            }
        } ?: return null
        val cleanHref = face.src.stripUrlOptions()
        val cacheKey = "$cleanHref|$bold|$italic"
        return fontTypefaceCache.getOrPut(cacheKey) {
            runCatching {
                val dir = File(appCtx.cacheDir, "epub-fonts").apply { mkdirs() }
                val suffix = cleanHref.substringAfterLast('.', "ttf")
                    .takeIf { it.length in 2..5 }
                    ?: "ttf"
                val file = File(dir, "${book.bookUrl.hashCode()}_${cleanHref.hashCode()}.$suffix")
                if (!file.exists() || file.length() <= 0L) {
                    val data = findEpubResource(cleanHref)?.data ?: return@getOrPut null
                    FileOutputStream(file).use { output -> output.write(data) }
                }
                val typeface = Typeface.createFromFile(file)
                val style = when {
                    bold && italic -> Typeface.BOLD_ITALIC
                    bold -> Typeface.BOLD
                    italic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                Typeface.create(typeface, style)
            }.onFailure {
                AppLog.putDebug("加载 EPUB 内嵌字体失败: family=$family, href=$cleanHref\n${it.localizedMessage}", it)
            }.getOrNull()
        }
    }

    private fun EpubFontFace.fontMatchScore(bold: Boolean, italic: Boolean): Int {
        val weightValue = weight?.toIntOrNull()
            ?: when (weight?.trim()?.lowercase(Locale.ROOT)) {
                "bold", "bolder" -> 700
                "light", "lighter" -> 300
                else -> 400
            }
        val targetWeight = if (bold) 700 else 400
        val styleScore = if (italic == style.equals("italic", ignoreCase = true) ||
            italic == style.equals("oblique", ignoreCase = true)
        ) {
            0
        } else {
            1000
        }
        return kotlin.math.abs(weightValue - targetWeight) + styleScore
    }

    private fun dumpEpubChapterDebug(
        chapter: BookChapter,
        rawResources: Map<String, String>,
        renderedHtml: String
    ) {
        runCatching {
            val root = File(appCtx.cacheDir, "epub-debug")
            val bookDirName = book.name
                .ifBlank { book.originName }
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(80)
            val chapterDirName = "${chapter.index}_${chapter.title}"
                .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                .take(96)
            val dir = File(root, "$bookDirName/$chapterDirName")
            FileUtils.createFolderIfNotExist(dir.absolutePath)
            rawResources.forEach { (href, source) ->
                val fileName = href.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .ifBlank { "chapter.xhtml" }
                    .takeLast(120)
                FileUtils.writeText(File(dir, "raw_$fileName").absolutePath, source)
            }
            FileUtils.writeText(File(dir, "rendered.html").absolutePath, renderedHtml)
            FileUtils.writeText(
                File(dir, "README.txt").absolutePath,
                "raw_*.xhtml/html 是 EPUB 解压后的原文；rendered.html 是应用解析 CSS 和资源路径后送入阅读器的内容。\n"
            )
        }.onFailure {
            AppLog.putDebug("写入 EPUB 调试原文失败\n${it.localizedMessage}", it)
        }
    }

    private fun EpubBody.applyEpubCss(res: Resource) {
        val rules = runCatching {
            val cssAssets = RustAnalyzerBridge.epubCssAssets(
                documentHtml = documentHtml,
                bodyHtml = html,
                rulePath = "EpubFile.cssAssets"
            )
            html = cssAssets.html
            val parsedRules = arrayListOf<EpubCss.Rule>()
            cssAssets.assets.forEach { asset ->
                when (asset.kind) {
                    "inline" -> parsedRules.addAll(parseCssRules(asset.content))
                    "stylesheet" -> asset.href.trim().takeIf { it.isNotBlank() }?.let { href ->
                        parsedRules.addAll(parseCssRules(loadCss(res.href, href)))
                    }
                }
            }
            parsedRules
        }.onFailure {
            AppLog.put("Epub CSS 解析失败, 已忽略样式\n${it.localizedMessage}", it)
        }.getOrDefault(emptyList())
        if (rules.isEmpty()) return
        val orderedRules = rules.mapIndexed { index, rule ->
            rule.copy(order = index)
        }
        val appliedCss = RustAnalyzerBridge.epubAppliedCss(
            bodyOuterHtml = outerHtml(),
            rulesJson = GSON.toJson(orderedRules),
            rulePath = "EpubFile.appliedCss"
        )
        html = appliedCss.html
        style = appliedCss.bodyStyle
        background = appliedCss.bodyBackground
    }

    private fun parseCssRules(css: String): List<EpubCss.Rule> {
        if (css.isBlank()) return emptyList()
        val cacheKey = "${css.length}:${css.hashCode()}"
        return cssRuleCache.getOrPut(cacheKey) {
            EpubCss.parseRules(css)
        }
    }

    private fun loadCss(baseHref: String, href: String): String {
        return runCatching {
            val resolvedHref = URLDecoder.decode(
                URI(baseHref.encodeURI()).resolve(href.encodeURI()).toString(),
                "UTF-8"
            )
            cssTextCache.getOrPut(resolvedHref) {
                epubBook?.resources?.getByHref(resolvedHref)?.data?.let {
                    String(it, mCharset).absolutizeCssUrls(resolvedHref)
                }.orEmpty()
            }
        }.getOrDefault("")
    }

    private fun String.absolutizeCssUrls(cssHref: String): String {
        val builder = StringBuilder(length)
        var index = 0
        while (index < length) {
            val start = indexOf("url(", index, ignoreCase = true)
            if (start < 0) {
                builder.append(substring(index))
                break
            }
            builder.append(substring(index, start))
            val valueStart = start + 4
            val end = findCssUrlEnd(valueStart)
            if (end < 0) {
                builder.append(substring(start))
                break
            }
            val raw = substring(valueStart, end).trim()
            val quote = raw.firstOrNull()?.takeIf { it == '\'' || it == '"' }
            val clean = raw.trimMatchingQuote()
            val resolved = if (clean.startsWith("data:", true) ||
                clean.startsWith("http://", true) ||
                clean.startsWith("https://", true)
            ) {
                clean
            } else {
                URLDecoder.decode(
                    URI(cssHref.encodeURI()).resolve(clean.encodeURI()).toString(),
                    "UTF-8"
                )
            }
            builder.append("url(")
            if (quote != null) {
                builder.append(quote).append(resolved).append(quote)
            } else {
                builder.append(resolved)
            }
            builder.append(")")
            index = end + 1
        }
        return builder.toString()
    }

    private fun String.findCssUrlEnd(start: Int): Int {
        var quote: Char? = null
        var index = start
        while (index < length) {
            val char = this[index]
            if (quote != null) {
                if (char == quote && getOrNull(index - 1) != '\\') {
                    quote = null
                }
                index++
                continue
            }
            when (char) {
                '\'', '"' -> quote = char
                ')' -> return index
            }
            index++
        }
        return -1
    }

    private fun String.escapeXmlAttr(): String {
        return replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun String.trimMatchingQuote(): String {
        val clean = trim()
        if (clean.length >= 2) {
            val first = clean.first()
            val last = clean.last()
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return clean.substring(1, clean.lastIndex)
            }
        }
        return clean
    }

    private fun resolveEpubResourceHref(baseHref: String, href: String): String {
        val cleanHref = href.stripUrlOptions()
            .substringBefore("?")
            .trim()
            .trimMatchingQuote()
        if (cleanHref.startsWith("data:", true) ||
            cleanHref.startsWith("http://", true) ||
            cleanHref.startsWith("https://", true)
        ) {
            return cleanHref
        }
        findEpubResource(cleanHref)?.let { return it.href }
        val resolved = runCatching {
            URLDecoder.decode(
                URI(baseHref.encodeURI()).resolve(cleanHref.encodeURI()).toString(),
                "UTF-8"
            )
        }.getOrDefault(cleanHref)
        findEpubResource(resolved)?.let { return it.href }
        return resolved
    }

    private fun findEpubResource(href: String): Resource? {
        val clean = href.stripUrlOptions()
            .substringBefore("?")
            .trim()
            .trimMatchingQuote()
        if (clean.isBlank()) return null
        val candidates = linkedSetOf(clean)
        runCatching { URLDecoder.decode(clean, "UTF-8") }.getOrNull()?.let { candidates.add(it) }
        candidates.toList().forEach { candidate ->
            candidates.add(candidate.trimStart('/'))
            candidates.addAll(candidate.epubPathFallbacks())
            candidates.add(candidate.encodeURI())
            runCatching { URLDecoder.decode(candidate.encodeURI(), "UTF-8") }.getOrNull()?.let {
                candidates.add(it)
                candidates.addAll(it.epubPathFallbacks())
            }
        }
        candidates.forEach { candidate ->
            epubBook?.resources?.getByHref(candidate)?.let { return it }
        }
        val normalized = candidates.map { it.trimStart('/').lowercase(Locale.ROOT) }.toSet()
        val fileName = clean.substringAfterLast('/').lowercase(Locale.ROOT)
        return epubBook?.resources?.all?.firstOrNull { resource ->
            val resourceHref = resource.href?.trimStart('/').orEmpty()
            val lower = resourceHref.lowercase(Locale.ROOT)
            lower in normalized || lower.endsWith("/$fileName") || lower == fileName
        }
    }

    private fun String.epubPathFallbacks(): List<String> {
        val clean = trimStart('/')
        val parts = clean.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val fallbacks = linkedSetOf<String>()
        val markerIndexes = parts.mapIndexedNotNull { index, part ->
            if (part.equals("OEBPS", ignoreCase = true) || part.equals("OPS", ignoreCase = true)) index else null
        }
        markerIndexes.forEach { index ->
            fallbacks.add(parts.drop(index).joinToString("/"))
        }
        val imageIndex = parts.indexOfLast { it.equals("Images", ignoreCase = true) || it.equals("Image", ignoreCase = true) }
        if (imageIndex >= 0) {
            fallbacks.add(parts.drop(imageIndex).joinToString("/"))
            fallbacks.add(parts.drop(imageIndex + 1).joinToString("/"))
        }
        fallbacks.add(parts.last())
        return fallbacks.filter { it.isNotBlank() && it != clean }
    }

    private fun getImage(href: String): InputStream? {
        val cleanHref = href.stripUrlOptions()
        if (cleanHref == "cover.jpeg") return epubBook?.coverImage?.inputStream
        return findEpubResource(cleanHref)?.inputStream
    }

    private fun String.stripUrlOptions(): String {
        val optionStart = indexOfUrlOptions()
        return if (optionStart != null) {
            substring(0, optionStart).trim()
        } else {
            trim()
        }
    }

    private fun String.indexOfUrlOptions(): Int? {
        for (index in indices) {
            if (this[index] != ',') continue
            var next = index + 1
            while (next < length && this[next].isWhitespace()) {
                next++
            }
            if (next < length && this[next] == '{') {
                return index
            }
        }
        return null
    }

    private fun upBookCover(fastCheck: Boolean = false): Boolean {
        return try {
            epubBook?.let {
                if (book.coverUrl.isNullOrEmpty()) {
                    book.coverUrl = LocalBook.findCoverPath(book) ?: LocalBook.getCoverPath(book)
                }
                if (fastCheck && File(book.coverUrl!!).exists()) {
                    return true
                }
                /*部分书籍DRM处理后，封面获取异常，待优化*/
                val cover = it.coverImage?.inputStream?.use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: findFallbackCoverBitmap()
                if (cover == null) {
                    AppLog.putDebug("Epub: 封面获取为空. path: ${book.bookUrl}")
                    return false
                }
                val coverPath = LocalBook.resolveCoverPath(book, cover.preferredCoverExtension())
                book.coverUrl = coverPath
                FileOutputStream(FileUtils.createFileIfNotExist(coverPath)).use { out ->
                    cover.compressPreservingAlpha(out, 90)
                    out.flush()
                }
                return true
            }
            false
        } catch (e: Exception) {
            AppLog.put("加载书籍封面失败\n${e.localizedMessage}", e)
            e.printOnDebug()
            false
        }
    }

    private fun ensureBookCoverLoaded() {
        if (coverLoadChecked) return
        val coverPath = book.coverUrl
        if (!coverPath.isNullOrBlank() && File(coverPath).exists()) {
            coverLoadChecked = true
            return
        }
        if (upBookCover(fastCheck = true)) {
            kotlin.runCatching { book.update() }
        }
        coverLoadChecked = true
    }

    private fun findFallbackCoverBitmap(): Bitmap? {
        val resources = epubBook?.resources?.all.orEmpty()
        val coverResource = resources.firstOrNull { resource ->
            val href = resource.href.orEmpty().lowercase(Locale.ROOT)
            val mediaType = resource.mediaType?.toString().orEmpty().lowercase(Locale.ROOT)
            (mediaType.startsWith("image/") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".png")) &&
                (href.contains("cover") || href.contains("titlepage") || href.contains("title"))
        } ?: resources.firstOrNull { resource ->
            val href = resource.href.orEmpty().lowercase(Locale.ROOT)
            val mediaType = resource.mediaType?.toString().orEmpty().lowercase(Locale.ROOT)
            mediaType.startsWith("image/") || href.endsWith(".jpg") || href.endsWith(".jpeg") || href.endsWith(".png")
        }
        return coverResource?.inputStream?.use { input ->
            BitmapFactory.decodeStream(input)
        }
    }

    private fun upBookInfo() {
        if (epubBook == null) {
            eFile = null
            book.intro = "书籍导入异常"
        } else {
            upBookCover()
            val metadata = epubBook!!.metadata
            book.name = metadata.firstTitle
            if (book.name.isEmpty()) {
                book.name = book.originName.replace(".epub", "")
            }

            if (metadata.authors.isNotEmpty()) {
                val author =
                    metadata.authors[0].toString().replace("^, |, $".toRegex(), "")
                book.author = author
            }
            if (metadata.descriptions.isNotEmpty()) {
                val desc = metadata.descriptions[0]
                book.intro = if (desc.isXml()) {
                    RustAnalyzerBridge.htmlTextArray(
                        metadata.descriptions[0],
                        "EpubFile.metadata.description"
                    ).joinToString("\n")
                } else {
                    desc
                }
            }
            findEpubBookInfo()?.let { info ->
                if (info.author.isNotBlank()) {
                    book.author = info.author
                }
                if (info.intro.isNotBlank()) {
                    book.intro = info.intro
                }
            }
        }
    }

    private fun getChapterList(): ArrayList<BookChapter> {
        val chapterList = ArrayList<BookChapter>()
        val cost = measureTimeMillis {
            epubBook?.let { eBook ->
                ensureBookCoverLoaded()
                warmChapterSpanIndex()
                val refs = eBook.tableOfContents.tocReferences
                if (refs == null || refs.isEmpty()) {
                    AppLog.putDebug("Epub: NCX file parse error, check the file: ${book.bookUrl}")
                    val spineReferences = eBook.spine.spineReferences
                    var i = 0
                    val size = spineReferences.size
                    while (i < size) {
                        val resource = spineReferences[i].resource
                        if (resource.isEpubBookInfoResource()) {
                            i++
                            continue
                        }
                        var title = resource.title
                        if (TextUtils.isEmpty(title)) {
                            try {
                                title = RustAnalyzerBridge.htmlTitle(
                                    String(resource.data, mCharset),
                                    "EpubFile.spineFallbackTitle"
                                )
                            } catch (e: IOException) {
                                e.printStackTrace()
                            }
                        }
                        val chapter = BookChapter()
                        chapter.index = i
                        chapter.bookUrl = book.bookUrl
                        chapter.url = resource.href
                        if (i == 0 && title.isEmpty()) {
                            chapter.title = "封面"
                        } else {
                            chapter.title = title.cleanEpubChapterTitle(resource, i)
                        }
                        chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
                        chapterList.add(chapter)
                        i++
                    }
                } else {
                    parseFirstPage(chapterList, refs)
                    parseMenu(chapterList, refs, 0)
                }
            }
            mergeMissingSpineChapters(chapterList)
            normalizeChapterList(chapterList)
            getWordCount(chapterList, book)
        }
        AppLog.putDebug("EPUB getChapterList done: chapters=${chapterList.size}, cost=${cost}ms")
        return chapterList
    }

    /**
     * EPUB 的 TOC/NCX 只是导航目录，不等于完整阅读顺序。
     * 一些书会把卷首页、人物图、插图页放在 spine 中但不写进 TOC，
     * 如果只按 TOC 生成章节就会出现“缺页”。
     */
    private fun mergeMissingSpineChapters(chapterList: ArrayList<BookChapter>) {
        val spineContents = epubSpineContents
            ?.filter { it.isReadableEpubResource() }
            ?.filterNot { it.isEpubBookInfoResource() }
            .orEmpty()
        if (spineContents.isEmpty()) return

        val spineOrder = spineContents
            .mapIndexed { index, resource -> resource.href to index }
            .toMap()
        val existingHrefSet = chapterList.asSequence()
            .filterNot { it.url.startsWith("skip:") }
            .map { it.url.substringBeforeLast("#") }
            .toMutableSet()
        var insertedCount = 0

        spineContents.forEachIndexed { spineIndex, resource ->
            if (!existingHrefSet.add(resource.href)) return@forEachIndexed
            val chapter = BookChapter()
            chapter.bookUrl = book.bookUrl
            chapter.url = resource.href
            chapter.title = resource.readableTitle(spineIndex)

            val insertIndex = chapterList.indexOfFirst { exist ->
                val existOrder = if (exist.url.startsWith("skip:")) {
                    null
                } else {
                    spineOrder[exist.url.substringBeforeLast("#")]
                }
                existOrder != null && existOrder > spineIndex
            }.let { if (it < 0) chapterList.size else it }

            chapterList.add(insertIndex, chapter)
            insertedCount++
            AppLog.put("EPUB spine 补页: href=${resource.href}, title=${chapter.title}, index=$spineIndex")
        }

        if (insertedCount > 0) {
            AppLog.put(
                "EPUB spine 补页完成: spine=${spineContents.size}, " +
                    "inserted=$insertedCount, final=${chapterList.size}"
            )
        }
    }

    /*获取书籍起始页内容。部分书籍第一章之前存在封面，引言，扉页等内容*/
    /*tile获取不同书籍风格杂乱，格式化处理待优化*/
    private var durIndex = 0
    private fun parseFirstPage(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?
    ) {
        val contents = epubSpineContents
        if (epubBook == null || contents == null || refs == null) return
        val firstRef = refs.firstOrNull { it.resource != null } ?: return
        var i = 0
        durIndex = 0
        while (i < contents.size) {
            val content = contents[i]
            if (!content.isReadableEpubResource()) {
                i++
                continue
            }
            if (content.isEpubBookInfoResource()) {
                i++
                continue
            }
            /**
             * 检索到第一章href停止
             * completeHref可能有fragment(#id) 必须去除
             * fix https://github.com/gedoor/legado/issues/1932
             */
            if (firstRef.completeHref.substringBeforeLast("#") == content.href) break
            val chapter = BookChapter()
            var title = content.title
            if (TextUtils.isEmpty(title)) {
                title = content.readableTitle(i)
            }
            chapter.bookUrl = book.bookUrl
            chapter.title = title
            chapter.url = content.href
            chapter.startFragmentId =
                if (content.href.substringAfter("#") == content.href) null
                else content.href.substringAfter("#")

            chapterList.lastOrNull()?.endFragmentId = chapter.startFragmentId
            chapterList.lastOrNull()?.putVariable("nextUrl", chapter.url)
            chapterList.add(chapter)
            durIndex++
            i++
        }
    }

    private fun parseMenu(
        chapterList: ArrayList<BookChapter>,
        refs: List<TOCReference>?,
        level: Int
    ) {
        refs?.forEach { ref ->
            if (ref.resource != null) {
                if (ref.resource.isEpubBookInfoResource()) {
                    if (ref.children != null && ref.children.isNotEmpty()) {
                        parseMenu(chapterList, ref.children, level + 1)
                    }
                    return@forEach
                }
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title.cleanEpubChapterTitle(ref.resource, chapterList.size)
                chapter.url = ref.completeHref
                chapter.startFragmentId = ref.fragmentId
                chapter.isVolume = ref.children != null && ref.children.isNotEmpty()
                chapterList.add(chapter)
                durIndex++
            } else if (!ref.title.isNullOrBlank()) {
                val chapter = BookChapter()
                chapter.bookUrl = book.bookUrl
                chapter.title = ref.title.cleanEpubChapterTitle(null, chapterList.size)
                chapter.url = "skip:${chapterList.size}:${ref.title}"
                chapter.isVolume = true
                chapterList.add(chapter)
            }
            if (ref.children != null && ref.children.isNotEmpty()) {
                chapterList.lastOrNull()?.isVolume = true
                parseMenu(chapterList, ref.children, level + 1)
            }
        }
    }

    private fun Resource.isReadableEpubResource(): Boolean {
        val lowerHref = href.lowercase(Locale.ROOT)
        if (!mediaType.toString().contains("htm") &&
            !lowerHref.endsWith(".html") &&
            !lowerHref.endsWith(".xhtml") &&
            !lowerHref.endsWith(".htm")
        ) {
            return false
        }
        return true
    }

    private fun Resource.readableTitle(spineIndex: Int): String {
        val hrefName = href.substringAfterLast('/').substringBeforeLast('.').trim()
        if (!title.isNullOrBlank() && !title.isLikelyEpubFileTitle(hrefName)) {
            return title.cleanEpubChapterTitle(this, spineIndex)
        }
        val html = runCatching { String(data, mCharset) }.getOrDefault("")
        val titleText = RustAnalyzerBridge.epubReadableTitle(html, "EpubFile.readableTitle").trim()
        if (!titleText.isNullOrBlank()) return titleText.cleanEpubChapterTitle(this, spineIndex)
        return title.cleanEpubChapterTitle(this, spineIndex).ifBlank {
            fallbackEpubSpineTitle(hrefName, spineIndex)
        }
    }

    private fun String?.cleanEpubChapterTitle(resource: Resource?, index: Int): String {
        val raw = orEmpty()
            .cleanEpubInfoText()
            .replace(Regex("\\s+"), " ")
            .trim('-', '—', '–', '_', ' ', '　')
            .trim()
        val lower = raw.lowercase(Locale.ROOT)
        val hrefName = resource?.href
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            ?.cleanEpubInfoText()
            .orEmpty()
        if (raw.isBlank()) return fallbackEpubSpineTitle(hrefName, index)
        val generic = raw == "卷首" ||
            raw == "卷首页" ||
            raw == "chapter" ||
            raw == "untitled" ||
            raw.isLikelyEpubFileTitle(hrefName) ||
            lower.matches(Regex("chapter\\s*\\d+\\s*-\\s*\\d+")) ||
            lower.matches(Regex("section\\d+")) ||
            lower.matches(Regex("qynmn\\d+"))
        if (!generic) return raw
        return when {
            hrefName.contains("gallery", ignoreCase = true) -> "人物画廊"
            hrefName.contains("cover", ignoreCase = true) -> "封面"
            hrefName.contains("intro", ignoreCase = true) -> "简介"
            hrefName.contains("copyright", ignoreCase = true) -> "版权信息"
            hrefName.matches(Regex("qynmn\\d+", RegexOption.IGNORE_CASE)) -> "人物图鉴 ${index + 1}"
            hrefName.matches(Regex("section\\d+", RegexOption.IGNORE_CASE)) -> "插图页 ${index + 1}"
            hrefName.matches(Regex("chapter\\d*", RegexOption.IGNORE_CASE)) -> "章节 ${index + 1}"
            hrefName.isNotBlank() && !hrefName.isLikelyEpubFileTitle(hrefName) -> hrefName
            else -> "卷首 ${index + 1}"
        }
    }

    private fun String?.isLikelyEpubFileTitle(hrefName: String): Boolean {
        val clean = orEmpty().cleanEpubInfoText().trim()
        if (clean.isBlank()) return true
        val lower = clean.lowercase(Locale.ROOT)
        val cleanHref = hrefName.cleanEpubInfoText().trim().lowercase(Locale.ROOT)
        return lower == cleanHref ||
            lower.matches(Regex("qynmn\\d+")) ||
            lower.matches(Regex("section\\d+")) ||
            lower.matches(Regex("chapter\\d*")) ||
            lower.matches(Regex("chapter\\s*\\d+\\s*-\\s*\\d+"))
    }

    private fun fallbackEpubSpineTitle(hrefName: String, index: Int): String {
        return when {
            hrefName.contains("gallery", ignoreCase = true) -> "人物画廊"
            hrefName.contains("cover", ignoreCase = true) -> "封面"
            hrefName.contains("intro", ignoreCase = true) -> "简介"
            hrefName.contains("copyright", ignoreCase = true) -> "版权信息"
            hrefName.matches(Regex("qynmn\\d+", RegexOption.IGNORE_CASE)) -> "人物图鉴 ${index + 1}"
            hrefName.matches(Regex("section\\d+", RegexOption.IGNORE_CASE)) -> "插图页 ${index + 1}"
            hrefName.matches(Regex("chapter\\d*", RegexOption.IGNORE_CASE)) -> "章节 ${index + 1}"
            else -> "EPUB 页面 ${index + 1}"
        }
    }

    private fun findEpubBookInfo(): EpubBookInfo? {
        return epubBook?.contents
            ?.asSequence()
            ?.filter { it.mediaType.toString().contains("htm") }
            ?.mapNotNull { it.extractEpubBookInfo() }
            ?.firstOrNull()
    }

    private fun Resource.isEpubBookInfoResource(): Boolean {
        return extractEpubBookInfo() != null
    }

    private fun Resource.extractEpubBookInfo(): EpubBookInfo? {
        val html = runCatching { String(data, mCharset) }.getOrNull() ?: return null
        val info = RustAnalyzerBridge.epubBookInfo(html, "EpubFile.bookInfo")
        if (!info.isBookInfo) return null
        return EpubBookInfo(author = info.author, intro = info.intro)
    }

    private fun String.cleanEpubInfoText(): String {
        return replace('\u00A0', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('　')
            .trim()
    }

    private data class EpubBookInfo(
        val author: String,
        val intro: String
    )

    internal data class EpubFootnote(
        val title: String,
        val html: String
    )

    private data class FootnoteSource(
        val href: String,
        val html: String
    )

    private fun normalizeChapterList(chapterList: ArrayList<BookChapter>) {
        if (chapterList.isEmpty()) return
        val titleCounts = linkedMapOf<String, Int>()
        for (index in chapterList.indices) {
            val chapter = chapterList[index]
            chapter.index = index
            chapter.title = chapter.title.cleanEpubChapterTitle(
                findEpubResource(chapter.url.substringBeforeLast("#")),
                index
            )
            val count = titleCounts.getOrDefault(chapter.title, 0) + 1
            titleCounts[chapter.title] = count
            if (count > 1 && chapter.title.isGenericEpubTitle()) {
                chapter.title = "${chapter.title} $count"
            }
            val next = chapterList.getOrNull(index + 1)
            if (chapter.isVolume &&
                next != null &&
                !chapter.url.startsWith("skip:") &&
                chapter.url.substringBeforeLast("#") == next.url.substringBeforeLast("#")
            ) {
                chapter.url = "skip:${index}:${chapter.url}"
                chapter.startFragmentId = null
                chapter.endFragmentId = null
            }
        }
        for (index in chapterList.indices) {
            val chapter = chapterList[index]
            val next = chapterList.drop(index + 1)
                .firstOrNull { !(it.isVolume && it.url.startsWith("skip:")) }
            chapter.endFragmentId = next?.startFragmentId
            chapter.putVariable("nextUrl", next?.url)
        }
    }

    private fun String.isGenericEpubTitle(): Boolean {
        val clean = cleanEpubInfoText().trim('-', '—', '–', '_', ' ', '　')
        return clean == "卷首" ||
            clean.startsWith("卷首 ") ||
            clean == "封面" ||
            clean == "插图" ||
            clean == "人物画廊" ||
            clean.startsWith("EPUB 页面")
    }


    protected fun finalize() {
        close()
    }

    private fun close() {
        invalidateBookCache(closeDescriptor = true)
    }

    private fun getWordCount(list: ArrayList<BookChapter>, book: Book) {
        if (!AppConfig.tocCountWords) {
            return
        }
        val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
        if (chapterList.isNotEmpty()) {
            val map = chapterList.associateBy({ it.getFileName() }, { it.wordCount })
            for (bookChapter in list) {
                val wordCount = map[bookChapter.getFileName()]
                if (wordCount != null) {
                    bookChapter.wordCount = wordCount
                }
            }
        }
    }

}
