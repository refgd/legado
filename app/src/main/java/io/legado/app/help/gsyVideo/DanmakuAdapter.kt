package io.legado.app.help.gsyVideo

import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ImageSpan
import androidx.core.graphics.toColorInt
import io.legado.app.model.webBook.RustAnalyzerBridge
import master.flame.danmaku.controller.IDanmakuView
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.android.BaseCacheStuffer
import java.io.ByteArrayInputStream

class DanmakuAdapter(private val mDanmakuView: IDanmakuView?) : BaseCacheStuffer.Proxy() {
    private var mDrawable: Drawable? = null

    companion object {
        private const val INLINE_ICON_URL = "http://www.bilibili.com/favicon.ico"

        fun fetchInlineIconBytes(url: String = INLINE_ICON_URL): ByteArray {
            return RustAnalyzerBridge.fetchRawUrl(url, "DanmakuAdapter.inlineIcon").body
        }
    }

    override fun prepareDrawing(danmaku: BaseDanmaku, fromWorkerThread: Boolean) {
        if (danmaku.text is Spanned) { // 根据你的条件检查是否需要需要更新弹幕
            // FIXME 这里只是简单启个线程来加载远程url图片，请使用你自己的异步线程池，最好加上你的缓存池
            object : Thread() {
                override fun run() {
                    var drawable = mDrawable
                    if (drawable == null) {
                        try {
                            val bytes = fetchInlineIconBytes()
                            drawable = BitmapDrawable.createFromStream(
                                ByteArrayInputStream(bytes),
                                "bitmap"
                            )
                            mDrawable = drawable
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    if (drawable != null) {
                        drawable.setBounds(0, 0, 100, 100)
                        val spannable = createSpannable(drawable)
                        danmaku.text = spannable
                        mDanmakuView?.invalidateDanmaku(danmaku, false)
                        return
                    }
                }
            }.start()
        }
    }


    override fun releaseResource(danmaku: BaseDanmaku?) {
        // TODO 重要:清理含有ImageSpan的text中的一些占用内存的资源 例如drawable
    }


    private fun createSpannable(drawable: Drawable): SpannableStringBuilder {
        val text = "bitmap"
        val spannableStringBuilder = SpannableStringBuilder(text)
        val span = ImageSpan(drawable) //ImageSpan.ALIGN_BOTTOM);
        spannableStringBuilder.setSpan(span, 0, text.length, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
        spannableStringBuilder.append("图文混排")
        spannableStringBuilder.setSpan(
            BackgroundColorSpan("#8A2233B1".toColorInt()),
            0,
            spannableStringBuilder.length,
            Spannable.SPAN_INCLUSIVE_INCLUSIVE
        )
        return spannableStringBuilder
    }
}
