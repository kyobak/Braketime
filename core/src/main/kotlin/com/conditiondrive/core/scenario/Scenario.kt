package com.conditiondrive.core.scenario

import com.conditiondrive.core.engine.AdvanceReason
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.GapEvidence
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.LogEvent
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.voice.VoiceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 9.4의 시나리오 JSON. JVM 테스트와 시연이 같은 파일을 읽는다. */
@Serializable
data class Scenario(
    val id: String,
    val title: String,
    val startLocal: String,
    val keepGlucoseAlive: Boolean = false,
    val steps: List<ScenarioStep>,
)

@Serializable
data class ScenarioStep(
    val t: String,
    @SerialName("in") val input: ScenarioInput? = null,
    val expect: ScenarioExpect? = null,
)

@Serializable
data class ScenarioInput(
    val ignition: Ignition? = null,
    val gear: Gear? = null,
    val speedKmh: Double? = null,
    val checkinText: String? = null,
    val promptText: String? = null,
    val summary: ScenarioSummary? = null,
    val summaryNull: Boolean? = null,
    val routeRemainingSec: Int? = null,
    val waypointArrived: Boolean? = null,
    val killProcess: Boolean? = null,
    val restoreGap: GapEvidence? = null,
    val unpaired: Boolean? = null,
    val glucoseCut: Boolean? = null,        // 표의 "끊김". 이 줄부터 러너의 혈당 유지 입력을 멈춘다
    val glucoseHoldRelease: Boolean? = null, // 표의 "S5 보류 풀기"(E34). 요약 입력 뒤, 응답 앞에 넣는다
)

@Serializable
data class ScenarioSummary(
    val sleepMinutes: Int? = null,
    val glucoseMmol: Double? = null,
    val trend: Trend? = null,
    val source: String? = null,
    val ttlSec: Long? = null,               // 혈당 유효 시간. 없으면 C-32
    val sleepTtlSec: Long? = null,          // 수면 유효 시간. 없으면 하루
    val measuredAt: String? = null,         // 같은 측정 재수신(D24)을 표현하려는 오프셋. 없으면 이 줄의 시각
)

@Serializable
data class ScenarioExpect(
    val state: DriverState? = null,
    val reasons: Set<Reason>? = null,
    val cdsSec: Long? = null,
    val ignored: Int? = null,
    val hold: Boolean? = null,
    val voices: List<VoiceId>? = null,
    val guidanceAuto: Boolean? = null,
    val advanced: AdvanceReason? = null,
    // 아래는 9.4 목록에 없는 키다. 표의 괄호(음성 변수, 휴식, 타이머, 임계)까지 대조하려고 더했다.
    val voiceVars: List<ScenarioVoiceVars>? = null,
    val restElapsedSec: Long? = null,
    val timerRunning: Boolean? = null,
    val cautionSec: Long? = null,
    val stopSec: Long? = null,
    val streak: Int? = null,
    val ventilationOffer: Boolean? = null,
    // 전이 기록 줄의 사건(E34의 GLUCOSE_HOLD_RELEASED). 음성처럼 생략하면 없어야 한다
    val events: List<LogEvent>? = null,
    // S5 보류 풀기를 지금 받을 수 있는지(엔진 뷰 glucoseHoldReleasableAtMs가 지금 이전)
    val holdReleasable: Boolean? = null,
)

@Serializable
data class ScenarioVoiceVars(
    val voice: VoiceId,
    val driveSec: Long? = null,
    val remainingSec: Long? = null,
    val routeMinutes: Int? = null,
    val advance: AdvanceReason? = null,
    val noAdvance: Boolean? = null,
)

data class RunResult(val entries: List<TransitionEntry>, val failures: List<String>)
enum class SpeedProfile { REAL_TIME, X60, X240, RANDOM }
