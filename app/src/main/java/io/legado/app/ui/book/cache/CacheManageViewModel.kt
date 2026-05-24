package io.legado.app.ui.book.cache

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.base.BaseViewModel
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.CacheBookManifest
import io.legado.app.help.book.CacheManifestHelper
import io.legado.app.help.book.getBookSource
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isNotShelf
import io.legado.app.help.book.isVideo
import io.legado.app.help.book.removeType
import io.legado.app.model.CacheBook
import io.legado.app.model.webBook.WebBook
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalCache
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.isJsonArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

class CacheManageViewModel(application: Application) : BaseViewModel(application) {

    val itemsLiveData = MutableLiveData<List<CacheBookItem>>()
    val summaryLiveData = MutableLiveData<CacheSummary>()
    val loadingLiveData = MutableLiveData<Boolean>()

    private var loadJob: Job? = null
    private val selectedSourceKeys = hashMapOf<String, String>()
    var mode: CacheManageMode = CacheManageMode.BOOK
        private set

    fun isLoading(): Boolean = loadJob?.isActive == true

    fun load(mode: CacheManageMode = this.mode) {
        this.mode = mode
        loadJob?.cancel()
        lateinit var job: Job
        job = viewModelScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            loadingLiveData.postValue(true)
            try {
                val currentBooks = getBooks(mode)
                val currentBookUrls = currentBooks.mapTo(hashSetOf()) { it.bookUrl }
                val cacheDirs = CacheManifestHelper.listCacheDirs()
                val cacheDirNames = cacheDirs.mapTo(hashSetOf()) { it.name }
                val manifests = CacheManifestHelper.listManifests(cacheDirs)
                val manifestByBookUrl = manifests.associateBy { it.bookUrl }
                val currentItems = currentBooks
                    .asSequence()
                    .mapNotNull { book ->
                        buildCacheBookItem(
                            book = book,
                            mode = mode,
                            knownManifest = manifestByBookUrl[book.bookUrl],
                            cacheDirNames = cacheDirNames
                        )
                    }
                    .toList()
                val manifestItems = manifests
                    .asSequence()
                    .filter { it.matches(mode) }
                    .filterNot { currentBookUrls.contains(it.bookUrl) }
                    .mapNotNull { manifest -> buildCacheBookItem(manifest, mode) }
                    .toList()
                val items = groupByBook(currentItems + manifestItems)
                ensureActive()
                itemsLiveData.postValue(items)
                summaryLiveData.postValue(
                    CacheSummary(
                        bookCount = items.size,
                        cachedChapterCount = items.sumOf { it.cachedCount },
                        mode = mode
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } finally {
                if (loadJob === job) {
                    loadingLiveData.postValue(false)
                }
            }
        }
        loadJob = job
        job.start()
    }

    fun selectSource(groupKey: String, sourceKey: String) {
        selectedSourceKeys[groupKey] = sourceKey
        load()
    }

    fun deleteBookCache(book: Book, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            deleteMediaCache(book)
            BookHelp.clearCache(book)
            CacheManifestHelper.delete(book)
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    fun deleteBookCaches(books: List<Book>, onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            books.forEach {
                deleteMediaCache(it)
                BookHelp.clearCache(it)
                CacheManifestHelper.delete(it)
            }
            withContext(Dispatchers.Main) {
                onDone()
            }
            load(mode)
        }
    }

    suspend fun getChapterItems(book: Book, key: String? = null): List<CacheChapterItem> {
        return getChapterItems(book, key, CacheChapterFilter.ALL)
    }

    suspend fun getChapterItems(
        book: Book,
        key: String? = null,
        filter: CacheChapterFilter = CacheChapterFilter.ALL
    ): List<CacheChapterItem> {
        return withContext(Dispatchers.IO) {
            val cacheNames = if (book.isMedia) emptySet() else getCacheFileNames(book)
            val manifest = CacheManifestHelper.read(book)
            val dbChapters = if (key.isNullOrBlank()) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            } else {
                appDb.bookChapterDao.search(book.bookUrl, key)
            }
            if (book.isMedia && CacheManifestHelper.mergeResourceUrls(dbChapters, manifest)) {
                appDb.bookChapterDao.update(*dbChapters.toTypedArray())
            }
            val chapters = dbChapters.takeIf { it.isNotEmpty() }
                ?: CacheManifestHelper.toChapters(manifest ?: return@withContext emptyList())
                    .filterByKey(key)
            chapters
                .asSequence()
                .filterNot { it.isVolume }
                .mapNotNull { chapter ->
                    val cached = isChapterCached(
                        book,
                        chapter,
                        cacheNames,
                        validateImageContent = false
                    )
                    when (filter) {
                        CacheChapterFilter.CACHED -> if (!cached) return@mapNotNull null
                        CacheChapterFilter.UNCACHED -> if (cached) return@mapNotNull null
                        CacheChapterFilter.ALL -> Unit
                    }
                    CacheChapterItem(chapter = chapter, cached = cached)
                }
                .toList()
        }
    }

