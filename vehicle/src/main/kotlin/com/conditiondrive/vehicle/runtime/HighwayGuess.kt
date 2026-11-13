package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.time.Clock

/** 고속도로·자동차전용도로일 가능성. 검색 범위와 장소 없는 혈당 정차 음성(V09H, V10H)이 같은 기준을 쓴다 */
internal const val HIGHWAY_LIKELY_KMH = 70.0

/** 이보다 느린 상태가 이어져야 고속도로를 벗어났다고 본다. 정체 구간의 30~60km/h는 고속도로에서 흔하다 */
internal const val HIGHWAY_SLOW_KMH = 30.0
internal const val HIGHWAY_RELEASE_MS = 5 * 60_000L

// 차속 하나만 볼 때의 판정. 히스테리시스 없이 쓰는 곳은 시험과 첫 판정뿐이다
internal fun highwayLikely(speedKmh: Double?): Boolean = (speedKmh ?: 0.0) >= HIGHWAY_LIKELY_KMH

/**
 * 고속 판정에 히스테리시스를 둔다(V-07). 말하는 순간 차속 하나로 고르면 고속도로가 막혀 69km/h가 되는 순간
 * 일반 도로 문구가 나가고, 70 언저리를 오가면 2분마다 V10H와 V10N이 번갈아 나와 지시가 바뀐다.
 * 70km/h 이상을 한 번 보면 고속으로 두고, 30km/h 미만이 5분 이어지거나 P·시동 꺼짐이면 푼다.
 * 차속을 모르면 마지막 판단을 둔다. 처음부터 모르면 일반 도로로 본다(일반 도로에서 나들목을 찾으라고 하면 정차가 늦어진다).
 */
class HighwayGuess(private val clock: Clock) {
    private var highway = false
    private var slowSinceMs: Long? = null

    @Synchronized
    fun onSpeed(kmh: Double?) {
        when {
            kmh == null -> Unit
            kmh >= HIGHWAY_LIKELY_KMH -> {
                highway = true
                slowSinceMs = null
            }
            kmh < HIGHWAY_SLOW_KMH -> if (slowSinceMs == null) slowSinceMs = clock.nowMs()
            else -> slowSinceMs = null
        }
    }

    @Synchronized
    fun onGear(gear: Gear) {
        if (gear == Gear.PARK) reset()
    }

    @Synchronized
    fun onIgnition(ignition: Ignition) {
        if (ignition == Ignition.OFF) reset()
    }

    @Synchronized
    fun likely(): Boolean {
        val slow = slowSinceMs
        if (highway && slow != null && clock.nowMs() - slow >= HIGHWAY_RELEASE_MS) reset()
        return highway
    }

    private fun reset() {
        highway = false
        slowSinceMs = null
    }
}
