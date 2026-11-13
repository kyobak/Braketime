package com.conditiondrive.vehicle.ui.model

import androidx.compose.runtime.Immutable
import com.conditiondrive.vehicle.port.TripRecord
import java.time.Instant
import java.time.ZoneId

/** S4 "최근 7일" 합계. 건강값과 정차 사유는 담지 않는다(F8-5). 안내를 따르지 않은 횟수도 담지 않는다. [rested]는 15분 이상 휴식 횟수다 */
@Immutable
data class RecentTotals(val trips: Int, val driveSec: Long, val advised: Int, val rested: Int) {
    // 운행이 0이어도 창 안에 안내나 15분 이상 휴식 기록이 있으면 "기록이 없어요"라 하지 않는다
    val isEmpty: Boolean get() = trips == 0 && advised == 0 && rested == 0
}

/**
 * 창은 오늘을 포함한 지역 날짜 7일(6일 전 0시부터 지금까지)이다. 24시간 x 7로 자르면 같은 날 운행이 시각에 따라 들어갔다 빠진다.
 * 운행은 시작한 날로 센다. 자정을 넘긴 운행을 둘로 나눠 세면 횟수와 시간이 서로 다른 운행 묶음을 말하게 된다.
 * 기록 보존은 30일(C-54)이라 7일 창은 늘 보존 안에 있다(RecentSummaryTest가 고정한다).
 */
object RecentSummary {
    const val DAYS = 7

    fun windowStartMs(nowMs: Long, zone: ZoneId, days: Int = DAYS): Long =
        Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            .minusDays(days - 1L)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun of(trips: List<TripRecord>, nowMs: Long, zone: ZoneId, days: Int = DAYS): RecentTotals {
        val start = windowStartMs(nowMs, zone, days)
        // 지금 뒤에 시작한 운행은 시계가 되감긴 흔적이라 뺀다(시연 시계, 기기 시각 변경)
        val inWindow = trips.filter { it.startedAtMs in start..nowMs }
        return RecentTotals(
            // 복원 직후 휴식 사건만 남은 조각과 1분 미만 운행은 운행이 아니다(오늘 목록에 행이 없다). 안내와 휴식 횟수는 그래도 센다
            trips = TripTimeline.tripCount(inWindow, nowMs),
            driveSec = TripTimeline.driveSec(inWindow, nowMs),
            advised = inWindow.sumOf { it.stopAdvised },
            // 오늘 목록의 "휴식" 행과 같은 규칙이다(15분을 채운 정차 한 번이 1회)
            rested = TripTimeline.restCount(inWindow, nowMs),
        )
    }
}
