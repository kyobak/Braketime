package com.conditiondrive.core.engine

/** UI와 런타임이 읽는 유일한 뷰. 화면은 EngineState를 직접 읽지 않는다. */
data class EngineView(
    val state: DriverState,
    val reasons: Set<Reason>,
    val continuousDriveSec: Long,
    val cautionThresholdSec: Long,
    val stopThresholdSec: Long,
    val advanced: AdvanceReason?,
    val remainingToRestSec: Long?,       // 정차 임계까지. 넘었으면 null
    val restElapsedSec: Long?,
    val restRemainingSec: Long?,
    val glucoseRecoveryRemainingSec: Long?,
    val preDepartureHold: Boolean,
    val ignoredCount: Int,
    val glucoseMmol: Double?,            // 유효할 때만. S2·S3 전용
    val glucoseTrend: Trend?,
    val glucoseMeasuredAtMs: Long?,
    val glucoseSimulated: Boolean,
    val glucoseUnavailable: Boolean,     // 유효했다가 끊김
    val sleepMinutes: Int?,
    val lastCheckin: CheckinAnswer?,
    val measuredSinceAppStart: Boolean,
    val driving: Boolean,                // 엔진 기준(출발 유예 중엔 false)
    val noDriveSignal: Boolean = false,  // 기어와 차속을 모두 읽지 못해 운전 시간을 세지 못한다
    // 끊김 보류를 S5에서 풀 수 있는 가장 이른 시각(D-33). 뷰는 사건 때만 다시 만들어지므로 남은 초가 아니라 시각으로 준다.
    // 화면이 지금 시각과 비교한다. 끊김이 아니면 null
    val glucoseHoldReleasableAtMs: Long? = null,
)
