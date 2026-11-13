package com.conditiondrive.vehicle.ui.model

import androidx.compose.runtime.Immutable
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.voice.Formats
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.port.TripRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 14장 표시 규칙. 동승자가 없다고 확인된 때만 수치를 보인다 */
fun numbersHidden(passengerPresent: Boolean?): Boolean = passengerPresent != false

fun EngineView.hasGlucoseReason(): Boolean = Reason.GLUCOSE in reasons || Reason.GLUCOSE_UNCONFIRMED in reasons

private fun minutesAgo(atMs: Long, nowMs: Long): Long = ((nowMs - atMs).coerceAtLeast(0)) / 60_000

private fun measuredAgo(atMs: Long, nowMs: Long): UiText {
    val minutes = minutesAgo(atMs, nowMs)
    return if (minutes < 1) UiText.res(R.string.measured_now) else UiText.res(R.string.measured_ago, minutes.toInt())
}

/** 범위 문구. 8.3 S2 "기준 위", "기준에 가까움", "기준 아래", "회복 대기" */
private fun rangeText(view: EngineView, value: Double): UiText {
    val p = Policy.DEFAULT
    return when {
        value < p.GLUCOSE_STOP_BELOW -> UiText.res(R.string.range_below)
        view.glucoseRecoveryRemainingSec != null || view.preDepartureHold -> UiText.res(R.string.range_waiting)
        // 뷰에 G 값이 없다. G=CAUTION은 상태가 CAUTION이고 G=STOP은 상태가 STOP 또는 RECOVER다
        Reason.GLUCOSE in view.reasons && value < p.GLUCOSE_RECOVER_AT && view.state != DriverState.CAUTION ->
            UiText.res(R.string.range_waiting)
        Reason.GLUCOSE in view.reasons -> UiText.res(R.string.range_near)
        else -> UiText.res(R.string.range_above)
    }
}

/** 화면 문구의 "15분"도 판단과 같은 값에서 읽는다 */
val REST_RESET_MINUTES: Int = (Policy.DEFAULT.REST_RESET_SEC / 60).toInt()

@Immutable
data class GlucoseRow(val value: UiText, val trend: UiText?, val simulated: UiText?)

/** 동승자가 있거나 모르면 수치뿐 아니라 "혈당"이라는 말도 쓰지 않는다. 혈당을 관리한다는 사실 자체가 건강 정보다(F11) */
private fun simulatedNote(view: EngineView, hidden: Boolean): UiText? = when {
    !view.glucoseSimulated -> null
    hidden -> UiText.res(R.string.s1_simulated_data)
    else -> UiText.res(R.string.glucose_simulated)
}

/** S2 혈당 줄. 받은 적이 없으면 행을 빼 1단계 사용자에게 보이지 않는다 */
fun glucoseRow(view: EngineView, hidden: Boolean): GlucoseRow? {
    val value = view.glucoseMmol
    return when {
        value != null && hidden -> GlucoseRow(rangeText(view, value), null, simulatedNote(view, hidden = true))
        value != null -> GlucoseRow(
            UiText.res(R.string.glucose_value, Formats.mgdl(value), UiFormat.mmol(value)),
            view.glucoseTrend?.let { UiText.res(UiFormat.trend(it)) },
            simulatedNote(view, hidden = false),
        )
        view.glucoseUnavailable -> GlucoseRow(
            UiText.res(if (hidden) R.string.glucose_unavailable_hidden else R.string.glucose_unavailable),
            null,
            null,
        )
        else -> null
    }
}

@Immutable
data class S2Model(
    val hold: Boolean,
    val checkin: UiText,
    val sleep: UiText?,
    val advancedNote: UiText?,
    val glucose: GlucoseRow?,
    val cautionAt: String,
    val stopAt: String,
    val phone: String?,
    val measured: UiText?,
    val showSleepSource: Boolean,
    val glucoseLabel: UiText = UiText.res(R.string.glucose),
    val glucoseSourceAsButton: Boolean = false,
    // U-29. 수면 행이 없는데 앞당김이 남은 경우의 사유 줄. 창 1개에서도 보이도록 왼쪽에 둔다
    val advanceKeptNote: UiText? = null,
    // U-28. 1시간 45분은 화물차 규정이 아니라 이 앱의 설계값이다. 앞당김과 상관없이 기본값을 적는다
    val cautionSource: UiText = cautionSourceLine(),
)

