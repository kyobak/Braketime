package com.conditiondrive.vehicle.storage

import com.conditiondrive.core.engine.TripEvent
import com.conditiondrive.core.engine.TripEvent.CONDITION_STOP_ADVISED
import com.conditiondrive.core.engine.TripEvent.DECLINED
import com.conditiondrive.core.engine.TripEvent.DRIVE_STARTED
import com.conditiondrive.core.engine.TripEvent.REST_COMPLETED
import com.conditiondrive.core.engine.TripEvent.REST_STARTED
import com.conditiondrive.core.engine.TripEvent.STOP_ADVISED
import com.conditiondrive.vehicle.ui.model.RecentSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripLogDbTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun at(h: Int, m: Int = 0, day: Int = 15) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun row(e: TripEvent, ms: Long) = TripEventRow(e, ms)

    private class MemoryStore : TripEventStore {
        val rows = mutableListOf<TripEventRow>()
        override fun insert(row: TripEventRow) { rows += row }
        override fun since(atMs: Long) = rows.filter { it.atMs >= atMs }
        override fun deleteBefore(atMs: Long) { rows.removeAll { it.atMs < atMs } }
        override fun deleteAll() = rows.clear()
    }

    @Test fun foldsSegmentsAndCounts() {
        val trips = TripFold.fold(
            listOf(
                row(DRIVE_STARTED, at(9, 12)),
                row(STOP_ADVISED, at(10, 50)),
                row(DECLINED, at(10, 51)),
                row(REST_STARTED, at(11, 4)),
                row(REST_COMPLETED, at(11, 19)),
                row(DRIVE_STARTED, at(11, 19)),
                row(CONDITION_STOP_ADVISED, at(13, 0)),
                row(REST_STARTED, at(13, 7)),
            ),
            zone,
        )
        assertEquals(1, trips.size)
        val t = trips.single()
        assertEquals(listOf("drive", "rest", "drive", "rest"), t.segments.map { it.type })
        assertEquals(at(11, 4), t.segments[0].endMs)
        assertNull(t.segments.last().endMs)
        assertNull(t.endedAtMs)
        assertEquals(2, t.stopAdvised)
        assertEquals(1, t.declined)
        assertEquals(1, t.restCompleted)
        assertEquals(1, t.conditionStops)
        UUID.fromString(t.tripId)
    }

    @Test fun repeatedEventsDoNotSplitSegment() {
        val t = TripFold.fold(listOf(row(DRIVE_STARTED, at(9)), row(DRIVE_STARTED, at(9, 30))), zone).single()
        assertEquals(1, t.segments.size)
        assertEquals(at(9), t.segments[0].startMs)
    }

    @Test fun fifteenMinuteRestStaysSameTrip() {
        val trips = TripFold.fold(listOf(row(DRIVE_STARTED, at(9)), row(REST_STARTED, at(10)), row(DRIVE_STARTED, at(10, 15))), zone)
        assertEquals(1, trips.size)
    }

    @Test fun longParkOrNewDayStartsNewTrip() {
        val long = TripFold.fold(listOf(row(DRIVE_STARTED, at(8)), row(REST_STARTED, at(9)), row(DRIVE_STARTED, at(13))), zone)
        assertEquals(2, long.size)
        // 휴식 완료 기록이 없어도(시동 끔) 무기록으로 닫힌 휴식은 15분까지 채운다(U-25)
        assertEquals(at(9, 15), long[0].endedAtMs)
        val nextDay = TripFold.fold(listOf(row(DRIVE_STARTED, at(23)), row(REST_STARTED, at(23, 30)), row(DRIVE_STARTED, at(0, 30, day = 16))), zone)
        assertEquals(2, nextDay.size)
    }

    @Test fun tripIdIsStableAcrossFolds() {
        val rows = listOf(row(DRIVE_STARTED, at(9)), row(REST_STARTED, at(10)))
        assertEquals(TripFold.fold(rows, zone).single().tripId, TripFold.fold(rows + row(DRIVE_STARTED, at(10, 20)), zone).single().tripId)
    }

    @Test fun todayShowsOnlyToday() = runBlocking {
        val store = MemoryStore()
        val db = TripLogDb(store, { at(12) })
        db.record(DRIVE_STARTED, at(9, day = 14))
        db.record(REST_STARTED, at(10, day = 14))
        db.record(DRIVE_STARTED, at(9))
        db.record(STOP_ADVISED, at(11))
        val today = db.today().first()
        assertEquals(1, today.size)
        assertEquals(at(9), today.single().startedAtMs)
        assertEquals(1, today.single().stopAdvised)
    }

    @Test fun driveAcrossMidnightIsToday() = runBlocking {
        val db = TripLogDb(MemoryStore(), { at(1) })
        db.record(DRIVE_STARTED, at(23, 30, day = 14))
        assertEquals(1, db.today().first().size)
    }

    @Test fun retentionDropsOldEvents() = runBlocking {
        val store = MemoryStore()
        val db = TripLogDb(store, { at(12) }, retentionDays = 30)
        db.record(DRIVE_STARTED, at(12) - 31L * 24 * 3600 * 1000)
        db.record(DRIVE_STARTED, at(12))
        assertEquals(listOf(at(12)), store.rows.map { it.atMs })
    }

    /** S4 "최근 7일": 창 첫날 0시 직후 운행이 쪼개지지 않고, 보존 안이지만 창 밖인 8일 전 운행은 합계에서 빠진다 */
    @Test fun recentFeedsSevenDayWindowWithinRetention() = runBlocking {
        val store = MemoryStore()
        val db = TripLogDb(store, { at(12) }, retentionDays = 30)
        val day = 24 * 3600 * 1000L
        val tooOld = at(9, day = 12) - 31 * day
        db.record(DRIVE_STARTED, tooOld)                     // 보존 밖: 뒤 사건을 쓸 때 지워진다
        db.record(DRIVE_STARTED, at(10, day = 7))             // 8일 전: 보존 안, 창 밖
        // 창 전날 밤 시작, 자정 넘김. 창 시작부터만 읽으면 0:40 휴식부터가 창 안 운행으로 잘못 잡힌다
        db.record(DRIVE_STARTED, at(23, 50, day = 8))
        db.record(REST_STARTED, at(0, 40, day = 9))
        db.record(DRIVE_STARTED, at(0, 55, day = 9))
        db.record(DRIVE_STARTED, at(9, day = 12))
        db.record(STOP_ADVISED, at(9, 30, day = 12))
        db.record(REST_STARTED, at(10, day = 12))
        db.record(REST_COMPLETED, at(10, 15, day = 12))
        assertTrue(store.rows.none { it.atMs == tooOld })
        val totals = RecentSummary.of(db.recent(RecentSummary.DAYS).first(), at(12), zone)
        assertEquals(1, totals.trips)
        assertEquals(60 * 60L, totals.driveSec)
        assertEquals(1, totals.advised)
        assertEquals(1, totals.rested)
        // 8일 전 운행은 DB에는 남아 있다
        assertTrue(store.rows.any { it.atMs == at(10, day = 7) })
    }

    @Test fun recentIsEmptyAfterDeleteAll() = runBlocking {
        val db = TripLogDb(MemoryStore(), { at(12) })
        db.record(DRIVE_STARTED, at(9, day = 13))
        assertTrue(db.recent(7).first().isNotEmpty())
        db.deleteAll()
        assertEquals(emptyList(), db.recent(7).first())
    }

    @Test fun deleteAllEmptiesAndNotifies() = runBlocking {
        val db = TripLogDb(MemoryStore(), { at(12) })
        db.record(DRIVE_STARTED, at(9))
        assertTrue(db.today().first().isNotEmpty())
        db.deleteAll()
        assertEquals(emptyList(), db.today().first())
    }

    @Test fun storeFailureDoesNotThrow() = runBlocking {
        val broken = object : TripEventStore {
            override fun insert(row: TripEventRow) = throw IllegalStateException("disk")
            override fun since(atMs: Long): List<TripEventRow> = throw IllegalStateException("disk")
            override fun deleteBefore(atMs: Long) = Unit
            override fun deleteAll() = throw IllegalStateException("disk")
        }
        val db = TripLogDb(broken, { at(12) })
        db.record(DRIVE_STARTED, at(9))
        db.deleteAll()
        assertEquals(emptyList(), db.today().first())
    }

    /** 시연은 2026-11-20 날짜로 돈다. 벽시계로 읽으면 기록이 있어도 "오늘 기록이 없어요"가 된다 */
    @Test fun viewReadsBySimulatedDayAndSharesRows() = runBlocking {
        val store = MemoryStore()
        val wall = TripLogDb(store, { at(9, day = 1) }, zone)
        val simulated = wall.viewedAt { at(11, day = 15) }
        simulated.record(DRIVE_STARTED, at(9, day = 15))
        simulated.record(REST_STARTED, at(10, day = 15))
        assertEquals(1, simulated.today().first().size)
        assertTrue(wall.today().first().isEmpty())
    }
}
