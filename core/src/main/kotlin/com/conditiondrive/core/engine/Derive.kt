package com.conditiondrive.core.engine

/** 3.1 파생 공식. 저장하지 않고 트랙에서 매번 계산해야 "정차 권고 > 주의"가 분기 없이 성립한다. */
internal fun EngineState.isHardParked(): Boolean = gear == Gear.PARK || ignition == Ignition.OFF

internal fun EngineState.isFallbackParked(): Boolean =
    gear == Gear.UNKNOWN && ignition != Ignition.OFF && speedKmh != null && speedKmh <= 0.0

internal fun EngineState.isNoSignal(): Boolean = gear == Gear.UNKNOWN && speedKmh == null && ignition != Ignition.OFF

internal fun EngineState.isParked(): Boolean =
    isHardParked() || departPendingSinceMs != null || isFallbackParked()

/** 신호가 전혀 없으면 운전이라 단정하지 않는다(3.2 마지막 행). */
internal fun EngineState.isDriving(): Boolean = !isParked() && !isNoSignal()

/** 대체 정차는 휴식과 운전이 함께 늘어난다(3.2). 유예 중에는 확정 때 소급한다. */
internal fun EngineState.accruesDrive(): Boolean =
    !isHardParked() && departPendingSinceMs == null && !isNoSignal()

internal fun EngineState.effectiveGlucose(): Level = if (glucoseLatched) Level.CAUTION else glucose

internal fun EngineState.level(): Level = maxOf(fatigue, checkin, effectiveGlucose())

internal fun EngineState.driverState(): DriverState = when (level()) {
    Level.NONE -> DriverState.NORMAL
    Level.CAUTION -> DriverState.CAUTION
    Level.STOP -> if (isParked()) DriverState.RECOVER else DriverState.STOP
}

internal fun EngineState.reasons(): Set<Reason> = buildSet {
    if (fatigue != Level.NONE) add(Reason.FATIGUE)
    if (checkin != Level.NONE) add(Reason.CHECKIN)
    if (glucose != Level.NONE) add(Reason.GLUCOSE)
    if (glucoseLatched) add(Reason.GLUCOSE_UNCONFIRMED)
}

/**
 * E34를 받는 가장 이른 시각(D-33). 실제 낮은 값(G=STOP)에서 온 보류는 마지막 판독에서 40분(C-25)을 기다린다.
 * 그 전에 풀면 회복 대기와 회복 기준 5.0을 사람이 건너뛴다(규칙 5). 값을 받은 적이 없으면 null이다.
 */
internal fun EngineState.holdReleaseFloorMs(p: Policy): Long? {
    val last = glucoseLastValidMs ?: return null
    val fromStop = glucose == Level.STOP || (glucoseLatched && !glucoseLatchFromCaution)
    return if (fromStop) last + p.GLUCOSE_WAIT_SEC * 1000 else last
}

internal fun EngineState.preDepartureHold(): Boolean = glucose == Level.STOP && !departedThisIgnition

internal fun EngineState.cautionThresholdSec(p: Policy): Long =
    if (advanceLatched) p.FATIGUE_CAUTION_SEC - p.ADVANCE_SEC else p.FATIGUE_CAUTION_SEC

internal fun EngineState.stopThresholdSec(p: Policy): Long =
    if (advanceLatched) p.FATIGUE_STOP_SEC - p.ADVANCE_SEC else p.FATIGUE_STOP_SEC

internal fun EngineState.isGlucoseLow(p: Policy): Boolean =
    glucose == Level.STOP && (glucoseValueAtLast ?: Double.MAX_VALUE) < p.GLUCOSE_STOP_BELOW

/** 경로 잔여는 갱신 사이에도 줄어든다. 이벤트가 드물어도 V22 판단이 늦지 않게 시각 차로 계산한다. */
internal fun EngineState.routeRemainingSec(nowMs: Long): Long? {
    val sec = remainingRouteSec ?: return null
    val since = remainingRouteAtMs ?: return sec.toLong()
    return (sec - (nowMs - since).coerceAtLeast(0) / 1000).coerceAtLeast(0)
}

internal fun ceilSec(ms: Long): Long = if (ms <= 0) 0 else (ms + 999) / 1000
