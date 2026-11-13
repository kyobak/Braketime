package com.conditiondrive.core.engine

sealed interface EngineEvent { val atMs: Long }
data class Tick(override val atMs: Long) : EngineEvent
data class VehicleChanged(override val atMs: Long, val gear: Gear, val speedKmh: Double?, val ignition: Ignition) : EngineEvent
data class ConditionReceived(override val atMs: Long, val input: ConditionInput?, val unpaired: Boolean = false) : EngineEvent
data class RouteChanged(override val atMs: Long, val remainingSec: Int?) : EngineEvent
data class CheckinHeard(override val atMs: Long, val promptId: Long, val answer: CheckinAnswer) : EngineEvent
data class PromptHeard(override val atMs: Long, val promptId: Long, val answer: PromptAnswer) : EngineEvent
data class WaypointArrived(override val atMs: Long) : EngineEvent
data class ProcessRestored(override val atMs: Long, val gap: GapEvidence, val rebooted: Boolean = false) : EngineEvent
/** 온보딩이 끝나기 전에는 V01을 말하지 않고 그날의 체크인도 쓰지 않는다. hold=false가 오면 조건을 다시 본다 */
data class CheckinHold(override val atMs: Long, val hold: Boolean) : EngineEvent
/**
 * E34. S5 "보류 풀기"(U-30). 값을 받은 뒤 끊긴 혈당 보류를 차에서 푼다. 효과는 E33의 혈당 몫과 같고 수면·앞당김은 그대로다.
 * 정차 중이고 지금 유효한 값이 없을 때만 받는다. 실제로 낮은 값은 사용자가 풀 수 없다(규칙 5).
 */
data class GlucoseHoldReleased(override val atMs: Long) : EngineEvent
