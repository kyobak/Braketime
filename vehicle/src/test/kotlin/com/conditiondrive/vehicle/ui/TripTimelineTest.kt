package com.conditiondrive.vehicle.ui

import com.conditiondrive.core.engine.Policy
import com.conditiondrive.vehicle.port.TripRecord
import com.conditiondrive.vehicle.port.TripSegment
import com.conditiondrive.vehicle.ui.model.RecentSummary
import com.conditiondrive.vehicle.ui.model.TimelineEntry
import com.conditiondrive.vehicle.ui.model.TripTimeline
import com.conditiondrive.vehicle.ui.preview.UiSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S4 목록 규칙(U-23). "휴식"은 15분을 채운 정차만, 1분 미만은 행이 아니다, 15분 미만 멈춤은 운전을 끊지 않는다 */
class TripTimelineTest {
    private val m = 60_000L
    private val t0 = UiSamples.NOW_MS - 600 * m

    /** (종류, 분) 목록을 이어 붙인다. 마지막이 null 분이면 진행 중 구간이다 */
    private fun trip(start: Long, vararg parts: Pair<String, Long?>): TripRecord {
        var at = start
        val segments = parts.map { (type, minutes) ->
            val end = minutes?.let { at + it * m }
            TripSegment(type, at, end).also { at = end ?: at }
        }
        return TripRecord("t$start", start, segments.last().endMs, segments, 0, 0, 0, 0)
    }

    private fun entries(now: Long, vararg trips: TripRecord) = TripTimeline.entries(trips.toList(), now)

    /** (종류, 초) 목록을 닫힌 구간으로 이어 붙인다. 경계 시험용 */
    private fun tripSec(start: Long, vararg parts: Pair<String, Long>): TripRecord {
        var at = start
        val segments = parts.map { (type, sec) -> TripSegment(type, at, at + sec * 1000).also { at += sec * 1000 } }
        return TripRecord("s$start", start, at, segments, 0, 0, 0, 0)
    }

    @Test fun restResetIsTheEngineValue() {
        assertEquals(Policy.DEFAULT.REST_RESET_SEC, TripTimeline.REST_SEC)
    }

    @Test fun shortStopIsPauseNotRest() {
        val trip = trip(t0, "drive" to 30L, "rest" to 12L)
        assertEquals(
            listOf(TimelineEntry.Drive(t0, 30 * 60, 0, 0), TimelineEntry.Pause(t0 + 30 * m, 12 * 60)),
            entries(t0 + 42 * m, trip),
        )
        assertEquals(0, TripTimeline.restCount(listOf(trip), t0 + 42 * m))
    }

    @Test fun fifteenMinutesIsRestAndSplitsDrives() {
        val trip = trip(t0, "drive" to 112L, "rest" to 15L, "drive" to 108L)
        assertEquals(
            listOf(
                TimelineEntry.Drive(t0, 112 * 60, 0, 0),
                TimelineEntry.Rest(t0 + 112 * m, 15 * 60),
                TimelineEntry.Drive(t0 + 127 * m, 108 * 60, 0, 0),
            ),
            entries(t0 + 300 * m, trip),
        )
        assertEquals(1, TripTimeline.restCount(listOf(trip), t0 + 300 * m))
    }

    @Test fun shortStopMergesDrivesLikeEngineContinuousDrive() {
        // 엔진은 15분 미만 정차에서 연속 운전을 지우지 않는다(E27). 행도 60 + 52 = 112분 한 줄이다
        val trip = trip(t0, "drive" to 60L, "rest" to 12L, "drive" to 52L, "rest" to 20L)
        assertEquals(
            listOf(TimelineEntry.Drive(t0, 112 * 60, 1, 12 * 60), TimelineEntry.Rest(t0 + 124 * m, 20 * 60)),
            entries(t0 + 300 * m, trip),
        )
    }

