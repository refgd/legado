package io.legado.app.ui.book.read.page.provider

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.URLSpan
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PageAnim
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookContent
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isEpub
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ImageProvider
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.TextLine
import io.legado.app.ui.book.read.page.entities.TextPage
import io.legado.app.ui.book.read.page.entities.column.ImageColumn
import io.legado.app.ui.book.read.page.entities.column.TextColumn
import io.legado.app.utils.dpToPx
import io.legado.app.utils.fastSum
import io.legado.app.utils.getTextWidthsCompat
import io.legado.app.utils.splitNotBlank
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.LinkedList
import kotlin.math.roundToInt
import android.util.Size
import androidx.core.text.HtmlCompat
import io.legado.app.constant.AppPattern.noWordCountRegex
import io.legado.app.data.appDb
import io.legado.app.ui.book.read.page.entities.TextLine.Companion.atLeastApi28
import io.legado.app.ui.book.read.page.entities.column.TextHtmlColumn
import io.legado.app.ui.book.read.page.provider.ChapterProvider.reviewStr
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceStr
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplaceChar
import io.legado.app.ui.book.read.page.provider.ChapterProvider.srcReplacementChar
import io.legado.app.utils.StringUtils
import androidx.core.text.parseAsHtml
import androidx.core.util.component1
import androidx.core.util.component2
import io.legado.app.help.TextViewTagHandler
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_CHAR
import io.legado.app.help.TextViewTagHandler.Companion.HR_PLACE_STR
import io.legado.app.model.localBook.EpubCss
import io.legado.app.model.localBook.EpubFile
import io.legado.app.model.localBook.EpubImageBox
import io.legado.app.model.localBook.EpubLayoutDocument
import io.legado.app.model.localBook.EpubPageColor
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.model.webBook.RustHtmlRenderAction
import io.legado.app.model.webBook.RustHtmlImageInfo
import io.legado.app.model.webBook.RustHtmlPageBackground
import io.legado.app.ui.book.read.page.entities.column.BaseColumn
import io.legado.app.ui.book.read.page.entities.column.TextBaseColumn
import io.legado.app.utils.UrlOptions
import io.legado.app.ui.book.read.page.provider.ChapterProvider.reviewChar

