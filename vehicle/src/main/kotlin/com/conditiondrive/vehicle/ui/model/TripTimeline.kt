package com.conditiondrive.vehicle.ui.model

import androidx.compose.runtime.Immutable
import com.conditiondrive.core.engine.Policy
import com.conditiondrive.vehicle.port.TripRecord
import com.conditiondrive.vehicle.port.TripSegment

/**
 * S4 목록 한 줄. 저장된 구간을 엔진의 말로 다시 묶은 것이다(U-23).
 * - [Drive]: 엔진이 이어서 센 운전 한 덩어리. 15분 미만 멈춤은 연속 운전을 끊지 않으므로(E27) 앞뒤 운전을 합친다.
 *   길이는 엔진 연속 운전의 근사값이다. 출발 유예(최대 30초)는 확정 때 기록돼 앞 정차에 들어가고, 기어 신호 없는 차종의 대체 정차는 엔진이 운전으로 센다.
 * - [Rest]: 15분을 채운 정차. "휴식"이라는 말은 이것에만 쓴다.
 * - [Pause]: 운전 앞뒤에 남은 15분 미만 정차(진행 중이거나 운행 끝).
 */
@Immutable
sealed interface TimelineEntry {
    val startMs: Long
    val sec: Long

    /** [pauses]는 합친 운전 사이의 1분 이상 멈춤 횟수, [pauseSec]는 그 합이다 */
    data class Drive(override val startMs: Long, override val sec: Long, val pauses: Int, val pauseSec: Long) : TimelineEntry
    data class Rest(override val startMs: Long, override val sec: Long) : TimelineEntry
    data class Pause(override val startMs: Long, override val sec: Long) : TimelineEntry
}

/**
 * 오늘 목록, 오늘 합계, 최근 7일이 같은 규칙으로 센다. 엔진의 REST_COMPLETED 횟수(`restCompleted`)는 서버 업로드에만 쓴다.
 * 그 사건은 대체 정차나 피곤 응답 뒤 재휴식에서 한 정차에 두 번 나올 수 있어, 목록의 "휴식 32분" 한 줄 옆에 "2회"가 서게 된다.
 */
object TripTimeline {
    val REST_SEC: Long = Policy.DEFAULT.REST_RESET_SEC

    /** 이보다 짧은 구간은 목록에 행으로 두지 않는다. "운전 0분"은 읽는 사람에게 뜻이 없다 */
    const val MIN_ROW_SEC = 60L

    private const val SEGMENT_DRIVE = "drive"

    /** 지금 뒤로 넘어간 끝은 시계 되감김 흔적이라 지금에서 자른다 */
    fun durationSec(segment: TripSegment, nowMs: Long): Long =
        ((segment.endMs ?: nowMs).coerceAtMost(nowMs) - segment.startMs).coerceAtLeast(0) / 1000

    /** 합계 시간. 1분 미만 운전도 들어간다(목록에서만 뺀다) */
    fun driveSec(trips: List<TripRecord>, nowMs: Long): Long =
        trips.flatMap { it.segments }.filter { it.type == SEGMENT_DRIVE }.sumOf { durationSec(it, nowMs) }

    fun restCount(trips: List<TripRecord>, nowMs: Long): Int = entries(trips, nowMs).count { it is TimelineEntry.Rest }

    /**
     * 운전이 1분 이상인 운행만 센다. 시동 후 바로 끝낸 운행을 세면 7일 요약은 "운행 1회"인데 오늘 목록은 비어 서로 다른 것을 센다.
     * 이 기준을 넘는 운행은 늘 목록에 운전 행이나 휴식 행을 남긴다(휴식으로만 갈리고 그 밖의 운전은 하나로 합치므로).
     */
    fun tripCount(trips: List<TripRecord>, nowMs: Long): Int = trips.count { driveSec(listOf(it), nowMs) >= MIN_ROW_SEC }

    /** 운행마다 묶는다. 운행 경계(마지막 기록에서 4시간 이상 무기록, 출발 때 날짜 변경)를 넘어서는 합치지 않는다 */
    fun entries(trips: List<TripRecord>, nowMs: Long): List<TimelineEntry> =
        trips.flatMap { entriesOf(it.segments.sortedBy { s -> s.startMs }, nowMs) }
            .filter { it.sec >= MIN_ROW_SEC }
            .sortedBy { it.startMs }

    private fun entriesOf(segments: List<TripSegment>, nowMs: Long): List<TimelineEntry> {
        val out = mutableListOf<TimelineEntry>()
        var drive: TimelineEntry.Drive? = null
        // 합친 운전 안의 멈춤. 운전 합이 1분 미만이라 행을 버릴 때 멈춤까지 사라지지 않게 멈춤 행으로 풀어 낸다
        val inner = mutableListOf<TimelineEntry.Pause>()
        // 운전 뒤의 짧은 정차는 다음 운전이 오는지 보고 정한다. 오면 합치고, 안 오면 멈춤 행이다
        var pending: TripSegment? = null

        fun flushDrive() {
            val current = drive ?: return
            if (current.sec >= MIN_ROW_SEC) out += current else out += inner
            inner.clear()
            drive = null
        }

        fun flushPending() {
            val stop = pending ?: return
            out += stopEntry(stop.startMs, durationSec(stop, nowMs))
            pending = null
        }

        for (segment in segments) {
            val sec = durationSec(segment, nowMs)
            if (segment.type == SEGMENT_DRIVE) {
                val current = drive
                val stop = pending
                drive = when {
                    current == null -> TimelineEntry.Drive(segment.startMs, sec, 0, 0)
                    stop == null -> current.copy(sec = current.sec + sec)
                    else -> {
                        val stopSec = durationSec(stop, nowMs)
                        inner += TimelineEntry.Pause(stop.startMs, stopSec)
                        // 1분 미만 멈춤은 횟수에도 넣지 않는다. 목록에서 빼는 규칙과 같다
                        val counted = stopSec >= MIN_ROW_SEC
                        current.copy(
                            sec = current.sec + sec,
                            pauses = current.pauses + if (counted) 1 else 0,
                            pauseSec = current.pauseSec + if (counted) stopSec else 0,
                        )
                    }
                }
                pending = null
            } else if (drive == null || sec >= REST_SEC) {
                flushDrive()
                flushPending()
                out += stopEntry(segment.startMs, sec)
            } else {
                pending = segment
            }
        }
        flushDrive()
        flushPending()
        return out
    }

    private fun stopEntry(startMs: Long, sec: Long): TimelineEntry =
        if (sec >= REST_SEC) TimelineEntry.Rest(startMs, sec) else TimelineEntry.Pause(startMs, sec)
}
