package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.fragment.app.DialogFragment
import io.legado.app.R
import io.legado.app.constant.Bus
import io.legado.app.constant.Status
import io.legado.app.help.ReadAloud
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.book.read.Help
import io.legado.app.utils.*
import kotlinx.android.synthetic.main.dialog_read_aloud.*
import org.jetbrains.anko.sdk27.listeners.onClick
import org.jetbrains.anko.sdk27.listeners.onLongClick

class ReadAloudDialog : DialogFragment() {
    var callBack: CallBack? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        callBack = activity as? CallBack
        return inflater.inflate(R.layout.dialog_read_aloud, container)
    }

    override fun onStart() {
        super.onStart()
        val dm = DisplayMetrics()
        activity?.let {
            Help.upSystemUiVisibility(it)
            it.windowManager?.defaultDisplay?.getMetrics(dm)
        }
        dialog?.window?.let {
            it.setBackgroundDrawableResource(R.color.transparent)
            it.decorView.setPadding(0, 0, 0, 0)
            val attr = it.attributes
            attr.dimAmount = 0.0f
            attr.gravity = Gravity.BOTTOM
            it.attributes = attr
            it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initData()
        initOnChange()
        initOnClick()
    }

    private fun initData() {
        observeEvent<Int>(Bus.ALOUD_STATE) { upPlayState(it) }
        observeEvent<Int>(Bus.TTS_DS) { seek_timer.progress = it }
        callBack?.readAloudStatus?.let {
            upPlayState(it)
        }
        seek_timer.progress = BaseReadAloudService.timeMinute
        tv_timer.text =
            requireContext().getString(R.string.timer_m, BaseReadAloudService.timeMinute)
        cb_tts_follow_sys.isChecked = requireContext().getPrefBoolean("ttsFollowSys", true)
        seek_tts_SpeechRate.isEnabled = !cb_tts_follow_sys.isChecked
        seek_tts_SpeechRate.progress = requireContext().getPrefInt("ttsSpeechRate", 5)
    }

    private fun initOnChange() {
        cb_tts_follow_sys.setOnCheckedChangeListener { buttonView, isChecked ->
            if (buttonView.isPressed) {
                requireContext().putPrefBoolean("ttsFollowSys", isChecked)
                seek_tts_SpeechRate.isEnabled = !isChecked
                upTtsSpeechRate()
            }
        }
        seek_tts_SpeechRate.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                requireContext().putPrefInt("ttsSpeechRate", seek_tts_SpeechRate.progress)
                upTtsSpeechRate()
            }

        })
        seek_timer.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tv_timer.text = requireContext().getString(R.string.timer_m, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                ReadAloud.setTimer(requireContext(), seek_timer.progress)
            }
        })
    }

    private fun initOnClick() {
        iv_menu.onClick { callBack?.showMenu(); dismiss() }
        iv_other_config.onClick {
            ReadAloudConfigDialog().show(childFragmentManager, "readAloudConfigDialog")
        }
        iv_menu.onLongClick { callBack?.openChapterList(); true }
        iv_stop.onClick { ReadAloud.stop(requireContext()); dismiss() }
        iv_play_pause.onClick { postEvent(Bus.READ_ALOUD_BUTTON, true) }
        iv_play_prev.onClick { ReadAloud.prevParagraph(requireContext()) }
        iv_play_prev.onLongClick { postEvent(Bus.TTS_TURN_PAGE, -2); true }
        iv_play_next.onClick { ReadAloud.nextParagraph(requireContext()) }
        iv_play_next.onLongClick { postEvent(Bus.TTS_TURN_PAGE, 2); true }
    }

    private fun upPlayState(state: Int) {
        if (state == Status.PLAY) {
            iv_play_pause.setImageResource(R.drawable.ic_pause_24dp)
        } else {
            iv_play_pause.setImageResource(R.drawable.ic_play_24dp)
        }
    }

    private fun upTtsSpeechRate() {
        ReadAloud.upTtsSpeechRate(requireContext())
        if (callBack?.readAloudStatus == Status.PLAY) {
            ReadAloud.pause(requireContext())
            ReadAloud.resume(requireContext())
        }
    }

    interface CallBack {
        fun showMenu()
        fun openChapterList()
        var readAloudStatus: Int
    }
}