    @Test fun pausesAreCountedAndSubMinutePausesAreNot() {
        val shortMs = 30_000L
        val trip = TripRecord(
            "p", t0, t0 + 64 * m + shortMs,
            listOf(
                TripSegment("drive", t0, t0 + 20 * m),
                TripSegment("rest", t0 + 20 * m, t0 + 25 * m),
                TripSegment("drive", t0 + 25 * m, t0 + 45 * m),
                TripSegment("rest", t0 + 45 * m, t0 + 45 * m + shortMs),
                TripSegment("drive", t0 + 45 * m + shortMs, t0 + 55 * m + shortMs),
                TripSegment("rest", t0 + 55 * m + shortMs, t0 + 64 * m + shortMs),
                TripSegment("drive", t0 + 64 * m + shortMs, t0 + 74 * m + shortMs),
            ),
            0, 0, 0, 0,
        )
        assertEquals(listOf(TimelineEntry.Drive(t0, 60 * 60, 2, 14 * 60)), entries(t0 + 100 * m, trip))
    }

    @Test fun subMinuteSegmentsAreNotRowsButStayInTotal() {
        val trip = TripRecord("s", t0, null, listOf(TripSegment("drive", t0, t0 + 40_000), TripSegment("rest", t0 + 40_000, null)), 0, 0, 0, 0)
        val now = t0 + 70_000
        assertEquals(emptyList(), entries(now, trip))
        assertEquals(40L, TripTimeline.driveSec(listOf(trip), now))
    }

    @Test fun openStopTurnsIntoRestAtFifteenMinutes() {
        val trip = trip(t0, "drive" to 30L, "rest" to null)
        val start = t0 + 30 * m
        assertEquals(TimelineEntry.Pause(start, 15 * 60 - 1), entries(start + 15 * m - 1000, trip).last())
        assertEquals(TimelineEntry.Rest(start, 15 * 60), entries(start + 15 * m, trip).last())
        assertEquals(1, TripTimeline.restCount(listOf(trip), start + 15 * m))
    }

    @Test fun openDriveAfterShortStopKeepsGrowingOneRow() {
        val trip = trip(t0, "drive" to 50L, "rest" to 10L, "drive" to null)
        assertEquals(listOf(TimelineEntry.Drive(t0, 70 * 60, 1, 10 * 60)), entries(t0 + 80 * m, trip))
    }

    @Test fun tripsAreNotMergedAcrossTheirBoundary() {
        val a = trip(t0, "drive" to 30L, "rest" to 5L)
        val b = trip(t0 + 35 * m, "drive" to 20L)
        assertEquals(
            listOf(TimelineEntry.Drive(t0, 30 * 60, 0, 0), TimelineEntry.Pause(t0 + 30 * m, 5 * 60), TimelineEntry.Drive(t0 + 35 * m, 20 * 60, 0, 0)),
            entries(t0 + 100 * m, b, a),
        )
    }

    @Test fun leadingRestFragmentKeepsItsOwnWord() {
        val fragment = trip(t0, "rest" to 15L)
        val short = trip(t0 + 60 * m, "rest" to 8L)
        assertEquals(
            listOf(TimelineEntry.Rest(t0, 15 * 60), TimelineEntry.Pause(t0 + 60 * m, 8 * 60)),
            entries(t0 + 100 * m, fragment, short),
        )
    }

    @Test fun screenshotDataBecomesOneDriveRow() {
        // 25-s4-week-before-fix.png: 운전 1분 + 멈춤 12분 + 운전 30초 + 진행 중 멈춤 40초
        val now = ShortStopSample.NOW_MS
        val rows = TripTimeline.entries(ShortStopSample.trips, now)
        assertEquals(1, rows.size)
        val drive = rows.single() as TimelineEntry.Drive
        assertEquals(100L, drive.sec)
        assertEquals(1, drive.pauses)
        assertEquals(12 * 60 + 10L, drive.pauseSec)
        assertEquals(0, TripTimeline.restCount(ShortStopSample.trips, now))
        assertEquals(0, RecentSummary.of(ShortStopSample.trips, now, UiSamples.ZONE).rested)
    }

    @Test fun galleryRestRowsEqualRestCounts() {
        // 오늘 목록의 휴식 행 수 = 오늘 합계 = 견본 restCompleted. 7일 요약 3회는 오늘 2 + 2일 전 1이다
        val today = TripTimeline.entries(UiSamples.trips, UiSamples.NOW_MS).filterIsInstance<TimelineEntry.Rest>()
        assertEquals(2, today.size)
        assertEquals(UiSamples.trips.sumOf { it.restCompleted }, today.size)
        assertEquals(3, RecentSummary.of(UiSamples.recentTrips, UiSamples.NOW_MS, UiSamples.ZONE).rested)
    }

