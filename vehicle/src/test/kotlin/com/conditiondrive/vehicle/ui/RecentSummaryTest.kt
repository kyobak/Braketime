package com.conditiondrive.vehicle.ui

import com.conditiondrive.core.engine.Policy
import com.conditiondrive.vehicle.port.TripRecord
import com.conditiondrive.vehicle.port.TripSegment
import com.conditiondrive.vehicle.ui.model.RecentSummary
import com.conditiondrive.vehicle.ui.model.RecentTotals
import com.conditiondrive.vehicle.ui.model.S4Presenter
import com.conditiondrive.vehicle.ui.model.TimelineEntry
import com.conditiondrive.vehicle.ui.model.TripTimeline
import com.conditiondrive.vehicle.ui.preview.UiSamples
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S4 "최근 7일" 순수 계산. Robolectric 없이 돈다 */
class RecentSummaryTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private val minute = 60_000L

    /** 2026-11-15(일) 기준. 창은 11-09 00:00부터다 */
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(15, 12)

    /** rested=1이면 운전 뒤에 15분 휴식 구간을 붙인다. 휴식 횟수는 구간에서 센다(U-23) */
    private fun trip(start: Long, driveMin: Long, advised: Int = 0, rested: Int = 0, open: Boolean = false): TripRecord {
        val driveEnd = start + driveMin * minute
        val rest = if (rested > 0 && !open) listOf(TripSegment("rest", driveEnd, driveEnd + 15 * minute)) else emptyList()
        return TripRecord(
            tripId = "t$start",
            startedAtMs = start,
            endedAtMs = if (open) null else rest.lastOrNull()?.endMs ?: driveEnd,
            segments = listOf(TripSegment("drive", start, if (open) null else driveEnd)) + rest,
            stopAdvised = advised, declined = advised, restCompleted = rested, conditionStops = 0,
        )
    }

    @Test fun windowIsSevenLocalDaysIncludingToday() {
        assertEquals(at(9, 0), RecentSummary.windowStartMs(now, zone))
        // 같은 날 아침과 밤에 창이 같다. 24시간 x 7로 자르면 시각마다 경계가 움직인다
        assertEquals(at(9, 0), RecentSummary.windowStartMs(at(15, 0, 1), zone))
        assertEquals(at(9, 0), RecentSummary.windowStartMs(at(15, 23, 59), zone))
    }

    @Test fun boundaryStartCountsAndOneMillisecondBeforeDoesNot() {
        val inside = trip(at(9, 0), 30)
        val outside = trip(at(9, 0) - 1, 30)
        assertEquals(1, RecentSummary.of(listOf(inside), now, zone).trips)
        assertEquals(0, RecentSummary.of(listOf(outside), now, zone).trips)
    }

    @Test fun tripIsCountedByItsStartDay() {
        // 창 전날 밤에 시작해 자정을 넘긴 운행은 통째로 빠지고, 창 첫날 밤 운행은 통째로 든다
        val beforeWindow = trip(at(8, 23, 30), 90)
        val lastDay = trip(at(14, 23, 30), 90)
        val totals = RecentSummary.of(listOf(beforeWindow, lastDay), now, zone)
        assertEquals(1, totals.trips)
        assertEquals(90 * 60L, totals.driveSec)
    }

    @Test fun sumsTripsDriveAdvisedAndRested() {
        val totals = RecentSummary.of(
            listOf(trip(at(10, 8), 125, advised = 2, rested = 1), trip(at(12, 18), 42), trip(at(15, 9), 60, advised = 1, rested = 1)),
            now, zone,
        )
        assertEquals(RecentTotals(trips = 3, driveSec = (125 + 42 + 60) * 60L, advised = 3, rested = 2), totals)
    }

    @Test fun openDriveRunsUntilNowAndFutureIsIgnored() {
        val open = trip(at(15, 11), 0, open = true)
        val future = trip(at(15, 13), 30)
        val totals = RecentSummary.of(listOf(open, future), now, zone)
        assertEquals(1, totals.trips)
        assertEquals(60 * 60L, totals.driveSec)
    }

    @Test fun restOnlyFragmentIsNotATripButKeepsCounts() {
        val fragment = TripRecord("f", at(14, 10), at(14, 10, 15), listOf(TripSegment("rest", at(14, 10), at(14, 10, 15))), 1, 0, 1, 0)
        val totals = RecentSummary.of(listOf(fragment), now, zone)
        assertEquals(0, totals.trips)
        assertEquals(1, totals.rested)
        // 오늘 목록에 "휴식 15분" 행이 서므로 7일 쪽도 빈 상태가 아니다
        assertFalse(totals.isEmpty)
    }

    /** 리뷰: 운전 30초 뒤 시동 켠 채 20분 쉼. 운행은 0이지만 "휴식 20분" 행 옆에 "운행 기록이 없어요"가 서면 안 된다 */
    @Test fun subMinuteDriveWithFullRestIsNotEmpty() {
        val start = at(15, 10)
        val t = TripRecord(
            "r", start, start + 30_000 + 20 * minute,
            listOf(TripSegment("drive", start, start + 30_000), TripSegment("rest", start + 30_000, start + 30_000 + 20 * minute)),
            0, 0, 1, 0,
        )
        val totals = RecentSummary.of(listOf(t), now, zone)
        assertEquals(RecentTotals(trips = 0, driveSec = 30, advised = 0, rested = 1), totals)
        assertFalse(totals.isEmpty)
        val model = S4Presenter.present(listOf(t), now, zone, recent = listOf(t))
        assertTrue(model.rows.isNotEmpty())
        val recent = assertNotNull(model.recent)
        assertEquals(0, recent.trips)
        assertEquals(1, recent.rested)
    }

    @Test fun emptyRecordsMeanEmptyBlock() {
        val totals = RecentSummary.of(emptyList(), now, zone)
        assertEquals(RecentTotals(0, 0, 0, 0), totals)
        assertTrue(totals.isEmpty)
        assertNull(S4Presenter.present(emptyList(), now, zone).recent)
    }

    @Test fun restedCountsFullRestsNotEngineEvents() {
        // 대체 정차 32분은 엔진이 REST_COMPLETED를 두 번 낸다. 목록은 "휴식 32분" 한 줄이라 7일 요약도 1회다
        val t = TripRecord(
            "fallback", at(14, 9), at(14, 10, 32),
            listOf(TripSegment("drive", at(14, 9), at(14, 10)), TripSegment("rest", at(14, 10), at(14, 10, 32))),
            0, 0, 2, 0,
        )
        assertEquals(1, RecentSummary.of(listOf(t), now, zone).rested)
        // 12분 멈춤은 휴식이 아니다
        val short = t.copy(segments = listOf(TripSegment("drive", at(14, 9), at(14, 10)), TripSegment("rest", at(14, 10), at(14, 10, 12))), restCompleted = 0)
        assertEquals(0, RecentSummary.of(listOf(short), now, zone).rested)
    }

    @Test fun subMinuteTripIsNotATripSoTodayAndWeekAgree() {
        // 시동 후 40초 만에 끝낸 운행. 오늘 목록에 행이 없으니 7일 요약도 "운행 1회, 운전 0분"이라 하지 않는다
        val blip = TripRecord("b", at(15, 11), at(15, 11) + 40_000, listOf(TripSegment("drive", at(15, 11), at(15, 11) + 40_000)), 0, 0, 0, 0)
        val totals = RecentSummary.of(listOf(blip), now, zone)
        assertEquals(0, totals.trips)
        assertEquals(40L, totals.driveSec)
        val model = S4Presenter.present(listOf(blip), now, zone, recent = listOf(blip))
        assertNull(model.recent)
        assertTrue(model.todayEmpty)
        // 다른 운행과 함께 있으면 그 운행만 센다
        assertEquals(1, RecentSummary.of(listOf(blip, trip(at(14, 9), 30)), now, zone).trips)
    }

    @Test fun windowFitsInsideRetention() {
        // 보존(C-54)보다 창이 길면 앞쪽 날이 조용히 비어 합계가 작게 보인다
        assertTrue(RecentSummary.DAYS <= Policy.DEFAULT.LOG_RETENTION_DAYS)
    }

    @Test fun zoneDecidesTheBoundary() {
        // 서울 11-09 08:30은 UTC로 11-08 23:30이다. UTC 창(11-09 00:00 UTC부터)에는 들지 않는다
        val early = trip(at(9, 8, 30), 30)
        assertEquals(1, RecentSummary.of(listOf(early), now, zone).trips)
        assertEquals(0, RecentSummary.of(listOf(early), now, ZoneOffset.UTC).trips)
    }

    /** U-24: 휴식으로 갈린 운전 행 2개도 한 운행이다. 7일 횟수("운행 N회")는 행 수가 아니라 운행 수를 센다 */
    @Test fun tripCountIsTripsNotDriveRows() {
        val now = TripVsRowSample.NOW_MS
        val totals = RecentSummary.of(TripVsRowSample.week, now, zone)
        assertEquals(2, totals.trips)
        assertEquals(8 * 60 + 30L, totals.driveSec)
        assertEquals(2, totals.rested)
        val todayDriveRows = TripTimeline.entries(listOf(TripVsRowSample.today), now).count { it is TimelineEntry.Drive }
        assertEquals(2, todayDriveRows)
        assertEquals(1, TripTimeline.tripCount(listOf(TripVsRowSample.today), now))
        val recent = assertNotNull(S4Presenter.present(listOf(TripVsRowSample.today), now, zone, recent = TripVsRowSample.week).recent)
        assertEquals("8분", recent.driveTotal)
    }

    @Test fun sampleMatchesGalleryNumbers() {
        val recent = assertNotNull(S4Presenter.present(UiSamples.trips, UiSamples.NOW_MS, UiSamples.ZONE, recent = UiSamples.recentTrips).recent)
        assertEquals(4, recent.trips)
        assertEquals("8시간 37분", recent.driveTotal)
        assertEquals(4, recent.advised)
        assertEquals(3, recent.rested)
    }
}
