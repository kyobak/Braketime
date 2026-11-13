package com.conditiondrive.core.engine

import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

sealed interface Effect
data class Say(val voice: VoiceId, val vars: VoiceVars = VoiceVars()) : Effect
data class AskCheckin(val voice: VoiceId, val promptId: Long, val vars: VoiceVars = VoiceVars()) : Effect
data class AskStop(val voice: VoiceId, val kind: PromptKind, val promptId: Long, val vars: VoiceVars = VoiceVars()) : Effect
data class StartGuidance(val auto: Boolean) : Effect   // auto=true: 응답 없이 시작(혈당)
data object CancelVoice : Effect                       // 재생 중인 음성과 듣기를 끊음
data object CancelPendingPrompts : Effect              // 큐의 프롬프트와 재검색을 끊음
data object OfferVentilation : Effect                  // 런타임이 지원·조건을 확인해 V27
data class Trip(val event: TripEvent) : Effect         // F8 로컬 기록

/** 상태나 음성이 바뀌지 않아도 남겨야 하는 사용자 조작. 사람이 판단을 푼 시각은 기록에 있어야 한다 */
@Serializable
enum class LogEvent { GLUCOSE_HOLD_RELEASED }

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TransitionEntry(
    val atMs: Long,
    val from: DriverState,
    val to: DriverState,
    val reasons: Set<Reason>,
    val voices: List<VoiceId>,
    // 기록 파일은 기본값까지 쓴다. 대부분의 줄에 "event":null이 붙지 않게 비었을 때는 쓰지 않는다
    @EncodeDefault(EncodeDefault.Mode.NEVER) val event: LogEvent? = null,
)

data class Decision(
    val state: EngineState,
    val effects: List<Effect>,
    val log: TransitionEntry?,           // 상태 변화, 음성, 기록할 조작(LogEvent)이 있을 때만
)
