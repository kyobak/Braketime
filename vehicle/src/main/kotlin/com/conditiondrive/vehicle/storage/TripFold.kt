package com.conditiondrive.vehicle.storage

import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.engine.TripEvent
import com.conditiondrive.vehicle.port.TripRecord
import com.conditiondrive.vehicle.port.TripSegment
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal data class TripEventRow(val event: TripEvent, val atMs: Long)

/**
 * 저장은 사건만, 구간은 읽을 때 접는다. 엔진에 운전 종료 사건이 없어 구간을 쓰는 시점에 끝을 정할 수 없기 때문이다.
 * 사유, 혈당, 좌표는 입력에도 없다(14장).
 */
internal object TripFold {
    const val SEGMENT_DRIVE = "drive"
    const val SEGMENT_REST = "rest"

    /**
     * 운행의 마지막 기록에서 이만큼(4시간 이상) 아무 사건이 없었으면 다음 사건이 무엇이든 다른 운행으로 친다(출발이 아니어도).
     * 출발 때 마지막 기록과 날짜가 달라도 다른 운행이다. 15분 휴식으로 기록이 나뉘면 합계 의미가 없어진다
     */
    const val NEW_TRIP_GAP_MS = 4 * 60 * 60 * 1000L

    private val REST_MS = Policy.DEFAULT.REST_RESET_SEC * 1000L

    private class Open(val startMs: Long) {
        val segments = mutableListOf<TripSegment>()
        var stopAdvised = 0
        var declined = 0
        var restCompleted = 0
        var conditionStops = 0
        var lastAtMs = startMs
    }

    fun fold(rows: List<TripEventRow>, zone: ZoneId): List<TripRecord> {
        val trips = mutableListOf<TripRecord>()
        var open: Open? = null

        fun close(trip: Open, closed: Boolean) {
            trips += record(trip, closed)
        }

        for (row in rows.sortedBy { it.atMs }) {
            val current = open
            // 앱이 보는 운전은 2시간(C-02)에 정차 권고, 그 뒤 최대 60분(C-10)마다 재안내 사건을 남기므로 4시간 무사건은 앱이 보지 못한 공백이다.
            // 출발 사건이 아니어도(재부팅 복원의 REST_STARTED 등) 마지막 기록에서 끊어야 관측 못 한 시간이 운전 구간이 되지 않는다
            // 예외: 혈당 정차 권고가 이어지거나 도착 임박으로 재안내가 사건을 남기지 않은 채 4시간 이상 달리면
            // 마지막 기록 뒤 운전은 다음 정차까지 S4 합계에서 빠진다(DESIGN S4 남은 한계, 근본책은 엔진 재안내 기록)
            val silent = current != null && row.atMs - current.lastAtMs >= NEW_TRIP_GAP_MS
            val startsNew = current != null && (
                silent || row.event == TripEvent.DRIVE_STARTED && !sameDay(current.lastAtMs, row.atMs, zone)
                )
            if (current != null && startsNew) {
                if (silent) closeOnSilence(current, row.atMs) else closeLast(current, current.lastAtMs)
                close(current, closed = true)
                open = null
            }
            val trip = open ?: Open(row.atMs).also { open = it }
            // 엔진은 정차 권고와 거절을 운전 중에만 낸다. 앱이 4시간 이상 꺼졌다 복원된 직후의 권고부터는 본 운전이다
            if (silent && (row.event == TripEvent.STOP_ADVISED || row.event == TripEvent.DECLINED)) {
                switchTo(trip, SEGMENT_DRIVE, row.atMs)
            }
            apply(trip, row)
            trip.lastAtMs = row.atMs
        }
        open?.let { close(it, closed = false) }
        return trips
    }

    /** today는 오늘에 닿은 운전만 보여 준다 */
    fun onDay(records: List<TripRecord>, dayStartMs: Long, dayEndMs: Long): List<TripRecord> =
        records.filter { r ->
            val end = r.endedAtMs ?: Long.MAX_VALUE
            r.startedAtMs < dayEndMs && end >= dayStartMs
        }

    private fun apply(trip: Open, row: TripEventRow) {
        when (row.event) {
            TripEvent.DRIVE_STARTED -> switchTo(trip, SEGMENT_DRIVE, row.atMs)
            TripEvent.REST_STARTED -> switchTo(trip, SEGMENT_REST, row.atMs)
            TripEvent.REST_COMPLETED -> trip.restCompleted++
            TripEvent.STOP_ADVISED -> trip.stopAdvised++
            TripEvent.DECLINED -> trip.declined++
            TripEvent.CONDITION_STOP_ADVISED -> {
                trip.conditionStops++
                trip.stopAdvised++
            }
        }
    }

    private fun switchTo(trip: Open, type: String, atMs: Long) {
        val last = trip.segments.lastOrNull()
        // 복원 뒤 같은 사건이 다시 와도 구간이 쪼개지지 않게 한다
        if (last != null && last.endMs == null && last.type == type) return
        closeLast(trip, atMs)
        trip.segments += TripSegment(type, atMs, null)
    }

    /**
     * 못 본 시간은 어느 쪽에도 넣지 않으므로 운전은 마지막 기록에서 닫는다.
     * 휴식은 15분까지만 채워 준다. 시동을 끈 주차는 휴식 완료가 다음 시동 때 기록돼, 마지막 기록에서 닫으면 길이 0이 되고 휴식 행이 사라진다
     */
    private fun closeOnSilence(trip: Open, nextAtMs: Long) {
        val last = trip.segments.lastOrNull()
        if (last != null && last.endMs == null && last.type == SEGMENT_REST) {
            closeLast(trip, minOf(nextAtMs, maxOf(trip.lastAtMs, last.startMs + REST_MS)))
        } else {
            closeLast(trip, trip.lastAtMs)
        }
    }

    private fun closeLast(trip: Open, atMs: Long) {
        val last = trip.segments.lastOrNull() ?: return
        if (last.endMs == null) trip.segments[trip.segments.lastIndex] = last.copy(endMs = atMs)
    }

    /** [closed]는 다음 운행으로 넘어가 닫힌 운행이다. 구간 없이 닫힌 운행이 '진행 중'(null)으로 남으면 다음 날 오늘 목록에 샌다 */
    private fun record(trip: Open, closed: Boolean): TripRecord {
        val ended = trip.segments.lastOrNull()?.endMs ?: if (closed) trip.lastAtMs else null
        return TripRecord(
            // 서버 업로드(12.11)가 UUID를 요구하고 다시 접어도 같은 값이어야 rev가 이어진다
            tripId = UUID.nameUUIDFromBytes("trip-${trip.startMs}".toByteArray()).toString(),
            startedAtMs = trip.startMs,
            endedAtMs = ended,
            segments = trip.segments.toList(),
            stopAdvised = trip.stopAdvised,
            declined = trip.declined,
            restCompleted = trip.restCompleted,
            conditionStops = trip.conditionStops,
        )
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
