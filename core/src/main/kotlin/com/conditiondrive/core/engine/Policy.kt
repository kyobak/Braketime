@file:Suppress("PropertyName")

package com.conditiondrive.core.engine

import java.time.LocalTime

/** C-36 "generated_at 이후 처음 맞는 경계, 그때까지 최소 시간 미만이면 다음 날 경계" */
data class SleepValidRule(val boundary: LocalTime, val minHoursUntilBoundary: Int)

data class HttpTimeoutMs(val connect: Long, val call: Long)

/**
 * 설계서 4장 상수표. 이름을 표와 그대로 맞춰 대조할 수 있게 하려고 대문자 프로퍼티로 둔다.
 * 시간은 이름의 단위를 따른다. 혈당은 mmol/L.
 * C-55는 시연 전용이고 :core에 해당 심볼을 둘 수 없어(2장) vehicle의 시연 패키지에 둔다.
 */
data class Policy(
    // 피로와 체크인
    val FATIGUE_CAUTION_SEC: Long = 6300,               // C-01
    val FATIGUE_STOP_SEC: Long = 7200,                  // C-02
    val REST_RESET_SEC: Long = 900,                     // C-03
    val SLEEP_SHORT_MINUTES: Int = 360,                 // C-04
    val ADVANCE_SEC: Long = 1800,                       // C-05
    val DEPART_CONFIRM_KMH: Double = 10.0,              // C-06
    val DEPART_CONFIRM_SEC: Long = 30,                  // C-07
    val REPROMPT_SEC: Long = 600,                       // C-08
    val FIRM_AFTER_DECLINES: Int = 4,                   // C-09
    val MUTED_REPROMPT_SEC: Long = 3600,                // C-10
    val ACCEPT_PAUSE_SEC: Long = 1200,                  // C-11
    val WAYPOINT_GRACE_SEC: Long = 180,                 // C-12
    val ARRIVAL_SOON_SEC: Long = 900,                   // C-13
    val CHECKIN_START_DELAY_SEC: Long = 5,              // C-14
    val CHECKIN_REASK_DRIVE_SEC: Long = 1800,           // C-15
    val TIRED_STREAK_STOP: Int = 2,                     // C-16
    val NIGHT_START: LocalTime = LocalTime.of(0, 0),    // C-17
    val NIGHT_END: LocalTime = LocalTime.of(6, 0),      // C-17
    val NIGHT_CHECKIN_DRIVE_SEC: Long = 3600,           // C-18
    val DAY_BOUNDARY: LocalTime = LocalTime.of(4, 0),   // C-19
    val LISTEN_TIMEOUT_MS: Long = 8000,                 // C-20
    val TTS_TO_STT_MS: Long = 300,                      // C-21
    val TTS_PREROLL_MS: Long = 150,                     // C-22

    // 혈당과 요약
    val GLUCOSE_STOP_BELOW: Double = 4.0,               // C-23
    val GLUCOSE_RECOVER_AT: Double = 5.0,               // C-24
    val GLUCOSE_WAIT_SEC: Long = 2400,                  // C-25
    val CAUTION_FALLING_FAST_BELOW: Double = 7.3,       // C-26
    val CAUTION_FALLING_BELOW: Double = 5.7,            // C-27
    val CAUTION_FLAT_BELOW: Double = 5.0,               // C-28
    val CAUTION_CLEAR_READS: Int = 2,                   // C-29
    val GLUCOSE_REPEAT_LOW_SEC: Long = 120,             // C-30
    val GLUCOSE_REPEAT_RECOVERING_SEC: Long = 600,      // C-31
    val GLUCOSE_TTL_SEC: Long = 900,                    // C-32
    val GLUCOSE_RESUME_GAP_SEC: Long = 900,             // C-33
    // C-34(LATCH_RELEASE_GAP_SEC)는 지웠다(D-32). 시간이 지났다고 값을 확인한 것은 아니다. 번호는 결번으로 둔다
    val GLUCOSE_LOST_REARM_SEC: Long = 600,             // C-35
    val SLEEP_VALID_RULE: SleepValidRule = SleepValidRule(LocalTime.of(4, 0), 6), // C-36

    // 시스템, 검색
    val CLOCK_BACKWARD_GAP: Long = 0,                   // C-37
    val PERSIST_SEC: Long = 30,                         // C-38
    val PARK_GATE_HOLD_MS: Long = 3000,                 // C-39
    val PARK_GATE_MAX_KMH: Double = 0.5,                // C-40
    val ODOMETER_MOVED_KM: Double = 0.5,                // C-41
    val POLL_SEC: Long = 60,                            // C-42
    val JITTER: Long = 5,                               // C-42, ±초
    val POLL_BACKOFF_SEC: List<Long> = listOf(15, 30, 60), // C-43, 이후 60
    val FIRST_FETCH_TIMEOUT_MS: Long = 3000,            // C-44
    val HTTP_TIMEOUT_MS: HttpTimeoutMs = HttpTimeoutMs(connect = 3000, call = 8000), // C-45
    val POI_BEARING_DEG: Double = 60.0,                 // C-46, ±도
    val POI_RADII_KM: List<Int> = listOf(5, 10, 20, 40), // C-47
    val POI_MIN_LEAD_SEC: Long = 60,                    // C-48
    val POI_MIN_LEAD_FLOOR_M: Int = 500,                // C-48 하한
    val POI_RETRY_SEC: Long = 30,                       // C-49
    val POI_MAX_DETOUR_RATIO: Double = 2.0,             // C-50
    val QUEUE_STALE_SEC: Long = 120,                    // C-51
    val VENT_RESTORE_SEC: Long = 300,                   // C-52
    val GUARDIAN_MOVING_SEC: Long = 600,                // C-53
    val LOG_RETENTION_DAYS: Int = 30,                   // C-54
) {
    companion object {
        val DEFAULT = Policy()
    }
}
