package com.conditiondrive.core.engine

import kotlinx.serialization.Serializable

/** 외부는 불투명하게 다룬다. 필드 구성이 바뀌면 schemaVersion을 올린다. */
@Serializable
data class EngineState(
    val schemaVersion: Int = SCHEMA_VERSION,
    val lastEventMs: Long,
    val gear: Gear = Gear.UNKNOWN,
    val speedKmh: Double? = null,
    val ignition: Ignition = Ignition.UNKNOWN,
    val driveMs: Long = 0,
    val restStartMs: Long? = null,
    val departPendingSinceMs: Long? = null,
    val departPendingDriveMs: Long? = null,
    val fatigue: Level = Level.NONE,
    val checkin: Level = Level.NONE,
    val glucose: Level = Level.NONE,
    val glucoseTimerStartMs: Long? = null,
    val glucoseLatched: Boolean = false,
    val glucoseLastValidMs: Long? = null,
    val glucoseLastMeasuredMs: Long? = null,
    val glucoseLastTrend: Trend? = null,
    val glucoseCautionClearReads: Int = 0,
    val glucoseCautionEscalated: Boolean = false,
    val glucoseLostAnnouncedAtMs: Long? = null,
    val glucoseValueAtLast: Double? = null,
    val departedThisIgnition: Boolean = false,
    val tiredStreak: Int = 0,
    val checkinRestDone: Boolean = false,
    val nextCheckinDriveMs: Long? = null,
    val checkinAskedDay: Long? = null,
    val checkinDueAtMs: Long? = null,
    val deferredCheckin: Boolean = false,
    val nightCheckinDone: Boolean = false,
    val ignoredCount: Int = 0,
    val muted: Boolean = false,
    val nextPromptAtMs: Long? = null,
    val promptPausedUntilMs: Long? = null,
    val nextGlucoseRepeatAtMs: Long? = null,
    val advanceLatched: Boolean = false,
    val sleepMinutes: Int? = null,
    val sleepExpiresAtMs: Long? = null,
    val remainingRouteSec: Int? = null,
    val promptSeq: Long = 0,
    val activePromptId: Long? = null,
    val activePromptKind: PromptKind? = null,
    val promptRetried: Boolean = false,
    val ventilationOffered: Boolean = false,
    val autoGuidanceDone: Boolean = false,
    val measuredSinceAppStart: Boolean = false,
    // 아래는 9.1 목록에 없던 값이다. 재시작 뒤에도 만료·재무장·재질문 판단을 이어야 해서 저장한다.
    val glucoseExpiresAtMs: Long? = null,
    val glucoseSimulated: Boolean = false,
    val glucoseLostAtMs: Long? = null,
    val glucoseValidSinceMs: Long? = null,
    val glucoseCautionAnnounced: Boolean = false,
    val remainingRouteAtMs: Long? = null,
    val activePromptPostRest: Boolean = false,
    val restartNoticePending: Boolean = false,
    val lastCheckin: CheckinAnswer? = null,
    val checkinHeld: Boolean = false,
    // D-31. 래치가 G=CAUTION에서 왔는지. STOP 래치의 복귀 처리(4.0~5.0을 STOP으로)를 주의에서 온 값에 쓰면 안 된다.
    // 기본값이 옛 저장본의 뜻(STOP 래치)과 같아 schemaVersion을 올리지 않는다
    val glucoseLatchFromCaution: Boolean = false,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 2
    }
}