class TextChapterLayout(
    scope: CoroutineScope,
    private val textChapter: TextChapter,
    private val textPages: ArrayList<TextPage>,
    private val book: Book,
    private val bookContent: BookContent,
) {

    @Volatile
    private var listener: LayoutProgressListener? = textChapter

    private val paddingLeft = ChapterProvider.paddingLeft
    private val paddingRight = ChapterProvider.paddingRight
    private val paddingTop = ChapterProvider.paddingTop

    private val titlePaint = ChapterProvider.titlePaint
    private val titlePaintTextHeight = ChapterProvider.titlePaintTextHeight
    private val titlePaintFontMetrics = ChapterProvider.titlePaintFontMetrics

    private val contentPaint = ChapterProvider.contentPaint
    private val reviewCharWidth by lazy { contentPaint.measureText(srcReplaceStr) * 1.5556f }
    private val contentPaintTextHeight = ChapterProvider.contentPaintTextHeight
    private val contentPaintFontMetrics = ChapterProvider.contentPaintFontMetrics

    private val titleTopSpacing = ChapterProvider.titleTopSpacing
    private val titleBottomSpacing = ChapterProvider.titleBottomSpacing
    private val lineSpacingExtra = ChapterProvider.lineSpacingExtra
    private val paragraphSpacing = ChapterProvider.paragraphSpacing

    private val visibleHeight = ChapterProvider.visibleHeight
    private val viewHeight = ChapterProvider.viewHeight
    private val visibleWidth = ChapterProvider.visibleWidth

    private val viewWidth = ChapterProvider.viewWidth
    private val doublePage = ChapterProvider.doublePage
    private val isClassicEpub = book.isEpub &&
        AppConfig.epubParseMode == AppConfig.EPUB_PARSE_MODE_CLASSIC
    private val indentCharWidth = ChapterProvider.indentCharWidth
    private val stringBuilder = StringBuilder()

    private val paragraphIndent = ReadBookConfig.paragraphIndent
    private val titleMode = ReadBookConfig.titleMode
    private val useZhLayout = ReadBookConfig.useZhLayout
    private val isMiddleTitle = ReadBookConfig.isMiddleTitle
    private val textFullJustify = ReadBookConfig.textFullJustify
    private val adaptSpecialStyle = AppConfig.adaptSpecialStyle
    private val pageAnim = book.getPageAnim()

    private var pendingTextPage = TextPage()

    private val bookChapter inline get() = textChapter.chapter
    private val displayTitle inline get() = textChapter.title
    private val chaptersSize inline get() = textChapter.chaptersSize

    private var durY = 0f
    private var absStartX = paddingLeft
    private var floatArray = FloatArray(128)
    private var pendingSingleImagePageBreak = false
    private var activeEpubBlockDecoration: ActiveEpubBlockDecoration? = null

    private var isCompleted = false
    private val job: Coroutine<*>

    var exception: Throwable? = null

    var channel = Channel<TextPage>(Channel.UNLIMITED)

    private data class InlineColumnStyle(
        val underline: Boolean = false,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val textSizeScale: Float = 1f,
        val baselineShiftEm: Float = 0f
    ) {
        val hasStyle: Boolean
            get() = underline || bold || italic || strike ||
                textSizeScale != 1f || baselineShiftEm != 0f
    }

    private data class ParsedInlineText(
        val text: String,
        val styles: List<InlineColumnStyle?>
    ) {
        fun styleAt(index: Int): InlineColumnStyle? = styles.getOrNull(index)
    }

    private data class MeasuredWords(
        val words: ArrayList<String>,
        val widths: ArrayList<Float>,
        val offsets: ArrayList<Int>
    )


    init {
        job = Coroutine.async(
            scope,
            start = CoroutineStart.LAZY,
            executeContext = IO
        ) {
            launch {
                val bookSource = book.getBookSource() ?: return@launch
                BookHelp.saveImages(bookSource, book, bookChapter, bookContent.toString())
            }
            getTextChapter(book, bookChapter, displayTitle, bookContent)
        }.onError {
            exception = it
            onException(it)
        }.onCancel {
            channel.cancel()
        }.onFinally {
            isCompleted = true
        }
        job.start()
    }

    fun cancel() {
        job.cancel()
        listener = null
    }

    private fun onPageCompleted() {
        val textPage = pendingTextPage
        if (textPage.lines.isEmpty() &&
            !textPage.hasEpubContent() &&
            textPage.epubEmbeddedBlocks.isEmpty() &&
            stringBuilder.isBlank()
        ) {
            return
        }
        textPage.index = textPages.size
        textPage.chapterIndex = bookChapter.index
        textPage.chapterSize = chaptersSize
        textPage.title = displayTitle
        textPage.doublePage = doublePage
        textPage.paddingTop = paddingTop
        textPage.fallbackChapterPosition = textPage.lines.firstOrNull()?.chapterPosition
            ?: textPages.lastOrNull()?.let { lastPage ->
                lastPage.chapterPosition + lastPage.charSize
            } ?: 0
        textPage.isCompleted = true
        textPage.textChapter = textChapter
        if (textPage.hasEpubBackground()) {
            textPage.height = textPage.height.coerceAtLeast(viewHeight.toFloat())
        }
        textPage.upLinesPosition()
        textPage.upRenderHeight()
        textPages.add(textPage)
        channel.trySend(textPage)
        try {
            listener?.onLayoutPageCompleted(textPages.lastIndex, textPage)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        }
    }

    private fun onCompleted() {
        channel.close()
        try {
            listener?.onLayoutCompleted()
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    private fun onException(e: Throwable) {
        channel.close(e)
        if (e is CancellationException) {
            listener = null
            return
        }
        try {
            listener?.onLayoutException(e)
        } catch (e: Exception) {
            e.printStackTrace()
            AppLog.put("调用布局进度监听回调出错\n${e.localizedMessage}", e)
        } finally {
            listener = null
        }
    }

    /**
     * 获取拆分完的章节数据
     */
    private suspend fun getTextChapter(
        book: Book,
        bookChapter: BookChapter,
        displayTitle: String,
        bookContent: BookContent,
    ) {
        val contents = bookContent.textList
        val imageStyle = book.getImageStyle()
        val isSingleImageStyle = imageStyle.equals(Book.imgStyleSingle, true)

        val useNovelChrome = !isClassicEpub
        if (useNovelChrome && (titleMode != 2 || bookChapter.isVolume || contents.isEmpty())) {
            var firstLine = true
            //标题非隐藏
            val advancedTitleHandled = titleMode == AdvancedTitleConfig.TITLE_MODE_ADVANCED &&
                !bookChapter.isVolume &&
                setTypeAdvancedTitle(book, displayTitle)
            val advancedTitleFallback = titleMode == AdvancedTitleConfig.TITLE_MODE_ADVANCED &&
                !advancedTitleHandled
            val titleLines: Array<String> = if (advancedTitleHandled) {
                emptyArray()
            } else {
                displayTitle.splitNotBlank("\n")
            }
            for (text in titleLines) {
                val srcList = LinkedList<String>()
                val clickList = LinkedList<String?>()
                val titleImg = if (firstLine) {
                    firstLine = false
                    bookChapter.imgUrl
                } else {
                    null
                }
                val imgText = if (titleImg.isNullOrEmpty()) {
                    null
                } else {
                    val urlMatcher = UrlOptions.paramPattern.matcher(titleImg)
                    var click: String? = null
                    var style: String? = null
                    var imgSize = ImageProvider.getImageSize(book, titleImg, ReadBook.bookSource)
                    if (urlMatcher.find()) {
                        var width: String? = null
                        val urlOptionStr = titleImg.substring(urlMatcher.end())
                        UrlOptions.parseStringMap(urlOptionStr, "TextChapterLayout.titleImage")
                            .let { map ->
                                map.forEach { (key, value) ->
                                    when (key) {
                                        "style" -> style = value
                                        "width" -> width = value
                                        "click" -> click = value
                                    }
                                }
                            }
                        width?.let {
                            imgSize = imgSize.applyWidth(it)
                        }
                    }
                    if (style == null) {
                        style = if (imgSize.width < 80 && imgSize.height < 80) {
                            "text"
                        } else {
                            imageStyle
                        }
                    }
                    when (style) {
                        "text" -> {
                            srcList.add(titleImg)
                            clickList.add(click)
                            srcReplaceChar
                        }
                        "TEXT" -> {
                            srcList.add(titleImg)
                            clickList.add(click)
                            reviewChar
                        }
                        else -> {
                            setTypeImage(
                                book,
                                titleImg,
                                contentPaintTextHeight,
                                style,
                                imgSize,
                                click
                            )
                            null
                        }
                    }
                }
                setTypeText(
                    book,
                    if (imgText != null) text + imgText else text,
                    titlePaint,
                    titlePaintTextHeight,
                    titlePaintFontMetrics,
                    imageStyle,
                    srcList = srcList,
                    clickList = clickList,
                    isTitle = true,
                    emptyContent = contents.isEmpty(),
                    isVolumeTitle = bookChapter.isVolume,
                    forceMiddleTitle = advancedTitleFallback
                )
                pendingTextPage.lines.last().isParagraphEnd = true
                stringBuilder.append("\n")
            }
            if (!advancedTitleHandled) {
                durY += titleBottomSpacing
            }

            // 如果是单图模式且当前页有内容，强制分页
            val keepTitleForNextImage = pendingTextPage.lines.all { it.isTitle && !it.isImage }
            if (isSingleImageStyle && pendingTextPage.lines.isNotEmpty() && contents.isNotEmpty() &&
                !keepTitleForNextImage
            ) {
                prepareNextPageIfNeed()
            }
        }

        val isTextImageStyle = imageStyle.equals(Book.imgStyleText, true)

        val sb = StringBuffer()
        var isSetTypedImage = false
        var wordCount = 0
        contents.forEach { content ->
            currentCoroutineContext().ensureActive()
            if (adaptSpecialStyle) {
                val text = content.trim()
                if (text == "[newpage]") {
                    prepareNextPageIfNeed()
                    return@forEach
                } else if (text == EpubFile.READABLE_CONTENT_VERSION_FLAG) {
                    return@forEach
                } else if (text.startsWith(EpubFile.NATIVE_CONTENT_FLAG)) {
                    if (isClassicEpub) {
                        setTypeNativeEpubLayout(text)
                    }
                    return@forEach
                } else if (text.startsWith("<usehtml")) {
                    val contentStart = text.indexOf('>')
                    val contentEnd = text.lastIndexOf("<")
                    if (contentStart >= 0 && contentEnd > contentStart) {
                        setTypeHtml(imageStyle, book, text.substring(contentStart + 1, contentEnd))
                        return@forEach
                    }
                }
            }
            var text = content.replace(srcReplaceChar, srcReplacementChar)
            if (isTextImageStyle) {
                //图片样式为文字嵌入类型
                val srcList = LinkedList<String>()
                sb.setLength(0)
                val matcher = AppPattern.imgPattern.matcher(text)
                while (matcher.find()) {
                    matcher.group(1)?.let { src ->
                        srcList.add(src)
                        matcher.appendReplacement(sb, srcReplaceStr)
                    }
                }
                matcher.appendTail(sb)
                text = sb.toString()
                wordCount += text.replace(noWordCountRegex,"").length
                setTypeText(
                    book,
                    text,
                    contentPaint,
                    contentPaintTextHeight,
                    contentPaintFontMetrics,
                    imageStyle,
                    srcList = srcList,
                    clickList = null
                )
            } else {
                if (isSingleImageStyle && isSetTypedImage) {
                    isSetTypedImage = false
                    prepareNextPageIfNeed()
                }
                var start = 0
                val srcList = LinkedList<String>()
                val clickList = LinkedList<String?>()
                sb.setLength(0)
                var isFirstLine = true
                if (content.contains("<img")) {
                    val matcher = AppPattern.imgPattern.matcher(text)
                    while (matcher.find()) {
                        currentCoroutineContext().ensureActive()
                        val imgSrc = matcher.group(1)!!
                        var style: String? = null
                        var click: String? = null
                        var imgSize = ImageProvider.getImageSize(book, imgSrc, ReadBook.bookSource)
                        val urlMatcher = UrlOptions.paramPattern.matcher(imgSrc)
                        if (urlMatcher.find()) {
                            var width: String? = null
                            val urlOptionStr = imgSrc.substring(urlMatcher.end())
                            UrlOptions.parseStringMap(urlOptionStr, "TextChapterLayout.inlineImage").let { map ->
                                map.forEach { (key, value) ->
                                    when (key) {
                                        "style" -> style = value
                                        "width" -> width = value
                                        "click" -> click = value
                                    }
                                }
                            }
                            width?.let {
                                imgSize = imgSize.applyWidth(it)
                            }
                        }
                        if (style == null) {
                            style = if (imgSize.width < 80 && imgSize.height < 80) {
                                "text"
                            } else {
                                imageStyle
                            }
                        }
                        if (start < matcher.start()) {
                            sb.append(text.subSequence(start, matcher.start()))
                        }
                        when (style) {
                            "TEXT" -> {
                                sb.append(reviewChar)
                                srcList.add(imgSrc)
                                clickList.add(click)
                            }
                            "text" -> {
                                sb.append(srcReplaceChar)
                                srcList.add(imgSrc)
                                clickList.add(click)
                            }
                            else -> {
                                val textBefore = sb.toString()
                                if (textBefore.isNotBlank()) {
                                    wordCount += textBefore.replace(noWordCountRegex, "").length
                                    setTypeText(
                                        book,
                                        sb.toString(),
                                        contentPaint,
                                        contentPaintTextHeight,
                                        contentPaintFontMetrics,
                                        "TEXT",
                                        isFirstLine = isFirstLine,
                                        srcList = srcList,
                                        clickList = clickList
                                    )
                                    sb.setLength(0)
                                    isFirstLine = false
                                }
                                setTypeImage(
                                    book,
                                    imgSrc,
                                    contentPaintTextHeight,
                                    style,
                                    imgSize,
                                    click
                                )
                                isSetTypedImage = true
                            }
                        }
                        start = matcher.end()
                    }
                }
                if (start < content.length) {
                    if (isSingleImageStyle && isSetTypedImage) {
                        isSetTypedImage = false
                        prepareNextPageIfNeed()
                    }
                    val textAfter = content.subSequence(start, content.length)
                    sb.append(textAfter)
                }
                text = sb.toString()
                if (text.isNotBlank()) {
                    wordCount += text.replace(noWordCountRegex,"").length
                    setTypeText(
                        book,
                        text,
                        contentPaint,
                        contentPaintTextHeight,
                        contentPaintFontMetrics,
                        "TEXT",
                        isFirstLine = isFirstLine,
                        srcList = srcList,
                        clickList = clickList
                    )
                }
            }
            pendingTextPage.lines.lastOrNull()?.isParagraphEnd = true
            stringBuilder.append("\n")
        }
        val chapterWordCount = StringUtils.wordCountFormat(wordCount.toString())
        bookChapter.wordCount = chapterWordCount
        appDb.bookChapterDao.upWordCount(bookChapter.bookUrl, bookChapter.url, chapterWordCount)
        val textPage = pendingTextPage
        val endPadding = 20.dpToPx()
        val durYPadding = durY + endPadding
        if (textPage.height < durYPadding) {
            textPage.height = durYPadding
        } else {
            textPage.height += endPadding
        }
        textPage.text = stringBuilder.toString()
        currentCoroutineContext().ensureActive()
        onPageCompleted()
        onCompleted()
    }

    /**
     * 排版图片
     */
    private suspend fun setTypeImage(
        book: Book,
        src: String,
        textHeight: Float,
        imageStyle: String?,
        size: Size,
        click: String?
    ) {
        breakAfterSingleImageIfNeed()
        if (size.width > 0 && size.height > 0) {
            prepareNextPageIfNeed(durY)
            var height = size.height
            var width = size.width
            val normalizedImageStyle = imageStyle?.uppercase()
            when (normalizedImageStyle) {
                Book.imgStyleFull -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (pageAnim != PageAnim.scrollPageAnim && height > visibleHeight - durY) {
                        if (height > visibleHeight) {
                            width = width * visibleHeight / height
                            height = visibleHeight
                        }
                        prepareNextPageIfNeed(durY + height)
                    }
                }

                Book.imgStyleSingle -> {
                    width = visibleWidth
                    height = size.height * visibleWidth / size.width
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    val mergeWithTitle = shouldMergeSingleImageWithTitle(src)
                    if (mergeWithTitle) {
                        val availableHeight = visibleHeight - durY
                        if (availableHeight > textHeight * 4 && height > availableHeight) {
                            width = (width * availableHeight / height).toInt()
                            height = availableHeight.toInt()
                        } else if (availableHeight <= textHeight * 4 && durY > 0f) {
                            prepareNextPageIfNeed()
                        }
                    } else if (durY > 0f) {
                        prepareNextPageIfNeed()
                    }

                    // 图片竖直方向居中：调整 Y 坐标
                    if (!mergeWithTitle && height < visibleHeight) {
                        val adjustHeight = (visibleHeight - height) / 2f
                        durY = adjustHeight // 将 Y 坐标设置为居中位置
                    }
                }

                else -> {
                    if (size.width > visibleWidth) {
                        height = size.height * visibleWidth / size.width
                        width = visibleWidth
                    }
                    if (height > visibleHeight) {
                        width = width * visibleHeight / height
                        height = visibleHeight
                    }
                    prepareNextPageIfNeed(durY + height)
                }
            }
            val textLine = TextLine(isImage = true)
            textLine.text = " "
            textLine.lineTop = durY + paddingTop
            durY += height
            textLine.lineBottom = durY + paddingTop
            val (start, end) = if (visibleWidth > width) {
                when (normalizedImageStyle) {
                    "RIGHT" -> Pair(visibleWidth - width, visibleWidth)
                    "LEFT" -> Pair(0f, width)
                    else -> {
                        val adjustWidth = (visibleWidth - width) / 2f
                        Pair(adjustWidth, adjustWidth + width)
                    }
                }
            } else {
                Pair(0f, width)
            }
            textLine.addColumn(
                ImageColumn(start = absStartX + start.toFloat(), end = absStartX + end.toFloat(), src = src, click = click)
            )
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(" ") // 确保翻页时索引计算正确
            pendingTextPage.addLine(textLine)
            upsertActiveEpubBlockDecoration(pendingTextPage, textPages.size)
            if (normalizedImageStyle == Book.imgStyleSingle) {
                pendingSingleImagePageBreak = true
            }
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private fun shouldMergeSingleImageWithTitle(src: String): Boolean {
        val lines = pendingTextPage.lines
        if (lines.isEmpty() || lines.any { !it.isTitle || it.isImage }) {
            return false
        }
        return true
    }

    private suspend fun breakAfterSingleImageIfNeed() {
        if (!pendingSingleImagePageBreak) return
        pendingSingleImagePageBreak = false
        if (pendingTextPage.lines.isNotEmpty()) {
            prepareNextPageIfNeed()
        }
    }

    /**
     * 排版html样式
     */
    private suspend fun setTypeNativeEpubLayout(rawNativeEntry: String): Boolean {
        if (!isClassicEpub) {
            AppLog.put("EPUB Native Layout abort: 当前书籍不是 EPUB, book=${book.name}")
            return false
        }
        val hrefs = RustAnalyzerBridge.epubNativeEntryHrefs(
            rawNativeEntry,
            "TextChapterLayout.epubNativeEntry"
        ).ifEmpty {
                val reason = "未找到 epub-native[data-href]"
                AppLog.put(
                    "EPUB Native Layout error: $reason, " +
                        "chapter=${bookChapter.index}:${bookChapter.title}, rawHead=${rawNativeEntry.take(160)}"
                )
                setTypeEpubDiagnosticPage(reason, rawNativeEntry.take(180))
                return true
            }
        if (hrefs.isEmpty()) {
            val reason = "data-href 为空"
            AppLog.put(
                "EPUB Native Layout error: $reason, " +
                    "chapter=${bookChapter.index}:${bookChapter.title}"
            )
            setTypeEpubDiagnosticPage(reason, rawNativeEntry.take(180))
            return true
        }
        AppLog.putDebug(
            "EPUB Native Layout request: chapter=${bookChapter.index}:${bookChapter.title}, " +
                "hrefs=${hrefs.joinToString()}, view=${ChapterProvider.visibleWidth}x${ChapterProvider.visibleHeight}"
        )
        var rendered = false
        hrefs.forEach { href ->
            val layout = EpubFile.getNativeLayout(book, href) ?: run {
                AppLog.putDebug(
                    "EPUB Native Layout skip: getNativeLayout 返回 null, " +
                        "chapter=${bookChapter.index}:${bookChapter.title}, href=$href, " +
                        "view=${ChapterProvider.visibleWidth}x${ChapterProvider.visibleHeight}"
                )
                return@forEach
            }
            if (layout.pages.isEmpty()) {
                AppLog.putDebug(
                    "EPUB Native Layout skip: layout 页数为 0, " +
                        "chapter=${bookChapter.index}:${bookChapter.title}, href=$href"
                )
                return@forEach
            }
            AppLog.putDebug(
                "EPUB Native Layout success: chapter=${bookChapter.index}:${bookChapter.title}, " +
                    "href=$href, pages=${layout.pages.size}"
            )
            setTypeNativeEpubLayout(layout)
            rendered = true
        }
        if (!rendered) {
            val reason = "getNativeLayout 全部返回空"
            setTypeEpubDiagnosticPage(reason, "hrefs=${hrefs.joinToString()}")
        }
        return true
    }


    private suspend fun setTypeEpubDiagnosticPage(reason: String, detail: String) {
        if (pendingTextPage.lines.isNotEmpty() ||
            pendingTextPage.epubNativeCommands.isNotEmpty() ||
            pendingTextPage.hasEpubBackground() ||
            stringBuilder.isNotBlank()
        ) {
            prepareNextPageIfNeed()
        }
        val message = buildString {
            append("EPUB 原生排版失败")
            append("\n")
            append(reason)
            if (detail.isNotBlank()) {
                append("\n")
                append(detail)
            }
        }
        setTypeText(
            book = book,
            text = message,
            textPaint = contentPaint,
            textHeight = contentPaintTextHeight,
            fontMetrics = contentPaintFontMetrics,
            imageStyle = Book.imgStyleDefault,
            clickList = null
        )
        prepareNextPageIfNeed()
    }

    private suspend fun setTypeAdvancedTitle(book: Book, title: String): Boolean {
        if (title.isBlank()) return false
        if (pageAnim == PageAnim.scrollPageAnim) return false
        currentCoroutineContext().ensureActive()
        val lottieJson = AdvancedTitleConfig.renderValidLottieJson(book, title) ?: return false
        val blockHeight = (visibleHeight * (AdvancedTitleConfig.heightFactor / 100f))
            .coerceAtLeast(80f)
            .coerceAtMost(visibleHeight * 0.6f)
        val startY = durY + titleTopSpacing
        prepareNextPageIfNeed(startY + blockHeight)
        pendingTextPage.epubEmbeddedBlocks.add(
            TextPage.EpubEmbeddedBlock(
                offsetX = paddingLeft.toFloat(),
                offsetY = paddingTop + startY,
                width = visibleWidth.toFloat(),
                height = blockHeight,
                commands = emptyList(),
                role = AdvancedTitleConfig.LOTTIE_BLOCK_ROLE,
                payload = lottieJson
            )
        )
        durY = startY + blockHeight + titleBottomSpacing
        if (pendingTextPage.height < durY) {
            pendingTextPage.height = durY
        }
        return true
    }

    private suspend fun setTypeNativeEpubLayout(layout: EpubLayoutDocument) {
        if (pendingTextPage.lines.isNotEmpty() ||
            pendingTextPage.epubNativeCommands.isNotEmpty() ||
            pendingTextPage.hasEpubBackground() ||
            stringBuilder.isNotBlank()
        ) {
            prepareNextPageIfNeed()
        }
        layout.pages.forEach { layoutPage ->
            currentCoroutineContext().ensureActive()
            val backgroundImage = layoutPage.commands
                .filterIsInstance<EpubImageBox>()
                .firstOrNull { it.isBackground }
            val backgroundColor = layoutPage.commands
                .filterIsInstance<EpubPageColor>()
                .firstOrNull()
            pendingTextPage.epubLayoutSnapshotId = layoutPage.snapshotId
            pendingTextPage.epubDrawOffsetX = if (backgroundImage != null) 0f else paddingLeft.toFloat()
            pendingTextPage.epubDrawOffsetY = if (backgroundImage != null) 0f else paddingTop.toFloat()
            layoutPage.commands.forEach { command ->
                if (command is EpubImageBox) {
                    if (command.isBackground) {
                        ImageProvider.cacheImageAsync(
                            book = book,
                            src = command.src,
                            bookSource = ReadBook.bookSource,
                            width = viewWidth,
                            height = viewHeight,
                            cacheKeySuffix = "epub-bg-${viewWidth}x${viewHeight}"
                        ) {
                            ReadBook.invalidateEpubResource(book.bookUrl, bookChapter.index, command.src)
                        }
                    } else {
                        ImageProvider.cacheImage(book, command.src, ReadBook.bookSource)
                    }
                }
            }
            if (backgroundColor != null) {
                pendingTextPage.epubBackgroundColor = backgroundColor.color
            }
            if (backgroundImage != null) {
                pendingTextPage.epubBackgroundSrc = backgroundImage.src
                pendingTextPage.epubBackgroundSize = backgroundImage.backgroundSize
                pendingTextPage.epubBackgroundPosition = backgroundImage.backgroundPosition
                pendingTextPage.epubBackgroundRepeat = backgroundImage.backgroundRepeat
            }
            pendingTextPage.epubNativeCommands.addAll(
                layoutPage.commands.filterNot { command ->
                    command is EpubPageColor || command is EpubImageBox && command.isBackground
                }
            )
            pendingTextPage.height = layoutPage.height.coerceAtLeast(viewHeight.toFloat())
            durY = pendingTextPage.height
            stringBuilder.append(' ')
            prepareNextPageIfNeed()
        }
    }

    /**
     * 排版html样式
     */
    private suspend fun setTypeHtml(
        imageStyle: String?,
        book: Book,
        htmlContent: String,
    ) {
        val htmlBuffer = StringBuilder()
        suspend fun flushHtmlBuffer() {
            if (htmlBuffer.isBlank()) {
                htmlBuffer.setLength(0)
                return
            }
            setTypeHtmlText(imageStyle, book, htmlBuffer.toString())
            htmlBuffer.setLength(0)
        }

        val pageBackground = if (isClassicEpub) {
            RustAnalyzerBridge.htmlPageBackground(
                htmlContent,
                "TextChapterLayout.pageBackground"
            )
        } else {
            null
        }
        if (pageBackground != null) {
            prepareEpubPageBackground(pageBackground, book)
        }
        val renderActions = RustAnalyzerBridge.htmlRenderPlan(
            pageBackground?.html ?: htmlContent,
            isClassicEpub,
            "TextChapterLayout.renderPlan"
        )
        renderActions.forEach { action ->
            currentCoroutineContext().ensureActive()
            when (action.kind) {
                "html" -> htmlBuffer.append(action.html)
                "flush" -> flushHtmlBuffer()
                "pageBreak" -> prepareNextPageIfNeed()
                "pageColor" -> {
                    flushHtmlBuffer()
                    action.pageColor.toEpubTagColor()?.let { color ->
                        if (pendingTextPage.lines.isNotEmpty() || pendingTextPage.hasEpubBackground()) {
                            prepareNextPageIfNeed()
                        }
                        pendingTextPage.epubBackgroundColor = color
                        pendingTextPage.height = viewHeight.toFloat()
                    }
                }
                "spacingBefore" -> {
                    flushHtmlBuffer()
                    addEpubBlockSpacingBefore(action)
                }
                "spacingAfter" -> {
                    flushHtmlBuffer()
                    addEpubBlockSpacingAfter(action)
                }
                "blockBox" -> {
                    flushHtmlBuffer()
                    setTypeEpubBlockBox(imageStyle, book, action.html)
                }
                "htmlText" -> {
                    flushHtmlBuffer()
                    setTypeHtmlText(imageStyle, book, action.html)
                }
                "image" -> {
                    flushHtmlBuffer()
                    setTypeHtmlImage(imageStyle, book, action.image)
                }
                else -> htmlBuffer.append(action.html)
            }
        }
        flushHtmlBuffer()
    }

    private suspend fun prepareEpubPageBackground(pageBackground: RustHtmlPageBackground, book: Book) {
        val pageColor = pageBackground.pageColor.toEpubTagColor()
        val backgroundSrc = pageBackground.backgroundSrc.trim()
        if (pageColor == null && backgroundSrc.isBlank()) return
        if (pendingTextPage.lines.isNotEmpty() || pendingTextPage.hasEpubBackground()) {
            prepareNextPageIfNeed()
        }
        pageColor?.let {
            pendingTextPage.epubBackgroundColor = it
        }
        if (backgroundSrc.isNotBlank()) {
            ImageProvider.cacheImageAsync(
                book = book,
                src = backgroundSrc,
                bookSource = ReadBook.bookSource,
                width = viewWidth,
                height = viewHeight,
                cacheKeySuffix = "epub-bg-${viewWidth}x${viewHeight}"
            ) {
                ReadBook.invalidateEpubResource(book.bookUrl, bookChapter.index, backgroundSrc)
            }
            pendingTextPage.epubBackgroundSrc = backgroundSrc
        }
        pendingTextPage.height = viewHeight.toFloat()
    }

    private suspend fun setTypeEpubBlockBox(
        imageStyle: String?,
        book: Book,
        htmlContent: String
    ) {
        if (!isClassicEpub) {
            setTypeHtmlText(imageStyle, book, htmlContent)
            return
        }
        val style = htmlContent.extractFirstStyleAttribute().epubBlockDecorationStyle() ?: run {
            setTypeHtmlText(imageStyle, book, htmlContent)
            return
        }
        val startPageIndex = textPages.size
        val startLineIndex = pendingTextPage.lines.size
        val layoutOffset = style.marginLeft + style.borderWidth + style.paddingLeft
        val layoutWidth = (visibleWidth - style.marginLeft - style.marginRight -
            style.paddingLeft - style.paddingRight - style.borderWidth * 2)
            .roundToInt()
            .coerceAtLeast((visibleWidth * 0.45f).roundToInt())
        activeEpubBlockDecoration = ActiveEpubBlockDecoration(style, startPageIndex, startLineIndex)
        try {
            setTypeHtmlText(
                imageStyle = imageStyle,
                book = book,
                htmlContent = htmlContent,
                layoutStartOffset = layoutOffset,
                layoutWidth = layoutWidth
            )
        } finally {
            activeEpubBlockDecoration = null
        }
    }

    private fun addEpubBlockDecorations(
        startPageIndex: Int,
        startLineIndex: Int,
        style: EpubBlockDecorationStyle
    ) {
        val lastPageIndex = textPages.size
        for (pageIndex in startPageIndex..lastPageIndex) {
            val page = if (pageIndex < textPages.size) textPages[pageIndex] else pendingTextPage
            val fromLine = if (pageIndex == startPageIndex) startLineIndex else 0
            val targetLines = page.lines.drop(fromLine)
            if (targetLines.isEmpty()) continue
            val top = (targetLines.first().lineTop - style.paddingTop).coerceAtLeast(0f)
            val bottom = (targetLines.last().lineBottom + style.paddingBottom).coerceAtMost(viewHeight.toFloat())
            if (bottom <= top) continue
            page.epubDecorations.add(
                TextPage.EpubDecoration(
                    left = (paddingLeft + style.marginLeft).coerceAtLeast(0f),
                    top = top,
                    right = (paddingLeft + visibleWidth - style.marginRight).coerceAtMost(viewWidth.toFloat()),
                    bottom = bottom,
                    backgroundColor = style.backgroundColor,
                    borderColor = style.borderColor,
                    borderWidth = style.borderWidth,
                    radius = style.radius
                )
            )
            page.invalidate()
        }
    }

    private fun upsertActiveEpubBlockDecoration(page: TextPage, pageIndex: Int) {
        val active = activeEpubBlockDecoration ?: return
        upsertEpubBlockDecoration(
            page = page,
            pageIndex = pageIndex,
            startLineIndex = active.startLineIndex,
            style = active.style
        )
    }

    private fun upsertEpubBlockDecoration(
        page: TextPage,
        pageIndex: Int,
        startLineIndex: Int,
        style: EpubBlockDecorationStyle
    ) {
        val active = activeEpubBlockDecoration
        val fromLine = if (pageIndex == active?.startPageIndex) startLineIndex else 0
        val targetLines = page.lines.drop(fromLine)
        if (targetLines.isEmpty()) return
        val top = (targetLines.first().lineTop - style.paddingTop).coerceAtLeast(0f)
        val bottom = (targetLines.last().lineBottom + style.paddingBottom).coerceAtMost(viewHeight.toFloat())
        if (bottom <= top) return
        val left = (paddingLeft + style.marginLeft).coerceAtLeast(0f)
        val right = (paddingLeft + visibleWidth - style.marginRight).coerceAtMost(viewWidth.toFloat())
        active?.pageDecorations?.remove(pageIndex)?.let { oldDecoration ->
            page.epubDecorations.remove(oldDecoration)
        }
        val decoration = TextPage.EpubDecoration(
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            backgroundColor = style.backgroundColor,
            borderColor = style.borderColor,
            borderWidth = style.borderWidth,
            radius = style.radius
        )
        active?.pageDecorations?.put(pageIndex, decoration)
        page.epubDecorations.add(decoration)
        page.invalidate()
    }

    private suspend fun addEpubBlockSpacingBefore(action: RustHtmlRenderAction) {
        val spacing = action.marginTop.toEpubSpacingPx()
            ?: action.paddingTop.toEpubSpacingPx()
            ?: contentPaintTextHeight
        if (spacing <= 0f) return
        prepareNextPageIfNeed(durY + spacing)
        durY += spacing
        if (pendingTextPage.height < durY) {
            pendingTextPage.height = durY
        }
    }

    private suspend fun addEpubBlockSpacingAfter(action: RustHtmlRenderAction) {
        val spacing = action.marginBottom.toEpubSpacingPx()
            ?: action.paddingBottom.toEpubSpacingPx()
            ?: contentPaintTextHeight
        if (spacing <= 0f) return
        prepareNextPageIfNeed(durY + spacing)
        durY += spacing
        if (pendingTextPage.height < durY) {
            pendingTextPage.height = durY
        }
    }

    private fun String.toEpubSpacingPx(): Float? {
        val value = trim().lowercase()
        if (value.isBlank() || value == "0") return 0f
        return when {
            value.endsWith("%") -> {
                val percentage = value.dropLast(1).toFloatOrNull() ?: return null
                visibleWidth * percentage / 100f
            }
            value.endsWith("em") -> {
                val em = value.dropLast(2).toFloatOrNull() ?: return null
                contentPaintTextHeight * em
            }
            value.endsWith("rem") -> {
                val rem = value.dropLast(3).toFloatOrNull() ?: return null
                contentPaintTextHeight * rem
            }
            value.endsWith("px") -> value.dropLast(2).toFloatOrNull()
            else -> value.toFloatOrNull()
        }
    }

    private fun String.toEpubHorizontalPx(): Float? {
        val value = trim().lowercase()
        if (value.isBlank() || value == "0" || value == "auto") return 0f
        return when {
            value.endsWith("%") -> {
                val percentage = value.dropLast(1).toFloatOrNull() ?: return null
                visibleWidth * percentage / 100f
            }
            value.endsWith("em") -> {
                val em = value.dropLast(2).toFloatOrNull() ?: return null
                contentPaintTextHeight * em
            }
            value.endsWith("rem") -> {
                val rem = value.dropLast(3).toFloatOrNull() ?: return null
                contentPaintTextHeight * rem
            }
            value.endsWith("px") -> value.dropLast(2).toFloatOrNull()
            else -> value.toFloatOrNull()
        }
    }

    private fun String.epubBlockDecorationStyle(): EpubBlockDecorationStyle? {
        val declarations = if (isBlank()) emptyMap() else EpubCss.declarations(this)
        val backgroundColor = declarations["background-color"]?.toEpubCssColor()
            ?: declarations["background"]?.extractCssColor()?.toEpubCssColor()
        val borderColor = declarations["border-color"]?.toEpubCssColor()
            ?: declarations["border"]?.extractCssColor()?.toEpubCssColor()
            ?: declarations["border-top-color"]?.toEpubCssColor()
            ?: declarations["border-right-color"]?.toEpubCssColor()
            ?: declarations["border-bottom-color"]?.toEpubCssColor()
            ?: declarations["border-left-color"]?.toEpubCssColor()
            ?: declarations["border-top"]?.extractCssColor()?.toEpubCssColor()
            ?: declarations["border-right"]?.extractCssColor()?.toEpubCssColor()
            ?: declarations["border-bottom"]?.extractCssColor()?.toEpubCssColor()
            ?: declarations["border-left"]?.extractCssColor()?.toEpubCssColor()
        if (backgroundColor == null && borderColor == null) return null
        val padding = declarations["padding"]?.toEpubBoxLengths().orEmpty()
        val margin = declarations["margin"]?.toEpubBoxLengths().orEmpty()
        val paddingTop = declarations["padding-top"]?.toEpubSpacingPx()
            ?: padding.getOrNull(0)?.toEpubSpacingPx()
            ?: (contentPaintTextHeight * 0.45f)
        val paddingRight = declarations["padding-right"]?.toEpubHorizontalPx()
            ?: padding.getOrNull(1)?.toEpubHorizontalPx()
            ?: padding.getOrNull(0)?.toEpubHorizontalPx()
            ?: 0f
        val paddingBottom = declarations["padding-bottom"]?.toEpubSpacingPx()
            ?: padding.getOrNull(2)?.toEpubSpacingPx()
            ?: padding.getOrNull(0)?.toEpubSpacingPx()
            ?: (contentPaintTextHeight * 0.45f)
        val paddingLeft = declarations["padding-left"]?.toEpubHorizontalPx()
            ?: padding.getOrNull(3)?.toEpubHorizontalPx()
            ?: padding.getOrNull(1)?.toEpubHorizontalPx()
            ?: padding.getOrNull(0)?.toEpubHorizontalPx()
            ?: 0f
        val marginLeft = declarations["margin-left"]?.toEpubHorizontalPx()
            ?: margin.getOrNull(3)?.toEpubHorizontalPx()
            ?: margin.getOrNull(1)?.toEpubHorizontalPx()
            ?: 0f
        val marginRight = declarations["margin-right"]?.toEpubHorizontalPx()
            ?: margin.getOrNull(1)?.toEpubHorizontalPx()
            ?: 0f
        val radius = declarations["border-radius"]?.let { EpubCss.splitValueList(it).firstOrNull() ?: it }
            ?.toEpubHorizontalPx() ?: 0f
        val borderWidth = declarations["border-width"]?.toEpubHorizontalPx()
            ?: declarations["border"]?.extractCssLength()?.toEpubHorizontalPx()
            ?: declarations["border-top-width"]?.toEpubHorizontalPx()
            ?: declarations["border-right-width"]?.toEpubHorizontalPx()
            ?: declarations["border-bottom-width"]?.toEpubHorizontalPx()
            ?: declarations["border-left-width"]?.toEpubHorizontalPx()
            ?: declarations["border-top"]?.extractCssLength()?.toEpubHorizontalPx()
            ?: declarations["border-right"]?.extractCssLength()?.toEpubHorizontalPx()
            ?: declarations["border-bottom"]?.extractCssLength()?.toEpubHorizontalPx()
            ?: declarations["border-left"]?.extractCssLength()?.toEpubHorizontalPx()
            ?: 1.dpToPx().toFloat()
        return EpubBlockDecorationStyle(
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            borderWidth = borderWidth,
            radius = radius,
            marginLeft = marginLeft,
            marginRight = marginRight,
            paddingTop = paddingTop,
            paddingBottom = paddingBottom,
            paddingLeft = paddingLeft,
            paddingRight = paddingRight
        )
    }

    private fun String.toEpubBoxLengths(): List<String> {
        val parts = trim().split(' ', '\t', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return when (parts.size) {
            0 -> emptyList()
            1 -> listOf(parts[0], parts[0], parts[0], parts[0])
            2 -> listOf(parts[0], parts[1], parts[0], parts[1])
            3 -> listOf(parts[0], parts[1], parts[2], parts[1])
            else -> parts.take(4)
        }
    }

    private fun String.extractFirstStyleAttribute(): String {
        val match = Regex("""(?is)\sstyle\s*=\s*(['"])(.*?)\1""").find(this) ?: return ""
        return match.groupValues.getOrNull(2).orEmpty()
    }

    private fun String.extractCssColor(): String? {
        val clean = trim()
        if (clean.startsWith("#") || clean.startsWith("rgb", true)) return clean
        val parts = clean.split(' ', ',', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return parts.firstOrNull { part ->
            part.startsWith("#") || part.startsWith("rgb", true) || part.toNamedCssColor() != null
        }
    }

    private fun String.extractCssLength(): String? {
        return trim().split(' ', '\t', '\n')
            .map { it.trim() }
            .firstOrNull { value ->
                value.endsWith("px", true) || value.endsWith("em", true) ||
                    value.endsWith("rem", true) || value.toFloatOrNull() != null
            }
    }

    private fun String.toEpubCssColor(): Int? {
        val clean = trim().trimMatchingQuote()
        return when {
            clean.startsWith("rgba", true) || clean.startsWith("rgb", true) -> clean.parseRgbCssColor()
            clean.startsWith("#") -> runCatching { Color.parseColor(clean.normalizeHexColor()) }.getOrNull()
            else -> clean.toNamedCssColor()?.let { runCatching { Color.parseColor(it) }.getOrNull() }
        }
    }

    private fun String.toEpubTagColor(): Int? {
        val clean = trim().takeIf { it.length == 6 || it.length == 8 } ?: return null
        return runCatching { Color.parseColor("#$clean") }.getOrNull()
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

    private fun String.normalizeHexColor(): String {
        val hex = trim().removePrefix("#")
        return when (hex.length) {
            3 -> "#" + hex.map { "$it$it" }.joinToString("")
            4 -> "#" + hex.map { "$it$it" }.joinToString("")
            else -> "#$hex"
        }
    }

    private fun String.parseRgbCssColor(): Int? {
        val start = indexOf('(')
        val end = lastIndexOf(')')
        if (start < 0 || end <= start) return null
        val parts = substring(start + 1, end)
            .split(',', ' ', '/')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (parts.size < 3) return null
        fun component(value: String): Int {
            return if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 0f) * 2.55f).toInt()
            } else {
                value.toFloatOrNull()?.toInt() ?: 0
            }.coerceIn(0, 255)
        }
        val alpha = parts.getOrNull(3)?.let { value ->
            if (value.endsWith("%")) {
                ((value.dropLast(1).toFloatOrNull() ?: 100f) * 2.55f).toInt()
            } else {
                ((value.toFloatOrNull() ?: 1f) * 255f).toInt()
            }
        } ?: 255
        return Color.argb(alpha.coerceIn(0, 255), component(parts[0]), component(parts[1]), component(parts[2]))
    }

    private fun String.toNamedCssColor(): String? {
        return when (lowercase()) {
            "black" -> "#000000"
            "white" -> "#FFFFFF"
            "red" -> "#FF0000"
            "green" -> "#008000"
            "blue" -> "#0000FF"
            "cyan", "aqua" -> "#00FFFF"
            "magenta", "fuchsia" -> "#FF00FF"
            "yellow" -> "#FFFF00"
            "gray", "grey" -> "#808080"
            "silver" -> "#C0C0C0"
            "maroon" -> "#800000"
            "purple" -> "#800080"
            "teal" -> "#008080"
            "navy" -> "#000080"
            "orange" -> "#FFA500"
            "transparent" -> "#00000000"
            else -> null
        }
    }

    private data class EpubBlockDecorationStyle(
        val backgroundColor: Int?,
        val borderColor: Int?,
        val borderWidth: Float,
        val radius: Float,
        val marginLeft: Float,
        val marginRight: Float,
        val paddingTop: Float,
        val paddingBottom: Float,
        val paddingLeft: Float,
        val paddingRight: Float
    )

    private data class ActiveEpubBlockDecoration(
        val style: EpubBlockDecorationStyle,
        val startPageIndex: Int,
        val startLineIndex: Int,
        val pageDecorations: MutableMap<Int, TextPage.EpubDecoration> = linkedMapOf()
    )

    private suspend fun setTypeHtmlImage(
        imageStyle: String?,
        book: Book,
        imageInfo: RustHtmlImageInfo
    ) {
        val src = imageInfo.src.trim()
        if (src.isBlank()) return
        if (isClassicEpub && imageInfo.isBackground) {
            ImageProvider.cacheImage(book, src, ReadBook.bookSource)
            if (pendingTextPage.lines.isNotEmpty() || pendingTextPage.epubBackgroundSrc != null) {
                prepareNextPageIfNeed()
            }
            pendingTextPage.epubBackgroundSrc = src
            if (pendingTextPage.height < viewHeight) {
                pendingTextPage.height = viewHeight.toFloat()
            }
            return
        }
        var style = imageInfo.style.ifBlank { null }
        val width = imageInfo.width
        val click = imageInfo.click.ifBlank { null }
        var imgSize = ImageProvider.getImageSize(book, src, ReadBook.bookSource)
        imgSize = imgSize.applyWidth(width)
        if (style == null) {
            style = if (imgSize.width < 80 && imgSize.height < 80) {
                "text"
            } else {
                imageStyle
            }
        }
        setTypeImage(
            book,
            src,
            contentPaintTextHeight,
            style,
            imgSize,
            click
        )
    }

    private fun Size.applyWidth(width: String): Size {
        if (this.width <= 0 || this.height <= 0 || width.isBlank()) return this
        val clean = width.trim().lowercase()
        val newWidth = when {
            clean.endsWith("%") -> {
                val percentage = clean.dropLast(1).toFloatOrNull() ?: return this
                (visibleWidth * percentage / 100f).roundToInt()
            }
            clean.endsWith("em") -> {
                val em = clean.dropLast(2).toFloatOrNull() ?: return this
                (contentPaintTextHeight * em).roundToInt()
            }
            clean.endsWith("rem") -> {
                val rem = clean.dropLast(3).toFloatOrNull() ?: return this
                (contentPaintTextHeight * rem).roundToInt()
            }
            clean.endsWith("px") -> clean.dropLast(2).substringBefore(".").toIntOrNull() ?: return this
            else -> clean.substringBefore(".").toIntOrNull() ?: return this
        }.coerceAtLeast(1)
        val newHeight = (height * newWidth.toFloat() / this.width).roundToInt().coerceAtLeast(1)
        return Size(newWidth, newHeight)
    }

    private suspend fun setTypeHtmlText(
        imageStyle: String?,
        book: Book,
        htmlContent: String,
        layoutStartOffset: Float = 0f,
        layoutWidth: Int = visibleWidth
    ) {
        breakAfterSingleImageIfNeed()
        val textViewTagHandler = TextViewTagHandler()
        val spanned = htmlContent.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT, tagHandler = textViewTagHandler)
        val width = layoutWidth.coerceIn(1, visibleWidth)
        val lineAbsStartX = absStartX + layoutStartOffset
        val textPaint = contentPaint
        val textColor = ReadBookConfig.textColor
        if (textPaint.color != textColor) {
            textPaint.color = textColor
        }
        val alignment = htmlContent.epubResourceAlignment()
        val staticLayout = if (atLeastApi28) {
            StaticLayout.Builder.obtain(spanned, 0, spanned.length, textPaint, width)
                .setAlignment(alignment)
                .setIncludePad(true)
                .setUseLineSpacingFromFallbacks(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                spanned,
                textPaint,
                width,
                alignment,
                1f,
                0f,
                true
            )
        }
        val tempPaint = TextPaint(textPaint)
        for (lineIndex in 0 until staticLayout.lineCount) {
            val lineStart = staticLayout.getLineStart(lineIndex)
            val lineEnd = staticLayout.getLineEnd(lineIndex)
            if (lineStart == lineEnd) { //这一行没有内容，跳过
                continue
            }
            val textLine = TextLine(isHtml = true)
            val lineText = StringBuilder()
            val lineLeft = staticLayout.getLineLeft(lineIndex)
            textLine.startX = lineAbsStartX + lineLeft //x坐标
            val mLineTop = staticLayout.getLineTop(lineIndex).toFloat()
            val mLineBottom = staticLayout.getLineBottom(lineIndex).toFloat()
            val lineHeight = mLineBottom - mLineTop
            prepareNextPageIfNeed(durY + lineHeight)
            textLine.upTopBottom(durY, lineHeight, textPaint.fontMetrics) //y坐标

            val columns = mutableListOf<BaseColumn>()
            var charIndex = lineStart
            while (charIndex < lineEnd) {
                val char = spanned[charIndex].toString()
                lineText.append(char)
                if (char == "\n") {
                    textLine.isParagraphEnd = true
                    durY += lineHeight * paragraphSpacing / 10f //段距
                    charIndex++
                    continue
                }
                val charX = staticLayout.getPrimaryHorizontal(charIndex)
                val textSize = extractTextSize(spanned, charIndex, textPaint.textSize)
                val textColor = extractTextColor(spanned, charIndex)
                val linkUrl = extractLinkUrl(spanned, charIndex)
                val charRight = if (charIndex + 1 < lineEnd) {
                    staticLayout.getPrimaryHorizontal(charIndex + 1)
                } else {
                    tempPaint.textSize = textSize
                    val charWidth = tempPaint.measureText(char)
                    charX + charWidth
                }
                var needAddText = true
                spanned.getSpans(charIndex, charIndex + 1, ImageSpan::class.java).firstOrNull()?.let { span -> //处理图片
                    val source = span.source ?: return@let
                    val urlMatcher = UrlOptions.paramPattern.matcher(source)
                    if (urlMatcher.find()) {
                        val urlOptionStr = source.substring(urlMatcher.end())
                        val urlOption = UrlOptions.parseStringMap(urlOptionStr, "TextChapterLayout.imageSpan")
                        var iStyle = urlOption["style"]
                        val width = urlOption["width"]
                        val click = urlOption["click"]
                        var imgSize = ImageProvider.getImageSize(book, source, ReadBook.bookSource)
                        width?.let {
                            imgSize = imgSize.applyWidth(it)
                        }
                        if (iStyle == null) {
                            iStyle = if (imgSize.width < 80 && imgSize.height < 80) {
                                "text"
                            } else {
                                imageStyle
                            }
                        }
                        when (iStyle?.uppercase()) {
                            "TEXT" -> {
                                ImageProvider.cacheImage(book, source, ReadBook.bookSource)
                                columns.add(
                                    ImageColumn(
                                        start = lineAbsStartX + charX,
                                        end = lineAbsStartX + charRight,
                                        src = source,
                                        click = click
                                    )
                                )
                            }
                            else -> {
                                setTypeImage(
                                    book,
                                    source,
                                    contentPaintTextHeight,
                                    iStyle,
                                    imgSize,
                                    click
                                )
                            }
                        }
                    } else {
                        val imgSize = ImageProvider.getImageSize(book, source, ReadBook.bookSource)
                        setTypeImage(
                            book,
                            source,
                            contentPaintTextHeight,
                            imageStyle,
                            imgSize,
                            null
                        )
                    }
                    needAddText = false
                }
                spanned.getSpans(charIndex, charIndex + 1, ReplacementSpan::class.java).firstOrNull()?.let { _ -> //自定义标签
                    if (char == HR_PLACE_CHAR) {
                        columns.add(
                            TextHtmlColumn(
                                lineAbsStartX,
                                lineAbsStartX + width,
                                HR_PLACE_STR,
                                textSize,
                                textColor,
                                linkUrl,
                                isBold = spanned.hasStyleSpan(charIndex, Typeface.BOLD),
                                isItalic = spanned.hasStyleSpan(charIndex, Typeface.ITALIC),
                                isUnderline = spanned.hasSpan(charIndex, UnderlineSpan::class.java),
                                isStrikethrough = spanned.hasSpan(charIndex, StrikethroughSpan::class.java),
                                backgroundColor = extractBackgroundColor(spanned, charIndex)
                            )
                        )
                        needAddText = false
                    }
                }
                if (needAddText) {
                    columns.add(
                        TextHtmlColumn(
                            lineAbsStartX + charX,
                            lineAbsStartX + charRight,
                            char,
                            textSize,
                            textColor,
                            linkUrl,
                            isBold = spanned.hasStyleSpan(charIndex, Typeface.BOLD),
                            isItalic = spanned.hasStyleSpan(charIndex, Typeface.ITALIC),
                            isUnderline = spanned.hasSpan(charIndex, UnderlineSpan::class.java),
                            isStrikethrough = spanned.hasSpan(charIndex, StrikethroughSpan::class.java),
                            backgroundColor = extractBackgroundColor(spanned, charIndex)
                        )
                    )
                }
                charIndex++
                if (charIndex == lineEnd && lineIndex == staticLayout.lineCount - 1) {
                    textLine.isParagraphEnd = true
                    durY += lineHeight * paragraphSpacing / 10f //段距
                }
            }
            textLine.text = lineText.toString()
            if (textFullJustify && !textLine.isParagraphEnd) {
                justifyHtmlLine(columns, textLine, width)
            } else {
                textLine.addColumns(columns)
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            val textPage = pendingTextPage
            textPage.addLine(textLine)
            upsertActiveEpubBlockDecoration(textPage, textPages.size)
            durY += lineHeight * lineSpacingExtra //行距
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
    }

    /**
     * 对HTML行进行两端对齐
     */
    private fun justifyHtmlLine(
        columns: MutableList<BaseColumn>,
        textLine: TextLine,
        lineWidth: Int
    ) {
        if (columns.isEmpty()) return
        // 计算当前行的总宽度
        val firstCol = columns.first()
        val lastCol = columns.last()
        val currentWidth = lastCol.end - firstCol.start
        // 计算剩余空间
        val residualWidth = lineWidth - currentWidth

        if (residualWidth <= 0) {
            textLine.addColumns(columns)
            return
        }

        // 统计空格数量
        val spaceCount = columns.count {
            (it as? TextBaseColumn)?.charData == " "
        }

        if (spaceCount > 1) {
            // 多个空格：调整空格间距
            val spaceIncrement = residualWidth / spaceCount
            textLine.wordSpacing = spaceIncrement

            // 重新计算字符位置
            var currentX = firstCol.start
            for (i in columns.indices) {
                val col = columns[i]
                val width = col.end - col.start

                if ((col as? TextBaseColumn)?.charData == " " && i != columns.lastIndex) {
                    // 空格，增加额外的间距
                    col.start = currentX
                    col.end = currentX + width + spaceIncrement
                    currentX = col.end
                } else {
                    // 非空格或最后一个字符
                    col.start = currentX
                    col.end = currentX + width
                    currentX = col.end
                }

                textLine.addColumn(col)
            }
        } else {
            // 没有或只有一个空格：调整字符间距
            val gapCount = columns.lastIndex
            if (gapCount > 0) {
                val charIncrement = residualWidth / gapCount
                var currentX = firstCol.start
                for (i in columns.indices) {
                    val col = columns[i]
                    val width = col.end - col.start

                    if (i != columns.lastIndex) {
                        // 非最后一个字符，增加额外的间距
                        col.start = currentX
                        col.end = currentX + width + charIncrement
                        currentX = col.end
                    } else {
                        // 最后一个字符，不增加额外间距
                        col.start = currentX
                        col.end = currentX + width
                    }

                    textLine.addColumn(col)
                }
            } else {
                // 只有一个字符，不需要调整
                textLine.addColumns(columns)
            }
        }
    }

    private fun extractTextSize(spanned: Spanned, index: Int, defaultSize: Float): Float {
        val relativeSpans = spanned.getSpans(index, index + 1, RelativeSizeSpan::class.java)
        // 如果有 RelativeSizeSpan，基于基准大小计算
        relativeSpans.firstOrNull()?.let { span ->
            return defaultSize * span.sizeChange
        }
        val sizeSpans = spanned.getSpans(index, index + 1, AbsoluteSizeSpan::class.java)
        sizeSpans.firstOrNull()?.let { span ->
            return if (span.dip) span.size.toFloat().dpToPx() else span.size.toFloat()
        }
        return defaultSize
    }

    private fun String.epubResourceAlignment(): Layout.Alignment {
        return when (RustAnalyzerBridge.htmlFirstAlignment(this, "TextChapterLayout.epubResourceAlignment")) {
            "center" -> Layout.Alignment.ALIGN_CENTER
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
    }

    private fun extractTextColor(spanned: Spanned, index: Int): Int? {
        val foregroundSpans = spanned.getSpans(index, index + 1, ForegroundColorSpan::class.java)
        return foregroundSpans.minByOrNull { span ->
            spanned.getSpanEnd(span) - spanned.getSpanStart(span)
        }?.foregroundColor
    }

    private fun extractBackgroundColor(spanned: Spanned, index: Int): Int? {
        val backgroundSpans = spanned.getSpans(index, index + 1, BackgroundColorSpan::class.java)
        return backgroundSpans.minByOrNull { span ->
            spanned.getSpanEnd(span) - spanned.getSpanStart(span)
        }?.backgroundColor
    }

    private fun <T> Spanned.hasSpan(index: Int, clazz: Class<T>): Boolean {
        return getSpans(index, index + 1, clazz).isNotEmpty()
    }

    private fun Spanned.hasStyleSpan(index: Int, style: Int): Boolean {
        return getSpans(index, index + 1, StyleSpan::class.java).any { span ->
            span.style == style || span.style == Typeface.BOLD_ITALIC
        }
    }

    private fun extractLinkUrl(spanned: Spanned, index: Int): String? {
        // 检查URLSpan（超链接）
        val urlSpans = spanned.getSpans(index, index + 1, URLSpan::class.java)
        urlSpans.firstOrNull()?.let { span ->
            return span.url
        }
        return null
    }


    /**
     * 排版文字
     */
    @Suppress("DEPRECATION")
    private suspend fun setTypeText(
        book: Book,
        text: String,
        textPaint: TextPaint,
        textHeight: Float,
        fontMetrics: Paint.FontMetrics,
        imageStyle: String?,
        isTitle: Boolean = false,
        isFirstLine: Boolean = true,
        emptyContent: Boolean = false,
        isVolumeTitle: Boolean = false,
        forceMiddleTitle: Boolean = false,
        srcList: LinkedList<String>? = null,
        clickList: LinkedList<String?>?
    ) {
        breakAfterSingleImageIfNeed()
        val styledText = parseEpubReadableInlineStyles(text)
        val plainText = styledText.text
        if (plainText.isBlank()) return
        val widthsArray = allocateFloatArray(plainText.length)
        textPaint.getTextWidthsCompat(plainText, widthsArray, reviewCharWidth)
        val layout = if (useZhLayout) {
            val measuredWords = measureTextSplit(plainText, widthsArray)
            val indentSize = if (isFirstLine) paragraphIndent.length else 0
            ZhLayout(
                plainText,
                textPaint,
                visibleWidth,
                measuredWords.words,
                measuredWords.widths,
                indentSize
            )
        } else {
            StaticLayout(plainText, textPaint, visibleWidth, Layout.Alignment.ALIGN_NORMAL, 0f, 0f, true)
        }
        durY = when {
            //标题y轴居中
            emptyContent && textPages.isEmpty() -> {
                val textPage = pendingTextPage
                if (textPage.lineSize == 0) {
                    val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                    if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                } else {
                    var textLayoutHeight = layout.lineCount * textHeight
                    val fistLine = textPage.getLine(0)
                    if (fistLine.lineTop < textLayoutHeight + titleTopSpacing) {
                        textLayoutHeight = fistLine.lineTop - titleTopSpacing
                    }
                    textPage.lines.forEach {
                        it.lineTop -= textLayoutHeight
                        it.lineBase -= textLayoutHeight
                        it.lineBottom -= textLayoutHeight
                    }
                    durY - textLayoutHeight
                }
            }

            isTitle && textPages.isEmpty() && pendingTextPage.lines.isEmpty() -> {
                when (imageStyle?.uppercase()) {
                    Book.imgStyleSingle -> {
                        val ty = (visibleHeight - layout.lineCount * textHeight) / 2
                        if (ty > titleTopSpacing) ty else titleTopSpacing.toFloat()
                    }

                    else -> durY + titleTopSpacing
                }
            }

            else -> durY
        }
        for (lineIndex in 0 until layout.lineCount) {
            val textLine = TextLine(isTitle = isTitle)
            prepareNextPageIfNeed(durY + textHeight)
            val lineStart = layout.getLineStart(lineIndex)
            val lineEnd = layout.getLineEnd(lineIndex)
            val lineText = plainText.substring(lineStart, lineEnd)
            val measuredWords = measureTextSplit(lineText, widthsArray, lineStart)
            val words = measuredWords.words
            val widths = measuredWords.widths
            val styles = measuredWords.offsets.map { offset ->
                styledText.styleAt(lineStart + offset)
            }
            val desiredWidth = widths.fastSum()
            textLine.text = lineText
            when (lineIndex) {
                0 if layout.lineCount > 1 && !isTitle && isFirstLine -> {
                    //多行的第一行 非标题
                    addCharsToLineFirst(
                        book, absStartX, textLine, words, textPaint,
                        desiredWidth, widths, styles, srcList, clickList
                    )
                }
                layout.lineCount - 1 -> {
                    //最后一行、单行
                    //标题x轴居中
                    val startX = if (
                        isTitle &&
                        (isMiddleTitle || forceMiddleTitle || emptyContent || isVolumeTitle
                                || imageStyle?.uppercase() == Book.imgStyleSingle)
                    ) {
                        (visibleWidth - desiredWidth) / 2
                    } else {
                        0f
                    }
                    addCharsToLineNatural(
                        book, absStartX, textLine, words,
                        startX, !isTitle && lineIndex == 0, widths, styles, srcList, clickList
                    )
                }
                else -> {
                    if (
                        isTitle &&
                        (isMiddleTitle || forceMiddleTitle || emptyContent || isVolumeTitle
                                || imageStyle?.uppercase() == Book.imgStyleSingle)
                    ) {
                        //标题居中
                        val startX = (visibleWidth - desiredWidth) / 2
                        addCharsToLineNatural(
                            book, absStartX, textLine, words,
                            startX, false, widths, styles, srcList, clickList
                        )
                    } else {
                        //中间行
                        addCharsToLineMiddle(
                            book, absStartX, textLine, words, textPaint,
                            desiredWidth, 0f, widths, styles, srcList, clickList
                        )
                    }
                }
            }
            if (doublePage) {
                textLine.isLeftLine = absStartX < viewWidth / 2
            }
            calcTextLinePosition(textPages, textLine, stringBuilder.length)
            stringBuilder.append(lineText)
            textLine.upTopBottom(durY, textHeight, fontMetrics)
            val textPage = pendingTextPage
            textPage.addLine(textLine)
            durY += textHeight * lineSpacingExtra
            if (textPage.height < durY) {
                textPage.height = durY
            }
        }
        durY += textHeight * paragraphSpacing / 10f
    }

    private fun parseEpubReadableInlineStyles(rawText: String): ParsedInlineText {
        if (
            !rawText.contains(EpubFile.READABLE_CONTENT_VERSION_FLAG) &&
            !rawText.contains(EpubFile.INLINE_STYLE_MARK)
        ) {
            return ParsedInlineText(rawText, List(rawText.length) { null })
        }
        val text = StringBuilder(rawText.length)
        val styles = ArrayList<InlineColumnStyle?>(rawText.length)
        var underlineCount = 0
        var boldCount = 0
        var italicCount = 0
        var strikeCount = 0
        var superCount = 0
        var subCount = 0
        var index = 0

        fun currentStyle(): InlineColumnStyle? {
            val script = when {
                superCount > 0 -> 1
                subCount > 0 -> -1
                else -> 0
            }
            val style = InlineColumnStyle(
                underline = underlineCount > 0,
                bold = boldCount > 0,
                italic = italicCount > 0,
                strike = strikeCount > 0,
                textSizeScale = if (script == 0) 1f else 0.72f,
                baselineShiftEm = when {
                    script > 0 -> -0.35f
                    script < 0 -> 0.2f
                    else -> 0f
                }
            )
            return style.takeIf { it.hasStyle }
        }

        while (index < rawText.length) {
            if (rawText.startsWith(EpubFile.READABLE_CONTENT_VERSION_FLAG, index)) {
                index += EpubFile.READABLE_CONTENT_VERSION_FLAG.length
                continue
            }
            if (rawText[index] == EpubFile.INLINE_STYLE_MARK && index + 1 < rawText.length) {
                when (rawText[index + 1]) {
                    'U' -> {
                        underlineCount++
                        index += 2
                        continue
                    }
                    'u' -> {
                        underlineCount = (underlineCount - 1).coerceAtLeast(0)
                        index += 2
                        continue
                    }
                    'B' -> {
                        boldCount++
                        index += 2
                        continue
                    }
                    'b' -> {
                        boldCount = (boldCount - 1).coerceAtLeast(0)
                        index += 2
                        continue
                    }
                    'I' -> {
                        italicCount++
                        index += 2
                        continue
                    }
                    'i' -> {
                        italicCount = (italicCount - 1).coerceAtLeast(0)
                        index += 2
                        continue
                    }
                    'S' -> {
                        strikeCount++
                        index += 2
                        continue
                    }
                    's' -> {
                        strikeCount = (strikeCount - 1).coerceAtLeast(0)
                        index += 2
                        continue
                    }
                    'P' -> {
                        superCount++
                        index += 2
                        continue
                    }
                    'p' -> {
                        superCount = (superCount - 1).coerceAtLeast(0)
                        index += 2
                        continue
                    }
                    'D' -> {
                        subCount++
                        index += 2
                        continue
                    }
                    'd' -> {
                        subCount = (subCount - 1).coerceAtLeast(0)
                        index += 2
                        continue
                    }
                    'L' -> {
                        val lenStart = index + 2
                        val lenEnd = lenStart + 4
                        val length = if (lenEnd <= rawText.length) {
                            rawText.substring(lenStart, lenEnd).toIntOrNull(16)
                        } else {
                            null
                        }
                        if (length != null && lenEnd + length <= rawText.length) {
                            index = lenEnd + length
                            continue
                        }
                    }
                    'l' -> {
                        index += 2
                        continue
                    }
                }
            }
            text.append(rawText[index])
            styles.add(currentStyle())
            index++
        }
        return ParsedInlineText(text.toString(), styles)
    }

    private fun calcTextLinePosition(
        textPages: ArrayList<TextPage>,
        textLine: TextLine,
        sbLength: Int
    ) {
        val lastLine = pendingTextPage.lines.lastOrNull { it.paragraphNum > 0 }
            ?: textPages.lastOrNull()?.lines?.lastOrNull { it.paragraphNum > 0 }
        val paragraphNum = when {
            lastLine == null -> 1
            lastLine.isParagraphEnd -> lastLine.paragraphNum + 1
            else -> lastLine.paragraphNum
        }
        textLine.paragraphNum = paragraphNum
        val previousPageEndPosition = textPages.lastOrNull()?.let { lastPage ->
            lastPage.lines.lastOrNull()?.run {
                chapterPosition + charSize + if (isParagraphEnd) 1 else 0
            } ?: (lastPage.chapterPosition + lastPage.charSize)
        } ?: 0
        textLine.chapterPosition = previousPageEndPosition + sbLength
        textLine.pagePosition = sbLength
    }

    /**
     * 有缩进,两端对齐
     */
    private suspend fun addCharsToLineFirst(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        /**自然排版长度**/
        desiredWidth: Float,
        textWidths: List<Float>,
        textStyles: List<InlineColumnStyle?>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?
    ) {
        var x = 0f
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                x, true, textWidths, textStyles, srcList, clickList
            )
            return
        }
        val bodyIndent = paragraphIndent
        repeat(bodyIndent.length) {
            val x1 = x + indentCharWidth
            textLine.addColumn(
                TextColumn(
                    charData = ChapterProvider.indentChar,
                    start = absStartX + x,
                    end = absStartX + x1
                )
            )
            x = x1
            textLine.indentWidth = x
        }
        textLine.indentSize = bodyIndent.length
        if (words.size > bodyIndent.length) {
            val text1 = words.subList(bodyIndent.length, words.size)
            val textWidths1 = textWidths.subList(bodyIndent.length, textWidths.size)
            val textStyles1 = textStyles.subList(bodyIndent.length, textStyles.size)
            addCharsToLineMiddle(
                book, absStartX, textLine, text1, textPaint,
                desiredWidth, x, textWidths1, textStyles1, srcList, clickList
            )
        }
    }

    /**
     * 无缩进,两端对齐
     */
    private suspend fun addCharsToLineMiddle(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        textPaint: TextPaint,
        /**自然排版长度**/
        desiredWidth: Float,
        /**起始x坐标**/
        startX: Float,
        textWidths: List<Float>,
        textStyles: List<InlineColumnStyle?>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?
    ) {
        if (!textFullJustify) {
            addCharsToLineNatural(
                book, absStartX, textLine, words,
                startX, false, textWidths, textStyles, srcList,
                clickList
            )
            return
        }
        val residualWidth = visibleWidth - desiredWidth
        val spaceSize = words.count { it == " " }
        textLine.startX = absStartX + startX
        if (spaceSize > 1) {
            val d = residualWidth / spaceSize
            textLine.wordSpacing = d
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (char == " ") {
                    if (index != words.lastIndex) (x + cw + d) else (x + cw)
                } else {
                    (x + cw)
                }
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList,
                    clickList, textStyles.getOrNull(index)
                )
                x = x1
            }
        } else {
            val gapCount: Int = words.lastIndex
            val d = if (gapCount > 0) residualWidth / gapCount else 0f
            textLine.extraLetterSpacingOffsetX = -d / 2
            textLine.extraLetterSpacing = d / textPaint.textSize
            var x = startX
            for (index in words.indices) {
                val char = words[index]
                val cw = textWidths[index]
                val x1 = if (index != words.lastIndex) (x + cw + d) else (x + cw)
                addCharToLine(
                    book, absStartX, textLine, char,
                    x, x1, index + 1 == words.size, srcList,
                    clickList, textStyles.getOrNull(index)
                )
                x = x1
            }
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 自然排列
     */
    private suspend fun addCharsToLineNatural(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        words: List<String>,
        startX: Float,
        hasIndent: Boolean,
        textWidths: List<Float>,
        textStyles: List<InlineColumnStyle?>,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?
    ) {
        val indentLength = paragraphIndent.length
        var x = startX
        textLine.startX = absStartX + startX
        for (index in words.indices) {
            val char = words[index]
            val cw = textWidths[index]
            val x1 = x + cw
            addCharToLine(
                book, absStartX, textLine, char, x, x1, index + 1 == words.size,
                srcList, clickList, textStyles.getOrNull(index)
            )
            x = x1
            if (hasIndent && index == indentLength - 1) {
                textLine.indentWidth = x
            }
        }
        exceed(absStartX, textLine, words)
    }

    /**
     * 添加字符
     */
    private suspend fun addCharToLine(
        book: Book,
        absStartX: Int,
        textLine: TextLine,
        char: String,
        xStart: Float,
        xEnd: Float,
        isLineEnd: Boolean,
        srcList: LinkedList<String>?,
        clickList: LinkedList<String?>?,
        style: InlineColumnStyle? = null
    ) {
        val column = when {
            !srcList.isNullOrEmpty() && (char == srcReplaceStr || char == reviewStr) -> {
                val src = srcList.removeFirst()
                val click = clickList?.removeFirst()
                ImageProvider.cacheImage(book, src, ReadBook.bookSource)
                ImageColumn(
                    start = absStartX + xStart,
                    end = absStartX + xEnd,
                    src = src,
                    click = click
                )
            }
//            isLineEnd && char == ChapterProvider.reviewChar -> {
//                ReviewColumn(
//                    start = absStartX + xStart,
//                    end = absStartX + xEnd,
//                    count = 10
//                )
//            }

            else -> {
                if (style?.hasStyle == true) {
                    TextHtmlColumn(
                        start = absStartX + xStart,
                        end = absStartX + xEnd,
                        charData = char,
                        mTextSize = contentPaint.textSize * style.textSizeScale,
                        mTextColor = null,
                        linkUrl = null,
                        isBold = style.bold,
                        isItalic = style.italic,
                        isUnderline = style.underline,
                        isStrikethrough = style.strike,
                        baselineShift = contentPaint.textSize * style.baselineShiftEm
                    )
                } else {
                    TextColumn(
                        start = absStartX + xStart,
                        end = absStartX + xEnd,
                        charData = char
                    )
                }
            }
        }
        textLine.addColumn(column)
    }

    /**
     * 超出边界处理
     */
    private fun exceed(absStartX: Int, textLine: TextLine, words: List<String>) {
        var size = words.size
        if (size < 2) return
        val visibleEnd = absStartX + visibleWidth
        val columns = textLine.columns
        var offset = 0
        val endColumn = if (words.last() == " ") {
            size--
            offset++
            columns[columns.lastIndex - 1]
        } else {
            columns.last()
        }
        val endX = endColumn.end.roundToInt()
        if (endX > visibleEnd) {
            textLine.exceed = true
            val cc = (endX - visibleEnd) / size
            for (i in 0..<size) {
                textLine.getColumnReverseAt(i, offset).let {
                    val py = cc * (size - i)
                    it.start -= py
                    it.end -= py
                }
            }
        }
    }

    private suspend fun prepareNextPageIfNeed(requestHeight: Float = -1f) {
        if (requestHeight > visibleHeight || requestHeight == -1f) {
            val textPage = pendingTextPage
            // 双页的 durY 不正确，可能会小于实际高度
            if (textPage.height < durY) {
                textPage.height = durY
            }
            if (doublePage && absStartX < viewWidth / 2) {
                //当前页面左列结束
                textPage.leftLineSize = textPage.lineSize
                absStartX = viewWidth / 2 + paddingLeft
            } else {
                //当前页面结束,设置各种值
                if (textPage.leftLineSize == 0) {
                    textPage.leftLineSize = textPage.lineSize
                }
                textPage.text = stringBuilder.toString()
                currentCoroutineContext().ensureActive()
                onPageCompleted()
                //新建页面
                pendingTextPage = TextPage()
                stringBuilder.clear()
                absStartX = paddingLeft
            }
            durY = 0f
        }
    }

    private fun allocateFloatArray(size: Int): FloatArray {
        if (size > floatArray.size) {
            floatArray = FloatArray(size)
        }
        return floatArray
    }

    private fun measureTextSplit(
        text: String,
        widthsArray: FloatArray,
        start: Int = 0
    ): MeasuredWords {
        val length = text.length
        var clusterCount = 0
        for (i in start..<start + length) {
            if (widthsArray[i] > 0) clusterCount++
        }
        val widths = ArrayList<Float>(clusterCount)
        val stringList = ArrayList<String>(clusterCount)
        val offsets = ArrayList<Int>(clusterCount)
        var i = 0
        while (i < length) {
            val clusterBaseIndex = i++
            widths.add(widthsArray[start + clusterBaseIndex])
            offsets.add(clusterBaseIndex)
            while (i < length && widthsArray[start + i] == 0f && !isZeroWidthChar(text[i])) {
                i++
            }
            stringList.add(text.substring(clusterBaseIndex, i))
        }
        return MeasuredWords(stringList, widths, offsets)
    }

    private fun isZeroWidthChar(char: Char): Boolean {
        val code = char.code
        return code == 8203 || code == 8204 || code == 8205 || code == 8288
    }

}
