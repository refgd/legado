package io.legado.app.model

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.annotation.Keep
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.CacheManager
import io.legado.app.help.DefaultData
import io.legado.app.help.config.AppConfig
import io.legado.app.help.glide.BlurTransformation
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.RustImageModelLoader
import io.legado.app.model.webBook.RustAnalyzerBridge
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import splitties.init.appCtx
import java.io.File
import androidx.core.graphics.drawable.toDrawable

@Keep
@Suppress("ConstPropertyName")
object BookCover {

    private const val coverRuleConfigKey = "legadoCoverRuleConfig"
    const val configFileName = "coverRule.json"

    var drawBookName = true
        private set
    var drawBookAuthor = true
        private set
    lateinit var defaultDrawable: Drawable
        private set


    init {
        upDefaultCover()
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    fun upDefaultCover() {
        var path: String?
        val isNightTheme = AppConfig.isNightTheme
        if (isNightTheme) {
            drawBookName = appCtx.getPrefBoolean(PreferKey.coverShowNameN, true)
            drawBookAuthor = appCtx.getPrefBoolean(PreferKey.coverShowAuthorN, true)
            path = appCtx.getPrefString(PreferKey.defaultCoverDark)
        } else {
            drawBookName = appCtx.getPrefBoolean(PreferKey.coverShowName, true)
            drawBookAuthor = appCtx.getPrefBoolean(PreferKey.coverShowAuthor, true)
            path = appCtx.getPrefString(PreferKey.defaultCover)
        }
        defaultDrawable = runCatching {
            BitmapUtils.decodeBitmap(path!!, 600, 800)!!.toDrawable(appCtx.resources)
        }.getOrDefault(appCtx.resources.getDrawable(R.drawable.image_cover_default, null))
    }

    /**
     * 加载封面
     */
    fun load(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        onLoadFinish: (() -> Unit)? = null,
    ): RequestBuilder<Drawable> {
        if (AppConfig.useDefaultCover) {
            return ImageLoader.load(context, defaultDrawable)
                .centerCrop()
        }
        var options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .disallowHardwareConfig()
            .set(RustImageModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(RustImageModelLoader.sourceOriginOption, sourceOrigin)
        }
        var builder = ImageLoader.load(context, path)
            .apply(options)
        if (onLoadFinish != null) {
            builder = builder.addListener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable?>,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable?>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    onLoadFinish.invoke()
                    return false
                }
            })
        }
        return builder.placeholder(defaultDrawable)
            .error(defaultDrawable)
            .centerCrop()
    }

    /**
     * 加载漫画图片
     */
    fun loadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        transformation: Transformation<Bitmap>? = null,
    ): RequestBuilder<Drawable> {
        var options = RequestOptions().set(RustImageModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(RustImageModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(RustImageModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(context, path)
            .apply(options)
            .override(context.resources.displayMetrics.widthPixels, SIZE_ORIGINAL)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .skipMemoryCache(true).let {
                if (transformation != null) {
                    it.transform(transformation)
                } else {
                    it
                }
            }
    }

    fun preloadManga(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
    ): RequestBuilder<File?> {
        var options = RequestOptions().set(RustImageModelLoader.loadOnlyWifiOption, loadOnlyWifi)
            .set(RustImageModelLoader.mangaOption, true)
        if (sourceOrigin != null) {
            options = options.set(RustImageModelLoader.sourceOriginOption, sourceOrigin)
        }
        return Glide.with(context)
            .downloadOnly()
            .apply(options)
            .load(path)
    }

    /**
     * 加载模糊封面
     */
    fun loadBlur(
        context: Context,
        path: String?,
        loadOnlyWifi: Boolean = false,
        sourceOrigin: String? = null,
        blurRadius: Int = 12,
    ): RequestBuilder<Drawable> {
        val radius = blurRadius.coerceIn(0, 25)
        val loadBlur = ImageLoader.load(context, defaultDrawable).run {
            if (radius > 0) transform(BlurTransformation(radius), CenterCrop()) else transform(CenterCrop())
        }
        if (AppConfig.useDefaultCover) {
            return loadBlur
        }
        var options = RequestOptions()
            .format(DecodeFormat.PREFER_ARGB_8888)
            .disallowHardwareConfig()
            .set(RustImageModelLoader.loadOnlyWifiOption, loadOnlyWifi)
        if (sourceOrigin != null) {
            options = options.set(RustImageModelLoader.sourceOriginOption, sourceOrigin)
        }
        return ImageLoader.load(context, path)
            .apply(options)
            .run {
                if (radius > 0) transform(BlurTransformation(radius), CenterCrop()) else transform(CenterCrop())
            }
            .transition(DrawableTransitionOptions.withCrossFade(300))
            .thumbnail(loadBlur)
    }

    fun getCoverRule(): CoverRule {
        return getConfig() ?: DefaultData.coverRule
    }

    fun getConfig(): CoverRule? {
        val json = CacheManager.get(coverRuleConfigKey)
        if (json.isNullOrBlank()) {
            return null
        }
        return GSON.fromJsonObject<CoverRule>(json).getOrElse {
            throw NoStackTraceException(
                "BookCover rule JSON is invalid for Rust analyzer handoff: ${it.localizedMessage}"
            )
        }
    }

    suspend fun searchCover(book: Book): String? {
        val config = getCoverRule()
        if (!config.enable || config.searchUrl.isBlank() || config.coverRule.isBlank()) {
            return null
        }
        return RustAnalyzerBridge.coverSearch(config, config.searchUrl, config.coverRule, book)
    }

    fun saveCoverRule(config: CoverRule) {
        val json = GSON.toJson(config)
        saveCoverRule(json)
    }

    fun saveCoverRule(json: String) {
        CacheManager.put(coverRuleConfigKey, json)
    }

    fun delCoverRule() {
        CacheManager.delete(coverRuleConfigKey)
    }

    @Keep
    data class CoverRule(
        var enable: Boolean = true,
        var searchUrl: String,
        var coverRule: String,
        override var concurrentRate: String? = null,
        override var loginUrl: String? = null,
        override var loginUi: String? = null,
        override var header: String? = null,
        override var jsLib: String? = null,
        override var enabledCookieJar: Boolean? = false,
    ) : BaseSource {

        override fun getTag(): String {
            return "CoverRule"
        }

        override fun getKey(): String {
            return searchUrl
        }
    }

}
