package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.core.content.edit
import com.shuyu.gsyvideoplayer.listener.GSYMediaPlayerListener
import com.shuyu.gsyvideoplayer.utils.CommonUtil
import com.shuyu.gsyvideoplayer.video.StandardGSYVideoPlayer
import com.shuyu.gsyvideoplayer.video.base.GSYBaseVideoPlayer
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.constant.SourceType
import io.legado.app.data.appDb
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookProgress
import io.legado.app.data.entities.BookProgressComparison
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadRecentBook
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.exception.ContentEmptyException
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.CacheManager
import io.legado.app.help.ReadRecordDailyHelper
import io.legado.app.help.book.getDanmaku
import io.legado.app.help.book.update
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.globalExecutor
import io.legado.app.help.gsyVideo.ExoVideoManager
import io.legado.app.help.gsyVideo.ExoVideoManager.Companion.FULLSCREEN_ID
import io.legado.app.help.gsyVideo.FloatingPlayer
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.externalCache
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object VideoPlay : CoroutineScope by MainScope(){
    private const val VIDEO_POS_NAME = "video_pos_" //单链接播放进度
    private const val VIDEO_POS_SAVE_TIME = 60 * 60 * 24 * 20 //20天
    private var needClearTemp = true //需要清理缓存
    private const val VIDEO_TEMP_PATH = "video_temp"
    private const val CHAPTER_LINK_CACHE_TTL = 30 * 60 * 1000L
    private val videoTempFile by lazy { File(FileUtils.getCachePath(), VIDEO_TEMP_PATH) }
    private data class CachedPlayLink(
        val playUrl: String,
        val headers: Map<String, String>,
        val mediaUrl: String,
        val createdAt: Long
    )
    private val chapterLinkCache = ConcurrentHashMap<String, CachedPlayLink>()
    private val preloadingKeys = ConcurrentHashMap.newKeySet<String>()
    private val preloadMutex = Mutex()

    const val VIDEO_PREF_NAME = "video_config"

    private val videoPrefs: SharedPreferences by lazy { appCtx.getSharedPreferences(VIDEO_PREF_NAME, MODE_PRIVATE) }
    /**  是否自动播放  **/
    var autoPlay
        get() = videoPrefs.getBoolean("autoPlay", true)
        set(value) {
            videoPrefs.edit { putBoolean("autoPlay", value) }
        }
    /**  直接全屏，需先启用自动播放  **/
    var startFull
        get() = videoPrefs.getBoolean("startFull", false)
        set(value) {
            videoPrefs.edit { putBoolean("startFull", value) }
        }
    /**  长按倍速  **/
    var longPressSpeed
        get() = videoPrefs.getInt("longPressSpeed", 30)
        set(value) {
            videoPrefs.edit { putInt("longPressSpeed", value) }
        }
    /**  全屏底部进度条  **/
    var fullBottomProgressBar
        get() = videoPrefs.getBoolean("fullBottomProgressBar", true)
        set(value) {
            videoPrefs.edit { putBoolean("fullBottomProgressBar", value) }
        }
    /**  弹幕滚动速度  **/
    var danmakuSpeed = 1.2f
    /**  锁屏  **/
    var lockCurScreen = false
    /**  竖屏视频  **/
    var isPortraitVideo = false

    val videoManager by lazy { ExoVideoManager() }
    private var isLoading = false
    private val loadScope = CoroutineScope(SupervisorJob() + IO)
    var videoUrl: String? = null //播放链接
    var singleUrl = false
    var videoTitle: String? = null
    var source: BaseSource? = null
    var book: Book? = null
    var toc: List<BookChapter>? =  null
    var chapter: BookChapter? = null
    var volumes = arrayListOf<BookChapter>()
    var episodes: List<BookChapter>? =  null
    /**  在当前episodes中的位置  **/
    var chapterInVolumeIndex = 0
    /**  卷章节 -> 线路或者季数  **/
    var durVolumeIndex = 0
    /**  当前卷  **/
    var durVolume: BookChapter? = null
    /**  本集的进度  **/
    var durChapterPos = 0
    var inBookshelf = true
    /**  订阅收藏  **/
    var rssStar: RssStar? = null
    /**  订阅历史记录,收藏优先  **/
    var rssRecord: RssReadRecord? = null
    private val readRecord = ReadRecord()
    var readStartTime: Long = System.currentTimeMillis()
    /**  弹幕相关  **/
    var danmakuFile: File? = null
    var danmakuStr: String? = null
    var danmakuShow = true

    /**
     * 开始播放
     */
    fun startPlay(player: StandardGSYVideoPlayer) {
        val playSource = source ?: return
        danmakuStr = null
        danmakuFile = null
        val player = player.getCurrentPlayer()
        if (singleUrl) {
            val mUrl = videoUrl ?: return
            Coroutine.async(loadScope, IO) {
                CacheManager.getLong(VIDEO_POS_NAME + mUrl)?.let {
                    player.seekOnStart = it
                }
                inBookshelf = true
                val request = RustAnalyzerBridge.resolveMediaRequest(
                    url = mUrl,
                    source = playSource,
                    rulePath = "VideoPlay.singleUrl"
                )
                withContext(Main) {
                    player.mapHeadData = request.headers.toMutableMap()
                    val url = request.url
                    player.setUp(url, false, File(appCtx.externalCache, "exoplayer"), videoTitle)
                    if (autoPlay) {
                        player.startPlayLogic()
                    }
                }
            }.onError {
                throw NoStackTraceException(
                    "VideoPlay Rust single URL media resolution failed: ${it.localizedMessage ?: it}"
                )
            }
            return
        }
        durChapterPos.takeIf { it > 0 }?.toLong()?.let { player.seekOnStart = it }
        (playSource as? RssSource)?.let { s ->
            val rssArticle = rssStar?.toRssArticle() ?: rssRecord?.toRssArticle()
            if (rssArticle == null) {
                appCtx.toastOnUi("未找到订阅")
                return
            }
            val ruleContent = s.ruleContent
            if (ruleContent.isNullOrBlank()) {
                Coroutine.async(loadScope, IO) {
                    val mUrl = rssArticle.link
                    videoUrl = mUrl
                    val request = RustAnalyzerBridge.resolveMediaRequest(
                        url = mUrl,
                        source = playSource,
                        baseUrl = rssArticle.link,
                        rulePath = "VideoPlay.rssLink"
                    )
                    withContext(Main) {
                        player.mapHeadData = request.headers.toMutableMap()
                        player.setUp(
                            request.url,
                            false,
                            File(appCtx.externalCache, "exoplayer"),
                            rssArticle.title
                        )
                        if (autoPlay) {
                            player.startPlayLogic()
                        }
                    }
                }.onError {
                    throw NoStackTraceException(
                        "VideoPlay Rust RSS link media resolution failed: ${it.localizedMessage ?: it}"
                    )
                }
            } else {
                Rss.getContent(loadScope, rssArticle, ruleContent, s)
                    .onSuccess(IO) { content ->
                        val content = content.trim()
                        val mUrl = if (content.isEmpty()) {
                            throw ContentEmptyException("正文为空")
                        } else if (content.startsWith("<")) { //当作mpd文本
                            val name = MD5Utils.md5Encode(content) + ".mpd"
                            val file = FileUtils.createFileIfNotExist(videoTempFile,name)
                            file.writeText(content)
                            Uri.fromFile(file).toString()
                        } else {
                            NetworkUtils.getAbsoluteURL(rssArticle.link, content)
                        }
                        videoUrl = mUrl
                        val request = RustAnalyzerBridge.resolveMediaRequest(
                            url = mUrl,
                            source = playSource,
                            baseUrl = rssArticle.link,
                            rulePath = "VideoPlay.rssContent"
                        )
                        val playUrl = request.url
                        withContext(Main) {
                            player.mapHeadData = request.headers.toMutableMap()
                            player.setUp(playUrl, false, File(appCtx.externalCache, "exoplayer"), rssArticle.title)
                            if (autoPlay) {
                                player.startPlayLogic()
                            }
                        }
                    }.onError {
                        throw NoStackTraceException(
                            "VideoPlay Rust RSS content/media URL failed: ${it.localizedMessage ?: it}"
                        )
                    }
            }
            return
        }
        val book = book
        if (book == null) {
            appCtx.toastOnUi("未找到书籍")
            return
        }
        chapter = if (episodes.isNullOrEmpty()) {
            //没有卷目录，那么卷就是播放的章节（适合电影类，没有剧集，全是线路卷章节，如果全是章节没有卷的写法，播放完后会继续下一个线路重复播放）
            val durVolume = durVolume
            when {
                durVolume == null -> null
                durVolume.url.startsWith(durVolume.title) -> null //卷章节没获取到链接（链接以标题开头）则返回null
                else -> durVolume
            }
        } else {
            // 优先获取当前索引的剧集，如果不存在则尝试获取第一个剧集
            episodes?.getOrNull(chapterInVolumeIndex) ?: run {
                chapterInVolumeIndex = 0
                episodes?.getOrNull(chapterInVolumeIndex)
            }
        }
        val chapter = chapter
        if (chapter == null) {
            appCtx.toastOnUi("未找到章节")
            return
        }
        val chapterSource = source as BookSource
        val chapterCacheKey = buildChapterCacheKey(chapterSource, book, chapter)
        chapter.resourceUrl
            ?.takeIf { ExoPlayerHelper.isMediaCached(it) }
            ?.let { cachedUrl ->
                playResolvedChapter(player, chapterSource, book, chapter, cachedUrl, emptyMap())
                isLoading = false
                return
            }
        val cached = chapterLinkCache[chapterCacheKey]?.takeIf {
            System.currentTimeMillis() - it.createdAt <= CHAPTER_LINK_CACHE_TTL
        }
        if (cached != null) {
            playResolvedChapter(player, chapterSource, book, chapter, cached.playUrl, cached.headers)
            isLoading = false
            return
        }
        WebBook.getContent(loadScope, chapterSource, book, chapter)
            .onSuccess(IO) { content ->
                val content = content.trim()
                val mUrl = if (content.isEmpty()) {
                    throw ContentEmptyException("正文为空")
                } else if (content.startsWith("<")) { //当作mpd文本
                    val name = MD5Utils.md5Encode(content) + ".mpd"
                    val file = FileUtils.createFileIfNotExist(videoTempFile,name)
                    file.writeText(content)
                    Uri.fromFile(file).toString()
                } else {
                    content
                }
                videoUrl = mUrl
                val request = RustAnalyzerBridge.resolveMediaRequest(
                    url = mUrl,
                    source = chapterSource,
                    baseUrl = chapter.baseUrl,
                    rulePath = "VideoPlay.chapterContent"
                )
                when (val danmaku = chapter.getDanmaku()) {
                    is String -> danmakuStr = danmaku
                    is File -> danmakuFile = danmaku
                }
                val playUrl = request.url
                if (chapter.resourceUrl != playUrl) {
                    chapter.resourceUrl = playUrl
                    appDb.bookChapterDao.update(chapter)
                }
                chapterLinkCache[chapterCacheKey] = CachedPlayLink(
                    playUrl = playUrl,
                    headers = request.headers,
                    mediaUrl = mUrl,
                    createdAt = System.currentTimeMillis()
                )
                withContext(Main) {
                    player.mapHeadData = request.headers.toMutableMap()
                    player.setUp(playUrl, false, File(appCtx.externalCache, "exoplayer"), chapter.title)
                    if (autoPlay) {
                        player.startPlayLogic()
                    }
                }
                preloadNextEpisode(chapterSource, book)
            }.onError {
                throw NoStackTraceException(
                    "VideoPlay Rust chapter content/media URL failed for ${book.name}-${chapter.title}: " +
                        (it.localizedMessage ?: it.toString())
                )
            }
        isLoading = false
    }

    private fun playResolvedChapter(
        player: GSYBaseVideoPlayer,
        source: BookSource,
        book: Book,
        chapter: BookChapter,
        playUrl: String,
        headers: Map<String, String>
    ) {
        videoUrl = playUrl
        when (val danmaku = chapter.getDanmaku()) {
            is String -> danmakuStr = danmaku
            is File -> danmakuFile = danmaku
        }
        player.mapHeadData = headers.toMutableMap()
        player.setUp(playUrl, false, File(appCtx.externalCache, "exoplayer"), chapter.title)
        if (autoPlay) {
            player.startPlayLogic()
        }
        preloadNextEpisode(source, book)
    }

    fun refreshCurrentChapter(player: StandardGSYVideoPlayer) {
        val chapter = chapter ?: return
        val book = book ?: return
        val source = source as? BookSource ?: return
        saveRead(videoManager.currentPosition.toInt())
        chapterLinkCache.remove(buildChapterCacheKey(source, book, chapter))
        chapter.resourceUrl = null
        Coroutine.async(loadScope, IO) {
            appDb.bookChapterDao.update(chapter)
        }
        videoUrl = null
        startPlay(player)
    }

    private fun buildChapterCacheKey(source: BookSource, book: Book, chapter: BookChapter): String {
        return "${source.getKey()}|${book.bookUrl}|${chapter.url}"
    }

    private fun preloadNextEpisode(source: BookSource, book: Book) {
        val nextChapter = episodes?.getOrNull(chapterInVolumeIndex + 1) ?: return
        val nextKey = buildChapterCacheKey(source, book, nextChapter)
        val exists = chapterLinkCache[nextKey]?.let {
            System.currentTimeMillis() - it.createdAt <= CHAPTER_LINK_CACHE_TTL
        } == true
        if (exists || !preloadingKeys.add(nextKey)) return
        Coroutine.async(loadScope, IO) {
            preloadMutex.withLock {
                try {
                    if (chapterLinkCache[nextKey]?.let {
                            System.currentTimeMillis() - it.createdAt <= CHAPTER_LINK_CACHE_TTL
                        } == true
                    ) return@withLock
                    val content = WebBook.getContentAwait(source, book, nextChapter).trim()
                    if (content.isEmpty()) return@withLock
                    val mUrl = if (content.startsWith("<")) {
                        val name = MD5Utils.md5Encode(content) + ".mpd"
                        val file = FileUtils.createFileIfNotExist(videoTempFile, name)
                        file.writeText(content)
                        Uri.fromFile(file).toString()
                    } else {
                        content
                    }
                    val request = RustAnalyzerBridge.resolveMediaRequest(
                        url = mUrl,
                        source = source,
                        baseUrl = nextChapter.baseUrl,
                        rulePath = "VideoPlay.preloadNextEpisode"
                    )
                    chapterLinkCache[nextKey] = CachedPlayLink(
                        playUrl = request.url,
                        headers = request.headers,
                        mediaUrl = mUrl,
                        createdAt = System.currentTimeMillis()
                    )
                } catch (e: Throwable) {
                    throw NoStackTraceException(
                        "VideoPlay Rust preload content/media URL failed for ${book.name}-${nextChapter.title}: " +
                            (e.localizedMessage ?: e.toString())
                    )
                } finally {
                    preloadingKeys.remove(nextKey)
                }
            }
        }
    }

    /**
     * 退出全屏，主要用于返回键
     *
     * @return 返回是否全屏
     */
    fun backFromWindowFull(context: Context?): Boolean {
        var backFrom = false
        val vp =
            (CommonUtil.scanForActivity(context)).findViewById<View?>(Window.ID_ANDROID_CONTENT) as ViewGroup
        val oldF = vp.findViewById<View?>(FULLSCREEN_ID)
        if (oldF != null) {
            backFrom = true
            CommonUtil.hideNavKey(context)
            if (videoManager.lastListener() != null) {
                videoManager.lastListener().onBackFullscreen()
            }
        }
        return backFrom
    }
    /**
     * 页面销毁了记得调用是否所有的video
     */
    fun releaseAllVideos() {
        if (videoManager.listener() != null) {
            videoManager.listener().onCompletion()
        }
        videoManager.releaseMediaPlayer()
        if (!isLoading) {
            //还原所有状态
            videoUrl = null
            singleUrl = false
            videoTitle = null
            source = null
            book = null
            toc = null
            chapter = null
            volumes.clear()
            episodes = null
            chapterInVolumeIndex = 0
            durVolumeIndex = 0
            durVolume = null
            durChapterPos = 0
            inBookshelf = true
            rssStar = null
            rssRecord = null
            danmakuStr = null
            danmakuFile = null
            lockCurScreen = false
            isPortraitVideo = false
            release()
            if (needClearTemp) {
                needClearTemp = false
                FileUtils.delete(videoTempFile)
            }
        }
    }
    /**
     * 暂停播放
     */
    fun onPause() {
        upReadTime()
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoPause()
        }
    }

    /**
     * 恢复播放
     */
    fun onResume() {
        markReadStart()
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoResume()
        }
    }


    /**
     * 恢复暂停状态
     * @param seek 是否产生seek动作,直播设置为false
     */
    fun onResume(seek: Boolean) {
        markReadStart()
        if (videoManager.listener() != null) {
            videoManager.listener().onVideoResume(seek)
        }
    }

    //播放器移植 - 辅助函数
    @SuppressLint("StaticFieldLeak")
    private var sSwitchVideo: StandardGSYVideoPlayer? = null
    private var sMediaPlayerListener: GSYMediaPlayerListener? = null
    fun savePlayState(switchVideo: StandardGSYVideoPlayer) {
        when (switchVideo) {
            is VideoPlayer -> sSwitchVideo = switchVideo.saveState()
            is FloatingPlayer -> sSwitchVideo = switchVideo.saveState()
        }
        sMediaPlayerListener = switchVideo
    }
    fun clonePlayState(switchVideo: StandardGSYVideoPlayer) {
        when (switchVideo) {
            is VideoPlayer -> sSwitchVideo?.let { switchVideo.cloneState(it) }
            is FloatingPlayer -> sSwitchVideo?.let { switchVideo.cloneState(it) }
        }
    }

    fun release() {
        sMediaPlayerListener?.onAutoCompletion()
        sMediaPlayerListener = null
        sSwitchVideo = null
    }

    fun markReadStart() {
        readStartTime = System.currentTimeMillis()
    }

    fun upReadTime() {
        val book = book ?: return
        if (!inBookshelf || !AppConfig.enableReadRecord) {
            markReadStart()
            return
        }
        val now = System.currentTimeMillis()
        val delta = now - readStartTime
        readStartTime = now
        if (delta <= 0L) return
        if (readRecord.bookName != book.name || readRecord.deviceId != AppConst.androidId) {
            readRecord.deviceId = AppConst.androidId
            readRecord.bookName = book.name
            readRecord.readTime = appDb.readRecordDao.getReadTime(AppConst.androidId, book.name) ?: 0L
        }
        readRecord.readTime += delta
        readRecord.lastRead = now
        val record = readRecord.copy()
        globalExecutor.execute {
            appDb.readRecordDao.insert(record)
            ReadRecordDailyHelper.record(delta, now)
        }
    }

    fun stopLoading() {
        loadScope.coroutineContext.cancelChildren()
    }

    fun initSource(sourceKey: String?, sourceType: Int?, bookUrl: String?, record:String?): Boolean {
        isLoading = true
        source = sourceKey?.let {
            when (sourceType) {
                SourceType.book -> appDb.bookSourceDao.getBookSource(it)
                SourceType.rss -> appDb.rssSourceDao.getByKey(it)
                else -> null
            }
        }
        book = bookUrl?.let {
            toc = appDb.bookChapterDao.getChapterList(it)
            volumes.clear()
            toc?.forEach { t ->
                if (t.isVolume) {
                    volumes.add(t)
                }
            }
            appDb.bookDao.getBook(it) ?: appDb.searchBookDao.getSearchBook(it)?.toBook()
        }?.also { b ->
            chapterInVolumeIndex = b.chapterInVolumeIndex
            durVolumeIndex = b.durVolumeIndex
            durChapterPos = b.durChapterPos
            source = appDb.bookSourceDao.getBookSource(b.origin)
            SourceCallBack.callBackBook(SourceCallBack.START_READ, source as BookSource?, b, chapter)
            readRecord.deviceId = AppConst.androidId
            readRecord.bookName = b.name
            readRecord.readTime = appDb.readRecordDao.getReadTime(AppConst.androidId, b.name) ?: 0L
            readRecord.lastRead = System.currentTimeMillis()
            markReadStart()
        }
        upEpisodes()
        if (source == null) {
            appCtx.toastOnUi("未找到源")
            return false
        }
        record?.let{ //订阅源
            val sourceKey = sourceKey ?: return@let
            rssStar =appDb.rssStarDao.get(sourceKey, it)?.also{ r ->
                durChapterPos = r.durPos
            }
            if (rssStar == null) {
                rssRecord = appDb.rssReadRecordDao.getRecord(it,sourceKey)?.also{ r ->
                    durChapterPos = r.durPos
                }
            }
        }
        return true
    }

    fun upEpisodes() {
        val volumes = volumes
        if (volumes.isEmpty()) {
            durVolume = null
            episodes = toc
            return
        }
        val toc = toc ?: return
        durVolume = volumes.getOrNull(durVolumeIndex)
        if (durVolume == null) {
            durVolumeIndex = 0
            durVolume = volumes.getOrNull(durVolumeIndex)
        }
        val startInt = durVolume?.index ?: 0
        val endInt = volumes.getOrNull(durVolumeIndex + 1)?.index ?: toc.size
        episodes = toc.subList(startInt + 1, endInt)
    }

    fun upDurIndex(offset: Int, player: StandardGSYVideoPlayer): Boolean {
        val episodes = episodes ?: return false
        val index = chapterInVolumeIndex + offset
        if (index < 0) {
            appCtx.toastOnUi("已到开头")
            return false
        }
        if (index >= episodes.size) {
            appCtx.toastOnUi("已播放完")
            return false
        }
        upReadTime()
        chapterInVolumeIndex = index
        saveRead(0)
        markReadStart()
        startPlay(player)
        postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1)) //更新选集视图
        return true
    }

    fun setProgress(progress: BookProgress, player: StandardGSYVideoPlayer? = null) {
        val toc = toc ?: return
        if (progress.durChapterIndex !in toc.indices) {
            return
        }
        if (volumes.isEmpty()) {
            durVolumeIndex = 0
            chapterInVolumeIndex = progress.durChapterIndex
        } else {
            val volumeIndex = volumes.indexOfLast { it.index < progress.durChapterIndex }
                .coerceAtLeast(0)
            durVolumeIndex = volumeIndex
            durVolume = volumes.getOrNull(volumeIndex)
            chapterInVolumeIndex = (progress.durChapterIndex - (durVolume?.index ?: -1) - 1)
                .coerceAtLeast(0)
        }
        durChapterPos = progress.durChapterPos
        upEpisodes()
        chapter = episodes?.getOrNull(chapterInVolumeIndex)
        videoTitle = chapter?.title ?: progress.durChapterTitle
        saveRead(progress.durChapterPos)
        postEvent(EventBus.UP_VIDEO_INFO, arrayListOf(1))
        player?.let {
            startPlay(it)
        }
    }

    fun uploadProgress(successAction: (() -> Unit)? = null) {
        book?.let {
            Coroutine.async {
                AppWebDav.uploadBookProgress(it) {
                    successAction?.invoke()
                }
                it.update()
            }
        }
    }

    fun syncProgress(
        newProgressAction: ((progress: BookProgress) -> Unit)? = null,
        uploadSuccessAction: (() -> Unit)? = null,
        syncSuccessAction: (() -> Unit)? = null,
    ) {
        if (!AppConfig.syncBookProgress) return
        val book = book ?: return
        Coroutine.async {
            AppWebDav.getBookProgress(book)
        }.onSuccess { progress ->
            when (progress?.compareWith(book)) {
                null,
                BookProgressComparison.LOCAL_NEWER -> {
                    Coroutine.async {
                        AppWebDav.uploadBookProgress(BookProgress(book), uploadSuccessAction)
                        book.update()
                    }
                }
                BookProgressComparison.REMOTE_NEWER -> {
                    newProgressAction?.invoke(progress)
                }
                BookProgressComparison.SAME -> {
                    syncSuccessAction?.invoke()
                }
            }
        }
    }

    fun saveRead(durPos: Int? = null) {
        val book = book
        val rssStar = rssStar
        val rssRecord = rssRecord
        val durPos = durPos ?: videoManager.currentPosition.toInt()
        durChapterPos = durPos
        if (book == null && rssStar == null && rssRecord == null) {
            videoUrl?.let { videoUrl ->
                CacheManager.put(VIDEO_POS_NAME + videoUrl, durPos, VIDEO_POS_SAVE_TIME)
            }
            return
        }
        val durVolumeIndex = durVolumeIndex
        val chapterInVolumeIndex = chapterInVolumeIndex
        val source = source
        val volumes = volumes.toList()
        val durVolume = durVolume
        val toc = toc
        Coroutine.async {
            book?.let { book ->
                book.lastCheckCount = 0
                val durTime = System.currentTimeMillis()
                book.durChapterTime = durTime
                book.durVolumeIndex = durVolumeIndex
                book.chapterInVolumeIndex = chapterInVolumeIndex
                val durChapterIndex = if (volumes.isEmpty()) chapterInVolumeIndex else
                    (durVolume?.index ?: 0) + chapterInVolumeIndex + 1
                book.durChapterIndex = durChapterIndex
                book.durChapterPos = durPos
                val chapter = toc?.getOrNull(durChapterIndex)
                videoTitle = chapter?.title
                book.durChapterTitle = chapter?.title
                SourceCallBack.callBackBook(SourceCallBack.SAVE_READ, source as BookSource?, book, chapter, durTime.toString())
                book.update()
                appDb.readRecentBookDao.insert(ReadRecentBook(book.bookUrl, durTime))
                ReadRecordWidgetStore.updateRecentSnapshot(book, durTime)
            }
            rssStar?.let {
                it.durPos = durPos
                videoTitle = it.title
                appDb.rssStarDao.update(it)
            }
            rssRecord?.let {
                it.durPos = durPos
                videoTitle = it.title
                appDb.rssReadRecordDao.update(it)
            }
            postEvent(EventBus.VIDEO_SUB_TITLE, videoTitle ?: appCtx.getString(R.string.data_loading))
        }
    }

    fun getDisplayCover(): String? {
        return book?.getDisplayCover() ?: rssStar?.image ?: rssRecord?.image
    }
}