    @Test fun closedStopBoundaryIsFifteenMinutesExactly() {
        val now = t0 + 100 * m
        for ((stopSec, rest) in listOf(899L to false, 900L to true, 901L to true)) {
            val trip = tripSec(t0, "drive" to 600L, "rest" to stopSec)
            val last = entries(now, trip).last()
            assertEquals(if (rest) TimelineEntry.Rest(t0 + 600_000, stopSec) else TimelineEntry.Pause(t0 + 600_000, stopSec), last, "정차 ${stopSec}초")
            assertEquals(if (rest) 1 else 0, TripTimeline.restCount(listOf(trip), now), "정차 ${stopSec}초")
        }
    }

    @Test fun oneMinuteBoundaryForRowsAndPauses() {
        val now = t0 + 100 * m
        // 단독 구간: 59초는 행이 없고 60초는 행이다
        assertEquals(emptyList(), entries(now, tripSec(t0, "drive" to 59L)))
        assertEquals(listOf<TimelineEntry>(TimelineEntry.Drive(t0, 60, 0, 0)), entries(now, tripSec(t0, "drive" to 60L)))
        assertEquals(emptyList(), entries(now, tripSec(t0, "rest" to 59L)))
        assertEquals(listOf<TimelineEntry>(TimelineEntry.Pause(t0, 60)), entries(now, tripSec(t0, "rest" to 60L)))
        // 중간 멈춤: 59초는 횟수에 들지 않고 60초는 든다. 운전은 어느 쪽이든 한 행으로 합친다
        assertEquals(
            listOf<TimelineEntry>(TimelineEntry.Drive(t0, 600, 0, 0)),
            entries(now, tripSec(t0, "drive" to 300L, "rest" to 59L, "drive" to 300L)),
        )
        assertEquals(
            listOf<TimelineEntry>(TimelineEntry.Drive(t0, 600, 1, 60)),
            entries(now, tripSec(t0, "drive" to 300L, "rest" to 60L, "drive" to 300L)),
        )
    }

    @Test fun subMinuteDriveDoesNotHideTheStopInside() {
        // 운전 20초 + 정차 10분 + 운전 20초. 운전 행은 1분 미만이라 없지만 10분 정차는 멈춤 행으로 남는다.
        // 정차가 운전 뒤 끝에 붙은 경우("멈춤 10분")와 같은 결과다
        val now = t0 + 100 * m
        val inside = tripSec(t0, "drive" to 20L, "rest" to 600L, "drive" to 20L)
        assertEquals(listOf<TimelineEntry>(TimelineEntry.Pause(t0 + 20_000, 600)), entries(now, inside))
        val trailing = tripSec(t0, "drive" to 40L, "rest" to 600L)
        assertEquals(listOf<TimelineEntry>(TimelineEntry.Pause(t0 + 40_000, 600)), entries(now, trailing))
        assertEquals(40L, TripTimeline.driveSec(listOf(inside), now))
    }

    @Test fun adjacentDrivesWithoutStopAreOneRow() {
        val trip = tripSec(t0, "drive" to 600L, "drive" to 300L)
        assertEquals(listOf<TimelineEntry>(TimelineEntry.Drive(t0, 900, 0, 0)), entries(t0 + 100 * m, trip))
    }

    @Test fun subMinuteTripIsNotCountedAndCountedTripAlwaysHasARow() {
        val now = t0 + 100 * m
        val blip = tripSec(t0, "drive" to 40L)
        assertEquals(0, TripTimeline.tripCount(listOf(blip), now))
        assertEquals(emptyList(), entries(now, blip))
        // 1분 이상 운전한 운행은 쪼개진 모양이어도 목록에 행이 있다
        val shapes = listOf(
            tripSec(t0, "drive" to 60L),
            tripSec(t0, "drive" to 40L, "rest" to 30L, "drive" to 40L),
            tripSec(t0, "drive" to 40L, "rest" to 900L, "drive" to 40L),
            tripSec(t0, "drive" to 30L, "drive" to 30L),
        )
        for (trip in shapes) {
            assertEquals(1, TripTimeline.tripCount(listOf(trip), now), trip.segments.toString())
            assertTrue(entries(now, trip).isNotEmpty(), trip.segments.toString())
        }
    }
}
