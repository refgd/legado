package io.legado.app.service

import android.app.PendingIntent
import android.content.*
import android.graphics.BitmapFactory
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.annotation.CallSuper
import androidx.core.app.NotificationCompat
import io.legado.app.R
import io.legado.app.base.BaseService
import io.legado.app.constant.Action
import io.legado.app.constant.AppConst
import io.legado.app.constant.Bus
import io.legado.app.constant.Status
import io.legado.app.help.IntentDataHelp
import io.legado.app.help.MediaHelp
import io.legado.app.help.PendingIntentHelp
import io.legado.app.receiver.MediaButtonReceiver
import io.legado.app.ui.widget.page.TextChapter
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent

abstract class BaseReadAloudService : BaseService(),
    AudioManager.OnAudioFocusChangeListener {

    companion object {
        var isRun = false
        var timeMinute: Int = 0

        fun play(
            context: Context,
            title: String,
            subtitle: String,
            pageIndex: Int,
            dataKey: String,
            play: Boolean = true
        ) {
            val readAloudIntent = Intent(context, TTSReadAloudService::class.java)
            readAloudIntent.action = Action.play
            readAloudIntent.putExtra("title", title)
            readAloudIntent.putExtra("subtitle", subtitle)
            readAloudIntent.putExtra("pageIndex", pageIndex)
            readAloudIntent.putExtra("dataKey", dataKey)
            readAloudIntent.putExtra("play", play)
            context.startService(readAloudIntent)
        }

        fun pause(context: Context) {
            if (isRun) {
                val intent = Intent(context, TTSReadAloudService::class.java)
                intent.action = Action.pause
                context.startService(intent)
            }
        }

        fun resume(context: Context) {
            if (isRun) {
                val intent = Intent(context, TTSReadAloudService::class.java)
                intent.action = Action.resume
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            if (isRun) {
                val intent = Intent(context, TTSReadAloudService::class.java)
                intent.action = Action.stop
                context.startService(intent)
            }
        }

        fun prevParagraph(context: Context) {
            if (isRun) {
                val intent = Intent(context, TTSReadAloudService::class.java)
                intent.action = Action.prevParagraph
                context.startService(intent)
            }
        }

        fun nextParagraph(context: Context) {
            if (isRun) {
                val intent = Intent(context, TTSReadAloudService::class.java)
                intent.action = Action.nextParagraph
                context.startService(intent)
            }
        }

        fun upTtsSpeechRate(context: Context) {
            if (isRun) {
                val intent = Intent(context, TTSReadAloudService::class.java)
                intent.action = Action.upTtsSpeechRate
                context.startService(intent)
            }
        }

        fun setTimer(context: Context, minute: Int) {
            if (isRun) {
                val intent = Intent(context, TTSReadAloudService::class.java)
                intent.action = Action.setTimer
                intent.putExtra("minute", minute)
                context.startService(intent)
            }
        }
    }

    private val handler = Handler()
    private lateinit var audioManager: AudioManager
    private lateinit var mFocusRequest: AudioFocusRequest
    private var broadcastReceiver: BroadcastReceiver? = null
    private var mediaSessionCompat: MediaSessionCompat? = null
    private var pause = false
    var title: String = ""
    private var subtitle: String = ""
    val contentList = arrayListOf<String>()
    var nowSpeak: Int = 0
    var readAloudNumber: Int = 0
    var textChapter: TextChapter? = null
    var pageIndex = 0
    private val dsRunnable: Runnable? = Runnable { doDs() }

    override fun onCreate() {
        super.onCreate()
        isRun = true
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mFocusRequest = MediaHelp.getFocusRequest(this)
        }
        initMediaSession()
        initBroadcastReceiver()
        upNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRun = false
        unregisterReceiver(broadcastReceiver)
        postEvent(Bus.ALOUD_STATE, Status.STOP)
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_STOPPED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                Action.play -> {
                    title = intent.getStringExtra("title") ?: ""
                    subtitle = intent.getStringExtra("subtitle") ?: ""
                    pageIndex = intent.getIntExtra("pageIndex", 0)
                    newReadAloud(
                        intent.getStringExtra("dataKey"),
                        intent.getBooleanExtra("play", true)
                    )
                }
                Action.pause -> pauseReadAloud(true)
                Action.resume -> resumeReadAloud()
                Action.upTtsSpeechRate -> upSpeechRate(true)
                Action.prevParagraph -> prevP()
                Action.nextParagraph -> nextP()
                Action.addTimer -> addTimer()
                Action.setTimer -> setTimer(intent.getIntExtra("minute", 0))
                else -> stopSelf()
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun newReadAloud(dataKey: String?, play: Boolean) {
        dataKey?.let {
            textChapter = IntentDataHelp.getData(dataKey) as? TextChapter
            textChapter?.let { textChapter ->
                nowSpeak = 0
                readAloudNumber = textChapter.getReadLength(pageIndex)
                contentList.clear()
                if (getPrefBoolean("readAloudByPage")) {
                    for (index in pageIndex..textChapter.lastIndex()) {
                        textChapter.page(index)?.text?.split("\n")?.let {
                            contentList.addAll(it)
                        }
                    }
                } else {
                    contentList.addAll(textChapter.getUnRead(pageIndex).split("\n"))
                }
                if (play) play()
            } ?: stopSelf()
        } ?: stopSelf()
    }

    open fun play() {
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PLAYING)
        postEvent(Bus.ALOUD_STATE, Status.PLAY)
        upNotification()
    }

    @CallSuper
    open fun pauseReadAloud(pause: Boolean) {
        postEvent(Bus.ALOUD_STATE, Status.PAUSE)
        this.pause = pause
        upNotification()
        upMediaSessionPlaybackState(PlaybackStateCompat.STATE_PAUSED)
    }

    @CallSuper
    open fun resumeReadAloud() {
        pause = false
    }

    abstract fun upSpeechRate(reset: Boolean = false)

    abstract fun prevP()

    abstract fun nextP()

    private fun setTimer(minute: Int) {
        timeMinute = minute
        if (minute > 0) {
            handler.removeCallbacks(dsRunnable)
            handler.postDelayed(dsRunnable, 60000)
        }
        upNotification()
    }

    private fun addTimer() {
        if (timeMinute == 60) {
            timeMinute = 0
            handler.removeCallbacks(dsRunnable)
        } else {
            timeMinute += 10
            if (timeMinute > 60) timeMinute = 60
            handler.removeCallbacks(dsRunnable)
            handler.postDelayed(dsRunnable, 60000)
        }
        postEvent(Bus.TTS_DS, timeMinute)
        upNotification()
    }

    /**
     * 定时
     */
    private fun doDs() {
        if (!pause) {
            timeMinute--
            if (timeMinute == 0) {
                stopSelf()
            } else if (timeMinute > 0) {
                handler.postDelayed(dsRunnable, 60000)
            }
        }
        postEvent(Bus.TTS_DS, timeMinute)
        upNotification()
    }


    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // 重新获得焦点,  可做恢复播放，恢复后台音量的操作
                if (!pause) resumeReadAloud()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久丢失焦点除非重新主动获取，这种情况是被其他播放器抢去了焦点，  为避免与其他播放器混音，可将音乐暂停
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // 暂时丢失焦点，这种情况是被其他应用申请了短暂的焦点，可压低后台音量
                if (!pause) pauseReadAloud(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // 短暂丢失焦点，这种情况是被其他应用申请了短暂的焦点希望其他声音能压低音量（或者关闭声音）凸显这个声音（比如短信提示音），
            }
        }
    }

    /**
     * @return 音频焦点
     */
    fun requestFocus(): Boolean {
        MediaHelp.playSilentSound(this)
        val request: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.requestAudioFocus(mFocusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                this,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return request == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun upMediaSessionPlaybackState(state: Int) {
        mediaSessionCompat?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(MediaHelp.MEDIA_SESSION_ACTIONS)
                .setState(state, nowSpeak.toLong(), 1f)
                .build()
        )
    }

    /**
     * 初始化MediaSession, 注册多媒体按钮
     */
    private fun initMediaSession() {
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = ComponentName(packageName, MediaButtonReceiver::class.java.name)
        }
        val mediaButtonReceiverPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_CANCEL_CURRENT
        )

        mediaSessionCompat = MediaSessionCompat(this, TTSReadAloudService.tag)
        mediaSessionCompat?.setCallback(object : MediaSessionCompat.Callback() {
            override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
                return MediaButtonReceiver.handleIntent(this@BaseReadAloudService, mediaButtonEvent)
            }
        })
        mediaSessionCompat?.setMediaButtonReceiver(mediaButtonReceiverPendingIntent)
        mediaSessionCompat?.isActive = true
    }

    /**
     * 断开耳机监听
     */
    private fun initBroadcastReceiver() {
        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                    pauseReadAloud(true)
                }
            }
        }
        val intentFilter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        registerReceiver(broadcastReceiver, intentFilter)
    }

    /**
     * 更新通知
     */
    private fun upNotification() {
        var nTitle: String = when {
            pause -> getString(R.string.read_aloud_pause)
            timeMinute in 1..60 -> getString(
                R.string.read_aloud_timer,
                timeMinute
            )
            else -> getString(R.string.read_aloud_t)
        }
        nTitle += ": $title"
        var nSubtitle = subtitle
        if (subtitle.isEmpty())
            nSubtitle = getString(R.string.read_aloud_s)
        val builder = NotificationCompat.Builder(this, AppConst.channelIdReadAloud)
            .setSmallIcon(R.drawable.ic_volume_up)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.icon_read_book))
            .setOngoing(true)
            .setContentTitle(nTitle)
            .setContentText(nSubtitle)
            .setContentIntent(PendingIntentHelp.readBookActivityPendingIntent(this))
        if (pause) {
            builder.addAction(
                R.drawable.ic_play_24dp,
                getString(R.string.resume),
                PendingIntentHelp.aloudServicePendingIntent(this, Action.resume)
            )
        } else {
            builder.addAction(
                R.drawable.ic_pause_24dp,
                getString(R.string.pause),
                PendingIntentHelp.aloudServicePendingIntent(this, Action.pause)
            )
        }
        builder.addAction(
            R.drawable.ic_stop_black_24dp,
            getString(R.string.stop),
            PendingIntentHelp.aloudServicePendingIntent(this, Action.stop)
        )
        builder.addAction(
            R.drawable.ic_time_add_24dp,
            getString(R.string.set_timer),
            PendingIntentHelp.aloudServicePendingIntent(this, Action.addTimer)
        )
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(mediaSessionCompat?.sessionToken)
                .setShowActionsInCompactView(0, 1, 2)
        )
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        val notification = builder.build()
        startForeground(112201, notification)
    }
}