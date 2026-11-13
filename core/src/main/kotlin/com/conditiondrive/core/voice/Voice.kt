package com.conditiondrive.core.voice

import com.conditiondrive.core.engine.AdvanceReason

enum class VoiceId {
    V01, V02, V03, V04, V04N, V04O, V05, V06, V06N, V06O, V08, V08F, V08N, V08FN, V08O, V08FO, V09, V09N, V09H, V10, V10N, V10U, V10H,
    V11, V12, V13, V14, V15, V16, V16C, V16D, V17, V18, V19, V19R, V20, V21, V21S, V22,
    V23, V24, V25, V26, V27, V27F, V28, V29, V30, V31,
}

data class VoiceVars(
    val driveSec: Long? = null,        // {운전 시간}
    val remainingSec: Long? = null,    // {남은 시간}
    val routeMinutes: Int? = null,     // V22 {분}
    val advance: AdvanceReason? = null,
    val distanceM: Int? = null,        // 런타임이 장소를 찾아 채움
    val distanceApprox: Boolean = false, // 경로로 재지 못한 직선 거리면 "약"을 붙인다
    val placeName: String? = null,
    // 런타임이 말하는 순간의 차속으로 채운다. 장소 없는 혈당 정차 안내를 나들목 변형으로 바꾸는 데만 쓴다(V-07)
    val highwayLikely: Boolean = false,
)

enum class VoicePriority { GLUCOSE_STOP, PROMPT, NOTICE, INFO }   // 6.3 ①②③④

data class PrivacyContext(val passengerPresentOrUnknown: Boolean)
