package com.conditiondrive.vehicle.service

import com.conditiondrive.core.voice.PrivacyContext
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceClassifier
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoicePriority
import com.conditiondrive.core.voice.VoiceVars
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.ListenerPort
import com.conditiondrive.vehicle.port.SpeakResult
import com.conditiondrive.vehicle.port.SpeakerPort
import com.conditiondrive.vehicle.ui.screens.VoiceTrial
import kotlinx.coroutines.delay

/**
 * O3 듣기 확인. 엔진 없이 음성 경로만 시험한다. 화면에 적힌 문장(V03)과 같아야 한다.
 * 들을 수 없으면 묻지 않는다(R-02). 주행 중 체크인과 같은 규칙이다.
 */
internal class VoiceTrialRunner(
    private val speaker: SpeakerPort,
    private val listener: ListenerPort,
    private val listenDelayMs: Long = LISTEN_DELAY_MS,
) {
    suspend fun run(onListening: () -> Unit): VoiceTrial {
        if (!listener.ready()) return VoiceTrial.Unavailable
        val text = VoiceCatalog.render(VoiceId.V03, VoiceVars(), PrivacyContext(passengerPresentOrUnknown = true))
        if (speaker.speak(text, VoicePriority.INFO) != SpeakResult.DONE) return VoiceTrial.Heard(null)
        delay(listenDelayMs)
        return when (val heard = listener.listen(onReady = onListening)) {
            is ListenResult.Heard -> VoiceTrial.Heard(VoiceClassifier.checkin(heard.text))
            ListenResult.NoSpeech, is ListenResult.Failed -> VoiceTrial.Heard(null)
        }
    }

    private companion object {
        const val LISTEN_DELAY_MS = 300L
    }
}