/** 화면에 1시간 45분을 직접 쓰지 않는다. 앞당김이면 규칙 행은 1:15가 되므로 기본값은 Policy에서 읽는다 */
fun cautionSourceLine(): UiText = UiText.res(R.string.source_caution, Formats.driveTime(Policy.DEFAULT.FATIGUE_CAUTION_SEC))

object S2Presenter {
    fun present(view: EngineView, passengerPresent: Boolean?, phoneName: String?, nowMs: Long): S2Model {
        val hidden = numbersHidden(passengerPresent)
        val sleep = view.sleepMinutes
        return S2Model(
            hold = view.preDepartureHold,
            checkin = UiText.res(UiFormat.checkin(view.lastCheckin)),
            sleep = sleep?.let {
                when {
                    !hidden -> UiText.Plain(Formats.driveTime(it * 60L))
                    it < Policy.DEFAULT.SLEEP_SHORT_MINUTES -> UiText.res(R.string.s2_sleep_short)
                    else -> UiText.res(R.string.s2_sleep_enough)
                }
            },
            advancedNote = view.advanced?.let { UiText.res(R.string.s2_advanced, (Policy.DEFAULT.ADVANCE_SEC / 60).toInt()) },
            glucose = glucoseRow(view, hidden),
            cautionAt = Formats.driveTime(view.cautionThresholdSec),
            stopAt = Formats.driveTime(view.stopThresholdSec),
            phone = phoneName,
            measured = view.glucoseMeasuredAtMs?.let { measuredAgo(it, nowMs) },
            showSleepSource = sleep != null || view.advanced != null,
            glucoseLabel = UiText.res(if (hidden) R.string.glucose_hidden else R.string.glucose),
            glucoseSourceAsButton = hidden,
            advanceKeptNote = when {
                view.advanced == null || sleep != null -> null
                // 쌓인 운전이 있으면 15분 휴식이 운전 시간과 함께 앞당김을 푼다(resetFatigue)
                view.continuousDriveSec > 0 ->
                    UiText.res(R.string.s2_advance_kept, REST_RESET_MINUTES, (Policy.DEFAULT.ADVANCE_SEC / 60).toInt())
                // 쌓인 운전이 0이면 쉬어도 풀 것이 없다. 다음 시동에서 풀리지만(releaseExpiredAdvance) 시동 신호가 없는 차종이 있어 시점은 말하지 않는다
                else -> UiText.res(R.string.s2_advance_kept_idle, (Policy.DEFAULT.ADVANCE_SEC / 60).toInt())
            },
        )
    }
}

/** 메모리에만 있는 정지 그래프 입력. 혈당 이력은 어디에도 저장하지 않는다(14장) */
@Immutable
data class GlucosePoint(val atMs: Long, val mmol: Double)

object GlucoseHistory {
    private const val MAX_POINTS = 240
    private const val WINDOW_MS = 6 * 60 * 60_000L

    /**
     * 값이 끊긴 동안은 곡선을 남긴다. 연결을 해제해 값이 사라지면 함께 지운다(S5 안내 문구).
     * 시각이 역행하면 다른 출처의 값이다. 이어 붙이면 절벽이 그려지니 새 곡선으로 시작한다.
     */
    fun append(history: List<GlucosePoint>, view: EngineView): List<GlucosePoint> {
        val at = view.glucoseMeasuredAtMs
        val value = view.glucoseMmol
        if (at == null || value == null) return if (view.glucoseUnavailable) history else emptyList()
        val last = history.lastOrNull()?.atMs
        if (last == at) return history
        val base = if (last != null && at < last) emptyList() else history
        return (base + GlucosePoint(at, value)).filter { at - it.atMs <= WINDOW_MS }.takeLast(MAX_POINTS)
    }
}

@Immutable
data class S3Model(
    val status: StatusKind,
    val hold: Boolean,
    val label: UiText,
    val big: BigValue,
    val horizon: HorizonModel?,
    val hint: UiText?,
    val glucoseLine: UiText?,
    val simulated: UiText?,
    val glucoseDetail: S3GlucoseDetail?,
    val cautionAt: String,
    val stopAt: String,
    val trendTitle: UiText = UiText.res(R.string.s3_trend_title),
    val showRestSource: Boolean = true,
)

