package com.conditiondrive.vehicle.platform

import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import kotlin.math.abs

/** android.car 없이 시험하려고 값만 옮겼다. VehicleGear, VehicleIgnitionState 공개 상수이고 GEAR_SELECTION 값은 실측과 같다 */
internal object SignalMapping {
    private const val GEAR_UNKNOWN = 0
    private const val GEAR_NEUTRAL = 1
    private const val GEAR_REVERSE = 2
    private const val GEAR_PARK = 4

    private const val IGN_UNDEFINED = 0
    private const val IGN_LOCK = 1
    private const val IGN_OFF = 2
    private const val IGN_ACC = 3
    private const val IGN_ON = 4
    private const val IGN_START = 5

    /** 8 이상은 D와 수동 단수(1~9단)다. 판단에는 전진 여부만 쓴다 */
    fun gear(value: Int?): Gear = when (value) {
        null, GEAR_UNKNOWN -> Gear.UNKNOWN
        GEAR_PARK -> Gear.PARK
        GEAR_REVERSE -> Gear.REVERSE
        GEAR_NEUTRAL -> Gear.NEUTRAL
        else -> if (value >= 8) Gear.DRIVE else Gear.UNKNOWN
    }

    /** ACC는 전장만 켜진 상태라 주행이 아니다 */
    fun ignition(value: Int?): Ignition = when (value) {
        IGN_LOCK, IGN_OFF, IGN_ACC -> Ignition.OFF
        IGN_ON, IGN_START -> Ignition.ON
        null, IGN_UNDEFINED -> Ignition.UNKNOWN
        else -> Ignition.UNKNOWN
    }

    /** 후진은 음수로 온다. 차속 규칙은 크기만 본다 */
    fun speedKmh(metersPerSecond: Float): Double = abs(metersPerSecond) * 3.6
}

/**
 * PERF_VEHICLE_SPEED는 요청한 1Hz보다 빠르게 온다(실측 5~10Hz, minSampleRate 10).
 * 그대로 내보내면 reduce가 초당 10번 돈다. 1초에 한 번으로 줄이되 정차·출발 경계는 바로 알린다. 게이트가 늦으면 안 된다.
 */
internal class SpeedThrottle(private val periodMs: Long = 1000, private val stopThresholdKmh: Double = 0.5) {
    private var lastAtMs: Long = Long.MIN_VALUE
    private var last: Double? = null

    fun accept(kmh: Double, nowMs: Long): Boolean {
        val previous = last
        val crossed = previous == null || (previous < stopThresholdKmh) != (kmh < stopThresholdKmh)
        val due = lastAtMs == Long.MIN_VALUE || nowMs - lastAtMs >= periodMs
        if (!crossed && !due) return false
        last = kmh
        lastAtMs = nowMs
        return true
    }

    fun reset() {
        last = null
        lastAtMs = Long.MIN_VALUE
    }
}