    suspend fun deleteChapterCache(book: Book, chapter: BookChapter) {
        deleteChapterCaches(book, listOf(chapter))
    }

    suspend fun deleteChapterCaches(book: Book, chapters: List<BookChapter>) {
        withContext(Dispatchers.IO) {
            if (chapters.isEmpty()) return@withContext
            chapters.forEach { chapter ->
                if (book.isMedia) {
                    ExoPlayerHelper.removeMediaCache(chapter.resourceUrl)
                }
                BookHelp.delChapterCache(book, chapter)
            }
            refreshManifest(book)
        }
    }

    fun cacheBookChapters(book: Book, chapters: List<BookChapter>): Int {
        if (book.isMedia || book.isLocal) return 0
        val indexes = chapters
            .asSequence()
            .filterNot { it.isVolume }
            .map { it.index }
            .distinct()
            .sorted()
            .toList()
        if (indexes.isEmpty()) return 0
        indexes.toRanges().forEach { (start, end) ->
            CacheBook.start(appCtx, book, start, end)
        }
        return indexes.size
    }

    suspend fun cacheAudioChapters(
        book: Book,
        chapters: List<BookChapter>,
        reloadOnFinished: Boolean = true
    ): Int {
        return cacheMediaChapters(book, chapters, reloadOnFinished)
    }

    suspend fun cacheMediaChapters(
        book: Book,
        chapters: List<BookChapter>,
        reloadOnFinished: Boolean = true
    ): Int {
        if (!book.isMedia) return 0
        val targets = withContext(Dispatchers.IO) {
            val realChapters = chapters
                .asSequence()
                .filterNot { it.isVolume }
                .toList()
            if (CacheManifestHelper.mergeResourceUrls(realChapters, CacheManifestHelper.read(book))) {
                appDb.bookChapterDao.update(*realChapters.toTypedArray())
            }
            realChapters
                .asSequence()
                .filterNot { ExoPlayerHelper.isMediaCached(it.resourceUrl) }
                .toList()
        }
        if (targets.isEmpty()) return 0
        val started = AudioCacheTaskManager.start(
            book = book,
            chapters = targets,
            resolver = ::resolveMediaRequest,
            onChapterResolved = { chapter, request ->
                if (chapter.resourceUrl != request.url) {
                    chapter.resourceUrl = request.url
                    appDb.bookChapterDao.update(chapter)
                }
            },
            onFinished = {
                refreshManifest(book)
                if (reloadOnFinished && mode == book.cacheManageMode) {
                    load(mode)
                }
            }
        )
        if (started && mode == book.cacheManageMode) {
            load(mode)
        }
        return if (started) targets.size else 0
    }