/** S3 오른쪽 창과 혈당 사유 줄 */
@Immutable
data class S3GlucoseDetail(
    val showGraph: Boolean,
    val recoveryLine: UiText?,
    val receivedLine: UiText?,
    val sourceAsButton: Boolean,
    /** 회복 타이머가 왼쪽 큰 숫자다. 창 2개면 오른쪽 회복 줄이 같은 값을 한 번 더 말한다 */
    val recoveryShownBig: Boolean = false,
)

object S3Presenter {
    fun present(view: EngineView, passengerPresent: Boolean?, nowMs: Long): S3Model {
        val p = Policy.DEFAULT
        val hidden = numbersHidden(passengerPresent)
        val glucoseReason = view.hasGlucoseReason()
        val fatigueOrCheckin = Reason.FATIGUE in view.reasons || Reason.CHECKIN in view.reasons
        val rest = view.restRemainingSec?.takeIf { it > 0 && (fatigueOrCheckin || !glucoseReason) }
        val recovery = view.glucoseRecoveryRemainingSec?.takeIf { it > 0 }
        val waitMinutes = (p.GLUCOSE_WAIT_SEC / 60).toInt()
        val unavailable = glucoseReason && view.glucoseMmol == null

        val (label, big) = when {
            rest != null -> UiText.res(R.string.s1_rest_left) to BigValue.Time(Formats.s1Time(rest))
            recovery != null -> UiText.res(R.string.s3_recovery_left, waitMinutes) to BigValue.Time(Formats.s1Time(recovery))
            unavailable -> UiText.res(R.string.s1_recovery_wait) to BigValue.Sentence(UiText.res(R.string.s1_take_rest))
            else -> UiText.res(R.string.s1_recovery_wait) to BigValue.Sentence(UiText.res(R.string.s3_wait_value, waitMinutes))
        }
        val horizon = when {
            rest != null -> elapsedHorizon(rest, p.REST_RESET_SEC)
            recovery != null -> elapsedHorizon(recovery, p.GLUCOSE_WAIT_SEC)
            else -> null
        }
        return S3Model(
            status = StatusKind.RECOVER,
            hold = view.preDepartureHold,
            label = label,
            big = big,
            horizon = horizon,
            hint = if (rest != null) UiText.res(R.string.s3_hint) else null,
            glucoseLine = glucoseLine(view, hidden, glucoseReason),
            simulated = simulatedNote(view, hidden).takeIf { glucoseReason },
            glucoseDetail = if (!glucoseReason) null else S3GlucoseDetail(
                showGraph = !hidden && view.glucoseMmol != null,
                recoveryLine = recovery?.let {
                    // "곧"은 s1Time 규칙이라 "40분 중 곧 남음"이 되면 문장이 깨진다
                    if (it < 60) UiText.res(R.string.s3_recovery_line_soon, waitMinutes)
                    else UiText.res(R.string.s3_recovery_line, waitMinutes, Formats.s1Time(it))
                },
                receivedLine = view.glucoseMeasuredAtMs?.let { receivedAgo(it, nowMs) },
                sourceAsButton = hidden,
                recoveryShownBig = rest == null && recovery != null,
            ),
            cautionAt = Formats.driveTime(view.cautionThresholdSec),
            stopAt = Formats.driveTime(view.stopThresholdSec),
            trendTitle = UiText.res(if (hidden) R.string.s3_trend_title_hidden else R.string.s3_trend_title),
            // 혈당 40분이 크게 보이는 화면 아래에 화물차 기준을 달면 40분이 그 규정에서 온 것처럼 읽힌다
            showRestSource = rest != null || !glucoseReason,
        )
    }

    private fun receivedAgo(atMs: Long, nowMs: Long): UiText {
        val minutes = minutesAgo(atMs, nowMs)
        return if (minutes < 1) UiText.res(R.string.received_now) else UiText.res(R.string.received_ago, minutes.toInt())
    }

    private fun glucoseLine(view: EngineView, hidden: Boolean, glucoseReason: Boolean): UiText? {
        if (!glucoseReason) return null
        val value = view.glucoseMmol
            ?: return UiText.res(if (hidden) R.string.s3_unavailable_hidden else R.string.s3_unavailable)
        if (hidden) return rangeText(view, value)
        val trend = view.glucoseTrend?.let { UiText.res(UiFormat.trend(it)) }
            ?: return UiText.res(R.string.glucose_value, Formats.mgdl(value), UiFormat.mmol(value))
        return UiText.res(R.string.glucose_value_long, Formats.mgdl(value), UiFormat.mmol(value), trend)
    }

