package com.conditiondrive.vehicle.platform

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

internal enum class FocusPurpose { SPEAK, LISTEN }
internal enum class FocusLoss { TRANSIENT, PERMANENT }

/** 6.3 포커스. 앱이 직접 잡는다. 실측: Pleos Speech Service는 재생 중 포커스를 잡지 않는다(USAGE_MEDIA 트랙, 스택 비어 있음) */
internal interface FocusPort {
    fun acquire(purpose: FocusPurpose, onLoss: (FocusLoss) -> Unit): Boolean
    fun release()
}

internal class AndroidAudioFocus(context: Context) : FocusPort {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var current: AudioFocusRequest? = null

    @Synchronized
    override fun acquire(purpose: FocusPurpose, onLoss: (FocusLoss) -> Unit): Boolean {
        release()
        val manager = audio ?: return false
        return guarded("focus request", false) {
            val request = AudioFocusRequest.Builder(gain(purpose))
                .setAudioAttributes(attributes(purpose))
                .setOnAudioFocusChangeListener({ change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS -> onLoss(FocusLoss.PERMANENT)
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> onLoss(FocusLoss.TRANSIENT)
                        // 덕킹 요청은 받아도 그대로 말한다. 우리가 낮출 대상이 아니다
                        else -> Unit
                    }
                }, handler)
                .build()
            val granted = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (granted) current = request
            granted
        }
    }

    @Synchronized
    override fun release() {
        val request = current ?: return
        current = null
        guarded("focus abandon", Unit) { audio?.abandonAudioFocusRequest(request) }
    }

    private fun gain(purpose: FocusPurpose) = when (purpose) {
        FocusPurpose.SPEAK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        FocusPurpose.LISTEN -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    }

    private fun attributes(purpose: FocusPurpose): AudioAttributes = when (purpose) {
        FocusPurpose.SPEAK -> AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        FocusPurpose.LISTEN -> AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** 마이크가 열렸다는 짧은 신호음. 준비 전에 대답하면 사라지므로(6.3) 언제 말할지 소리로 알린다. 내장 소리라 파일이 없다 */
internal class ListenCue {
    private var tone: ToneGenerator? = null

    fun play() {
        guarded("listen cue", Unit) {
            val generator = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, VOLUME).also { tone = it }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS)
        }
    }

    private companion object {
        const val VOLUME = 60
        const val DURATION_MS = 120
    }
}