    suspend fun restoreCacheToBookshelf(item: CacheBookItem): Boolean {
        return withContext(Dispatchers.IO) {
            val manifest = item.manifest ?: CacheManifestHelper.read(item.book) ?: return@withContext false
            val sameUrlBook = appDb.bookDao.getBook(manifest.bookUrl)
            val sameNameBook = appDb.bookDao.getBook(manifest.name, manifest.author)
            val cacheBook = CacheManifestHelper.toBook(manifest).apply {
                removeType(BookType.notShelf)
                sameUrlBook?.let {
                    group = it.group
                    order = it.order
                    durChapterIndex = it.durChapterIndex
                    durChapterTitle = it.durChapterTitle
                    durChapterPos = it.durChapterPos
                    readConfig = it.readConfig
                } ?: sameNameBook?.let {
                    group = it.group
                    order = it.order
                    durChapterIndex = it.durChapterIndex
                    durChapterTitle = it.durChapterTitle
                    durChapterPos = it.durChapterPos
                    readConfig = it.readConfig
                }
            }
            when {
                sameUrlBook != null -> appDb.bookDao.update(cacheBook)
                sameNameBook != null -> appDb.bookDao.replace(sameNameBook, cacheBook)
                else -> appDb.bookDao.insert(cacheBook)
            }
            val chapters = CacheManifestHelper.toChapters(manifest, cacheBook.bookUrl)
            if (chapters.isNotEmpty()) {
                appDb.bookChapterDao.delByBook(cacheBook.bookUrl)
                appDb.bookChapterDao.insert(*chapters.toTypedArray())
            }
            true
        }
    }