    /**
     * 눈금은 "전체 - 크게 보이는 남은 분"이다. 둘을 따로 내림하면 더해서 전체가 되지 않는다. 0은 시작 라벨과 겹쳐 뺀다.
     * 지금 표시는 "N분 지남", 끝 눈금은 "0분"과 "40분"이다. 큰 숫자(남은 분)와 같은 화면에서 어느 쪽이 남은 시간인지 헷갈리지 않게 한다.
     */
    private fun elapsedHorizon(remainingSec: Long, totalSec: Long): HorizonModel {
        val totalMinutes = totalSec / 60
        val minutes = (totalMinutes - remainingSec.coerceAtLeast(0) / 60).coerceIn(0, totalMinutes)
        return HorizonModel(
            ticks = listOf(HorizonModel.Tick(0f, Formats.driveTime(0)), HorizonModel.Tick(1f, Formats.driveTime(totalSec))),
            nowFraction = (minutes.toFloat() / totalMinutes).coerceIn(0f, 1f),
            nowLabel = if (minutes == 0L) UiText.Plain("") else UiText.res(R.string.s3_elapsed, minutes.toInt()),
        )
    }
}

/** [note]는 합친 운전 안의 짧은 멈춤이다. 본문은 합친 운전 시간(엔진 연속 운전의 근사값, U-23)이다 */
@Immutable
data class S4Row(val time: String, val text: UiText, val note: UiText? = null)

/** S4 맨 위 "최근 7일". [trips]는 운행 수(라벨 "운행", 값 "N회, 운전 …"), [driveTotal]은 운전 시간이다(U-24). null이면 그 기간 운행도 안내도 15분 이상 휴식도 없다 */
@Immutable
data class S4Recent(val trips: Int, val driveTotal: String, val advised: Int, val rested: Int)

@Immutable
data class S4Model(
    val recent: S4Recent?,
    val driveTotal: String,
    val restCount: Int,
    val advisedCount: Int,
    val conditionStops: Int,
    val distanceKm: Int?,
    val rows: List<S4Row>,
    val gapNote: Boolean,
) {
    /** 오늘 운행이 없다. 합계가 모두 0이라 빈 상태 문구와 같은 말을 두 번 하게 된다 */
    val todayEmpty: Boolean
        get() = rows.isEmpty() && restCount == 0 && advisedCount == 0 && conditionStops == 0 && distanceKm == null && !gapNote
}

object S4Presenter {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun present(
        trips: List<TripRecord>,
        nowMs: Long,
        zone: ZoneId,
        distanceKm: Double? = null,
        gapNote: Boolean = false,
        recent: List<TripRecord> = emptyList(),
    ): S4Model {
        val week = RecentSummary.of(recent, nowMs, zone)
        return S4Model(
            recent = if (week.isEmpty) null else S4Recent(week.trips, Formats.driveTime(week.driveSec), week.advised, week.rested),
            driveTotal = Formats.driveTime(TripTimeline.driveSec(trips, nowMs)),
            restCount = TripTimeline.restCount(trips, nowMs),
            advisedCount = trips.sumOf { it.stopAdvised },
            conditionStops = trips.sumOf { it.conditionStops },
            distanceKm = distanceKm?.toInt(),
            rows = TripTimeline.entries(trips, nowMs).map { row(it, zone) },
            gapNote = gapNote,
        )
    }

    private fun row(entry: TimelineEntry, zone: ZoneId): S4Row {
        val time = TIME.format(Instant.ofEpochMilli(entry.startMs).atZone(zone))
        val length = Formats.driveTime(entry.sec)
        return when (entry) {
            is TimelineEntry.Drive -> S4Row(
                time,
                UiText.res(R.string.s4_seg_drive, length),
                when (entry.pauses) {
                    0 -> null
                    1 -> UiText.res(R.string.s4_seg_pause_note, Formats.driveTime(entry.pauseSec))
                    else -> UiText.res(R.string.s4_seg_pauses_note, entry.pauses, Formats.driveTime(entry.pauseSec))
                },
            )
            is TimelineEntry.Rest -> S4Row(time, UiText.res(R.string.s4_seg_rest, length))
            is TimelineEntry.Pause -> S4Row(time, UiText.res(R.string.s4_seg_pause, length))
        }
    }
}
