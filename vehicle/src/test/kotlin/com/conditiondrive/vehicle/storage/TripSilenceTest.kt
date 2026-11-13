package com.conditiondrive.vehicle.storage

import com.conditiondrive.core.engine.TripEvent
import com.conditiondrive.core.engine.TripEvent.CONDITION_STOP_ADVISED
import com.conditiondrive.core.engine.TripEvent.DRIVE_STARTED
import com.conditiondrive.core.engine.TripEvent.REST_COMPLETED
import com.conditiondrive.core.engine.TripEvent.REST_STARTED
import com.conditiondrive.core.engine.TripEvent.STOP_ADVISED
import com.conditiondrive.vehicle.ui.model.RecentSummary
import com.conditiondrive.vehicle.ui.model.TimelineEntry
import com.conditiondrive.vehicle.ui.model.TripTimeline
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 에뮬레이터 실사용 S4의 "13:07 운전 27시간 19분"(2026-11-05) 재현.
 * 전날 13:07 D에서 앱이 멈추고 다음 날 재부팅 복원이 REST_STARTED만 남기면, 출발 사건이 없어 운행 경계(4시간, 날짜)가 걸리지 않고
 * 관측하지 못한 27시간이 운전 한 구간이 된다.
 */
class TripSilenceTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(day: Int, h: Int, m: Int = 0, s: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, s, 0, zone).toInstant().toEpochMilli()

    private class MemoryStore(val rows: MutableList<TripEventRow>) : TripEventStore {
        override fun insert(row: TripEventRow) { rows += row }
        override fun since(atMs: Long) = rows.filter { it.atMs >= atMs }
        override fun deleteBefore(atMs: Long) { rows.removeAll { it.atMs < atMs } }
        override fun deleteAll() = rows.clear()
    }

    private val now = at(5, 20, 30)
    private val rows = mutableListOf(
        // 11-04 13:07 출발 뒤 앱이 아무것도 쓰지 못했다(에뮬레이터 종료, 콘솔은 D 그대로)
        TripEventRow(DRIVE_STARTED, at(4, 13, 7)),
        // 11-05 콜드 부팅 뒤 복원: 주행계 없음(UNKNOWN) + 재부팅 = 시동 끔 → REST_STARTED
        TripEventRow(REST_STARTED, at(5, 16, 32)),
        TripEventRow(DRIVE_STARTED, at(5, 16, 35)),
        TripEventRow(REST_STARTED, at(5, 16, 38)),
        TripEventRow(DRIVE_STARTED, at(5, 16, 58)),
        TripEventRow(REST_STARTED, at(5, 17, 5)),
    )
    private val db = TripLogDb(MemoryStore(rows), { now }, zone)

    @Test fun silenceInsideDriveIsNotDriving() = runBlocking {
        val today = db.today().first()
        val entries = TripTimeline.entries(today, now)
        val longest = entries.filterIsInstance<TimelineEntry.Drive>().maxOf { it.sec }
        // 관측된 운전은 3분 + 7분뿐이다
        assertTrue(longest <= 7 * 60, "오늘 목록에 ${longest / 60}분짜리 운전 행이 있다: $entries")
        assertEquals((3 + 7) * 60L, TripTimeline.driveSec(today, now))
        // 오늘 목록의 첫 행은 오늘 시각이어야 한다(날짜 없이 13:07만 보이면 오늘로 읽힌다)
        assertTrue(entries.all { it.startMs >= at(5, 0) }, "어제 시작한 행이 오늘 목록에 있다: $entries")
    }

    @Test fun recentWeekDoesNotCountSilence() = runBlocking {
        val week = RecentSummary.of(db.recent(RecentSummary.DAYS).first(), now, zone)
        assertEquals((3 + 7) * 60L, week.driveSec)
    }

    @Test fun realLongDriveAcrossMidnightStaysOneTrip() {
        // 4시간 미만 간격으로 사건이 이어지면(재안내 최대 60분, C-10) 자정을 넘긴 운전은 한 운행이다
        val trips = TripFold.fold(
            listOf(
                TripEventRow(DRIVE_STARTED, at(4, 21)),
                TripEventRow(TripEvent.STOP_ADVISED, at(4, 23)),
                TripEventRow(TripEvent.DECLINED, at(4, 23, 1)),
                TripEventRow(REST_STARTED, at(5, 0, 40)),
            ),
            zone,
        )
        assertEquals(1, trips.size)
        assertEquals(at(5, 0, 40), trips.single().segments.first().endMs)
    }

    @Test fun adviceRightAfterDroveRestoreOpensDrive() {
        // 앱이 5시간 꺼진 채 달린 뒤 DROVE 복원, 복원 시각에 바로 정차 권고, 1시간 더 달리고 정차.
        // 공백 5시간은 넣지 않지만 복원 뒤 본 60분은 운전이다(권고는 운전 중에만 나온다)
        val trips = TripFold.fold(
            listOf(
                TripEventRow(DRIVE_STARTED, at(5, 8)),
                TripEventRow(REST_STARTED, at(5, 8, 30)),
                TripEventRow(DRIVE_STARTED, at(5, 8, 40)),
                TripEventRow(STOP_ADVISED, at(5, 13, 40)),
                TripEventRow(REST_STARTED, at(5, 14, 40)),
            ),
            zone,
        )
        assertEquals(2, trips.size)
        assertEquals((30 + 60) * 60L, TripTimeline.driveSec(trips, at(5, 18)))
    }

    @Test fun ignitionOffParkKeepsRestLikeIgnitionOn() {
        // 시동을 끈 주차는 휴식 완료가 다음 시동 때 기록된다. P에 시동을 켠 채 주차한 것과 같은 휴식으로 보여야 한다
        val now = at(5, 18)
        val off = TripFold.fold(
            listOf(
                TripEventRow(DRIVE_STARTED, at(5, 8)), TripEventRow(REST_STARTED, at(5, 9)),
                TripEventRow(REST_COMPLETED, at(5, 14)), TripEventRow(DRIVE_STARTED, at(5, 14, 1)), TripEventRow(REST_STARTED, at(5, 15)),
            ),
            zone,
        )
        val on = TripFold.fold(
            listOf(
                TripEventRow(DRIVE_STARTED, at(5, 8)), TripEventRow(REST_STARTED, at(5, 9)), TripEventRow(REST_COMPLETED, at(5, 9, 15)),
                TripEventRow(DRIVE_STARTED, at(5, 14)), TripEventRow(REST_STARTED, at(5, 15)),
            ),
            zone,
        )
        assertEquals(at(5, 9, 15), off.first().endedAtMs)
        assertEquals(TripTimeline.restCount(on, now), TripTimeline.restCount(off, now))
        val parked = TripTimeline.entries(off, now).filterIsInstance<TimelineEntry.Rest>().first()
        assertEquals(at(5, 9), parked.startMs)
        assertEquals(15 * 60L, parked.sec)
    }

    @Test fun segmentlessTripIsClosedNotOngoing() = runBlocking {
        // 무기록 뒤 구간 사건 없이 기록만 남고 다시 무기록으로 닫힌 운행은 다음 날 오늘에 들지 않아야 한다
        val store = MemoryStore(
            mutableListOf(
                TripEventRow(DRIVE_STARTED, at(4, 21)), TripEventRow(REST_STARTED, at(4, 22)),
                TripEventRow(REST_COMPLETED, at(5, 8)), TripEventRow(CONDITION_STOP_ADVISED, at(5, 8, 0, 5)),
                TripEventRow(DRIVE_STARTED, at(5, 13)), TripEventRow(REST_STARTED, at(5, 13, 30)),
            ),
        )
        val day6 = TripLogDb(store, { at(6, 10) }, zone).today().first()
        assertEquals(0, day6.sumOf { it.stopAdvised }, "전날 권고가 오늘에 섞였다: $day6")
        assertEquals(0, day6.sumOf { it.conditionStops })
        assertTrue(day6.none { it.startedAtMs == at(5, 8) })
    }
}