    suspend fun createCachePackage(book: Book): File {
        return withContext(Dispatchers.IO) {
            val cacheDir = BookHelp.getCacheDir(book)
            val outDir = File(appCtx.externalCache, "cache_package").apply {
                if (!exists()) mkdirs()
            }
            val fileName = "${book.name}_${book.author}_${System.currentTimeMillis()}"
                .normalizeFileName()
                .ifBlank { "cache_${System.currentTimeMillis()}" }
            val zipFile = File(outDir, "$fileName.zip").apply {
                if (exists()) delete()
            }
            if (book.isMedia) {
                return@withContext createMediaCachePackage(book, cacheDir, outDir, fileName, zipFile)
            }
            if (!cacheDir.exists() || cacheDir.listFiles().isNullOrEmpty()) {
                throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
            }
            if (!ZipUtils.zipFile(cacheDir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
                throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
            }
            zipFile
        }
    }

    private fun createMediaCachePackage(
        book: Book,
        cacheDir: File,
        outDir: File,
        fileName: String,
        zipFile: File
    ): File {
        val packageDir = File(outDir, "${fileName}_media").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        var hasCache = false
        if (cacheDir.exists() && !cacheDir.listFiles().isNullOrEmpty()) {
            cacheDir.copyRecursively(File(packageDir, "chapter_cache"), overwrite = true)
            hasCache = true
        }
        val mediaDir = File(packageDir, "media_cache").apply { mkdirs() }
        val chapters = (appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty())
            .filterNot { it.isVolume }
            .mapNotNull { chapter ->
                val chapterDir = File(mediaDir, chapter.index.toString())
                if (!ExoPlayerHelper.isMediaCached(chapter.resourceUrl)) {
                    chapterDir.deleteRecursively()
                    return@mapNotNull null
                }
                val fileCount = ExoPlayerHelper.copyMediaCache(chapter.resourceUrl, chapterDir)
                if (fileCount <= 0) {
                    chapterDir.deleteRecursively()
                    return@mapNotNull null
                }
                hasCache = true
                MediaCacheManifest.Chapter(
                    index = chapter.index,
                    title = chapter.title,
                    url = chapter.url,
                    resourceUrl = chapter.resourceUrl,
                    fileCount = fileCount
                )
            }
        if (!hasCache) {
            packageDir.deleteRecursively()
            throw IllegalStateException(context.getString(R.string.cache_manage_no_cache))
        }
        File(packageDir, "manifest.json").writeText(
            GSON.toJson(
                MediaCacheManifest(
                    bookName = book.name,
                    author = book.author,
                    bookUrl = book.bookUrl,
                    chapters = chapters
                )
            )
        )
        val success = ZipUtils.zipFile(packageDir, zipFile)
        packageDir.deleteRecursively()
        if (!success || !zipFile.exists() || zipFile.length() <= 0L) {
            throw IllegalStateException(context.getString(R.string.cache_manage_pack_failed))
        }
        return zipFile
    }

    private fun groupByBook(items: List<CacheBookItem>): List<CacheBookItem> {
        return items
            .groupBy { it.groupKey }
            .values
            .mapNotNull { group ->
                val variants = group
                    .sortedWith(
                        compareByDescending<CacheBookItem> { if (it.taskState.isVisibleAudioTask()) 1 else 0 }
                            .thenByDescending { it.cachedCount }
                            .thenBy { it.sourceName }
                    )
                    .map { it.toSourceVariant() }
                val groupKey = group.firstOrNull()?.groupKey ?: return@mapNotNull null
                val selectedKey = selectedSourceKeys[groupKey]
                val selected = group.firstOrNull { it.sourceKey == selectedKey }
                    ?: group.firstOrNull { it.taskState.isVisibleAudioTask() }
                    ?: group.maxWithOrNull(
                        compareBy<CacheBookItem> { it.cachedCount }
                            .thenBy { it.totalChapterCount }
                    )
                    ?: group.first()
                selected.copy(sourceVariants = variants)
            }
            .sortedWith(
                compareByDescending<CacheBookItem> { it.cachedCount }
                    .thenBy { it.book.name }
                    .thenBy { it.sourceName }
            )
    }

    private fun buildCacheBookItem(
        book: Book,
        mode: CacheManageMode,
        knownManifest: CacheBookManifest? = null,
        cacheDirNames: Set<String> = emptySet()
    ): CacheBookItem? {
        val taskState = AudioCacheTaskManager.snapshot(book.bookUrl)
        if (mode.isMedia) {
            return buildMediaCacheBookItem(book, mode, knownManifest, taskState)
        }
        if (knownManifest == null &&
            taskState?.active != true &&
            !cacheDirNames.contains(book.getFolderName())
        ) {
            return null
        }
        val cacheNames = getCacheFileNames(book)
        val needsChapterList = book.totalChapterNum <= 0 || book.isNotShelf
        var manifest = knownManifest ?: CacheManifestHelper.read(book)
        val dbChapters = if (needsChapterList) {
            appDb.bookChapterDao.getChapterList(book.bookUrl)
        } else {
            emptyList()
        }
        val chapters = dbChapters.takeIf { it.isNotEmpty() }
            ?: manifest?.let(CacheManifestHelper::toChapters)
            ?: emptyList()
        val rawCachedCount = getFastCachedCount(cacheNames)
        if (rawCachedCount <= 0 && taskState?.active != true) {
            CacheManifestHelper.delete(book)
            return null
        }
        if (book.isNotShelf && manifest == null) {
            manifest = CacheManifestHelper.refresh(book, chapters)
        }
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: rawCachedCount
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = !book.isNotShelf,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun buildMediaCacheBookItem(
        book: Book,
        mode: CacheManageMode,
        initialManifest: CacheBookManifest?,
        taskState: AudioCacheTaskState?
    ): CacheBookItem? {
        var manifest = initialManifest
        if (book.isNotShelf && manifest == null) {
            manifest = CacheManifestHelper.refresh(book)
        }
        val hasVisibleTask = taskState.isVisibleAudioTask()
        if (manifest == null && !hasVisibleTask) return null
        val candidateCachedIndexes = manifest.cachedIndexes()
        val manifestChapters = manifest?.let(CacheManifestHelper::toChapters).orEmpty()
        val realCachedCount = getAudioCachedCount(manifestChapters, candidateCachedIndexes)
        val taskCompletedCount = taskState?.completedChapters ?: 0
        val rawCachedCount = maxOf(realCachedCount, taskCompletedCount)
        if (rawCachedCount <= 0 && !hasVisibleTask) {
            CacheManifestHelper.delete(book)
            return null
        }
        val totalChapterCount = book.totalChapterNum.takeIf { it > 0 }
            ?: manifest?.totalChapterNum?.takeIf { it > 0 }
            ?: manifestChapters.size.takeIf { it > 0 }
            ?: taskState?.totalChapters?.takeIf { it > 0 }
            ?: rawCachedCount.coerceAtLeast(1)
        val cachedCount = rawCachedCount.coerceAtMost(totalChapterCount)
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = !book.isNotShelf,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun buildCacheBookItem(
        manifest: CacheBookManifest,
        mode: CacheManageMode
    ): CacheBookItem? {
        val book = CacheManifestHelper.toBook(manifest)
        val chapters = CacheManifestHelper.toChapters(manifest)
        val cacheNames = getCacheFileNames(book)
        val rawCachedCount = if (mode.isMedia) {
            getAudioCachedCount(chapters, manifest.cachedIndexes())
        } else {
            chapters.count {
                isChapterCached(book, it, cacheNames, validateImageContent = false)
            }
        }
        if (rawCachedCount <= 0) {
            if (mode.isMedia) {
                CacheManifestHelper.delete(manifest)
            }
            return null
        }
        val totalChapterCount = manifest.totalChapterNum.takeIf { it > 0 }
            ?: chapters.size.takeIf { it > 0 }
            ?: rawCachedCount
        return CacheBookItem(
            book = book,
            mode = mode,
            groupKey = book.cacheGroupKey(mode),
            sourceKey = book.cacheSourceKey(),
            sourceName = book.cacheSourceName(),
            cachedCount = rawCachedCount.coerceAtMost(totalChapterCount),
            totalChapterCount = totalChapterCount,
            manifest = manifest,
            inBookshelf = false,
            sourceAvailable = book.isLocal || book.getBookSource() != null
        )
    }

    private fun getFastCachedCount(cacheNames: Set<String>): Int {
        return cacheNames.count { it.endsWith(".nb") }
    }

    private fun getAudioCachedCount(chapters: List<BookChapter>): Int {
        return getAudioCachedCount(chapters, cachedIndexes = null)
    }

    private fun getAudioCachedCount(
        chapters: List<BookChapter>,
        cachedIndexes: Set<Int>? = null
    ): Int {
        return chapters
            .asSequence()
            .filterNot { it.isVolume }
            .filter { cachedIndexes == null || it.index in cachedIndexes }
            .count { ExoPlayerHelper.isMediaCached(it.resourceUrl) }
    }

    private fun CacheBookManifest?.cachedIndexes(): Set<Int>? {
        return this
            ?.chapters
            ?.asSequence()
            ?.filter { it.cached }
            ?.mapTo(hashSetOf()) { it.index }
    }

    private fun getCacheFileNames(book: Book): Set<String> {
        val cacheDir = BookHelp.getCacheDir(book)
        if (!cacheDir.exists() || !cacheDir.isDirectory) return emptySet()
        return cacheDir.list()?.toSet().orEmpty()
    }

    private fun isChapterCached(
        book: Book,
        chapter: BookChapter,
        cacheNames: Set<String> = getCacheFileNames(book),
        validateImageContent: Boolean = true
    ): Boolean {
        if (book.isLocal) return false
        if (book.isMedia) return ExoPlayerHelper.isMediaCached(chapter.resourceUrl)
        val hasContent = BookHelp.getChapterCacheFileNames(book, chapter).any(cacheNames::contains)
        return if (validateImageContent && book.isImage && hasContent) {
            BookHelp.hasImageContent(book, chapter)
        } else {
            hasContent
        }
    }

    private fun getBooks(mode: CacheManageMode): List<Book> {
        return when (mode) {
            CacheManageMode.BOOK -> appDb.bookDao.getByTypeOnLine(BookType.text)
            CacheManageMode.AUDIO -> appDb.bookDao.getByTypeOnLine(BookType.audio)
            CacheManageMode.VIDEO -> appDb.bookDao.getByTypeOnLine(BookType.video)
            CacheManageMode.MANGA -> appDb.bookDao.getByTypeOnLine(BookType.image)
        }
    }

    private fun deleteMediaCache(book: Book) {
        if (!book.isMedia) return
        val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            .takeIf { it.isNotEmpty() }
            ?: CacheManifestHelper.read(book)?.let(CacheManifestHelper::toChapters).orEmpty()
        chapters
            .forEach { ExoPlayerHelper.removeMediaCache(it.resourceUrl) }
    }

    private fun refreshManifest(book: Book) {
        CacheManifestHelper.refresh(book)
    }

    private suspend fun resolveMediaRequest(
        book: Book,
        chapter: BookChapter
    ): ExoPlayerHelper.MediaRequest {
        chapter.resourceUrl
            ?.takeIf { it.isNotBlank() }
            ?.takeIf(::isDownloadableMediaContent)
            ?.let { return ExoPlayerHelper.MediaRequest(it) }
        val source = book.getBookSource()
            ?: throw IllegalStateException(context.getString(R.string.book_source_not_found))
        val candidates = linkedSetOf<String>()
        BookHelp.getContent(book, chapter)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { content -> normalizeMediaContent(book, content) }
            ?.let(candidates::add)
        WebBook.getContentAwait(source, book, chapter, needSave = true)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { content -> normalizeMediaContent(book, content) }
            ?.let(candidates::add)
        for (content in candidates) {
            if (content.isJsonArray()) {
                return ExoPlayerHelper.MediaRequest(content)
            }
            return RustAnalyzerBridge.resolveMediaRequest(
                url = content,
                source = source,
                baseUrl = chapter.baseUrl,
                rulePath = "CacheManage.resolveMediaRequest"
            )
        }
        throw IllegalStateException(
            context.getString(R.string.cache_manage_audio_url_empty)
        )
    }

    private fun normalizeMediaContent(book: Book, content: String): String {
        if (!book.isVideo) return content
        if (content.startsWith("#EXTM3U")) {
            return writeVideoTempManifest(content, "m3u8")
        }
        if (!content.startsWith("<")) return content
        return writeVideoTempManifest(content, "mpd")
    }

    private fun writeVideoTempManifest(content: String, suffix: String): String {
        val dir = File(appCtx.externalCache, "video_temp_cache").apply { mkdirs() }
        val file = File(dir, "${MD5Utils.md5Encode(content)}.$suffix")
        if (!file.isFile || file.readText() != content) {
            file.writeText(content)
        }
        return Uri.fromFile(file).toString()
    }

    private fun isDownloadableMediaContent(content: String): Boolean {
        val urls = if (content.isJsonArray()) {
            GSON.fromJsonArray<String>(content).getOrElse {
                throw IllegalArgumentException(
                    "CacheManage media content URL array JSON is invalid for Rust analyzer handoff: " +
                        (it.localizedMessage ?: it::class.java.name)
                )
            }
        } else {
            listOf(content)
        }
        return urls.isNotEmpty() && urls.all {
            val scheme = Uri.parse(it).scheme
            scheme.equals("http", true) ||
                scheme.equals("https", true) ||
                (scheme.equals("file", true) && isVideoManifestUrl(it))
        }
    }

    private fun isVideoManifestUrl(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".m3u8") || lower.endsWith(".mpd") || lower.endsWith(".ism")
    }

    private fun Book.cacheGroupKey(mode: CacheManageMode): String {
        return listOf(
            mode.name,
            name.trim(),
            getRealAuthor().trim()
        ).joinToString(separator = "\u001F")
    }

    private fun Book.cacheSourceKey(): String {
        return listOf(
            origin.ifBlank { originName },
            bookUrl
        ).joinToString(separator = "\u001F")
    }

    private fun Book.cacheSourceName(): String {
        return when {
            isLocal -> context.getString(R.string.local)
            originName.isNotBlank() -> originName
            origin.isNotBlank() -> origin
            else -> context.getString(R.string.unknown)
        }
    }

    private fun CacheBookItem.toSourceVariant(): CacheBookSourceVariant {
        return CacheBookSourceVariant(
            sourceKey = sourceKey,
            sourceName = sourceName,
            book = book,
            cachedCount = cachedCount,
            totalChapterCount = totalChapterCount,
            taskState = taskState,
            manifest = manifest,
            inBookshelf = inBookshelf,
            sourceAvailable = sourceAvailable
        )
    }
}

enum class CacheManageMode(@StringRes val titleRes: Int, val bookType: Int) {
    BOOK(R.string.cache_manage_books, BookType.text),
    AUDIO(R.string.cache_manage_audio, BookType.audio),
    VIDEO(R.string.cache_manage_video, BookType.video),
    MANGA(R.string.cache_manage_manga, BookType.image)
}

enum class CacheChapterFilter {
    ALL,
    CACHED,
    UNCACHED
}

data class CacheBookItem(
    val book: Book,
    val mode: CacheManageMode,
    val groupKey: String,
    val sourceKey: String,
    val sourceName: String,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val taskState: AudioCacheTaskState? = null,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true,
    val sourceAvailable: Boolean = true,
    val sourceVariants: List<CacheBookSourceVariant> = emptyList()
)

data class CacheBookSourceVariant(
    val sourceKey: String,
    val sourceName: String,
    val book: Book,
    val cachedCount: Int,
    val totalChapterCount: Int,
    val taskState: AudioCacheTaskState? = null,
    val manifest: CacheBookManifest? = null,
    val inBookshelf: Boolean = true,
    val sourceAvailable: Boolean = true
)

data class CacheChapterItem(
    val chapter: BookChapter,
    val cached: Boolean
)

data class CacheSummary(
    val bookCount: Int,
    val cachedChapterCount: Int,
    val mode: CacheManageMode
)

private data class MediaCacheManifest(
    val bookName: String,
    val author: String,
    val bookUrl: String,
    val chapters: List<Chapter>
) {
    data class Chapter(
        val index: Int,
        val title: String,
        val url: String,
        val resourceUrl: String?,
        val fileCount: Int
    )
}

private fun CacheBookManifest.matches(mode: CacheManageMode): Boolean {
    return type and mode.bookType > 0
}

private val CacheManageMode.isMedia: Boolean
    get() = this == CacheManageMode.AUDIO || this == CacheManageMode.VIDEO

private val Book.isMedia: Boolean
    get() = isAudio || isVideo

private val Book.cacheManageMode: CacheManageMode
    get() = if (isVideo) CacheManageMode.VIDEO else CacheManageMode.AUDIO

private fun AudioCacheTaskState?.isVisibleAudioTask(): Boolean {
    return this?.active == true || this?.status == CacheTaskStatus.PAUSED
}

private fun List<BookChapter>.filterByKey(key: String?): List<BookChapter> {
    if (key.isNullOrBlank()) return this
    return filter { it.title.contains(key, ignoreCase = true) }
}

private fun List<Int>.toRanges(): List<Pair<Int, Int>> {
    if (isEmpty()) return emptyList()
    val ranges = arrayListOf<Pair<Int, Int>>()
    var start = first()
    var previous = first()
    drop(1).forEach { value ->
        if (value == previous + 1) {
            previous = value
        } else {
            ranges.add(start to previous)
            start = value
            previous = value
        }
    }
    ranges.add(start to previous)
    return ranges
}
