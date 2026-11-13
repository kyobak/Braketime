package com.conditiondrive.vehicle.ui.model

import androidx.compose.runtime.Immutable
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.EngineView
import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.poi.PlaceCandidate
import com.conditiondrive.core.voice.Formats
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.port.UxLimits

enum class StatusKind { NORMAL, CAUTION, STOP, RECOVER }

fun DriverState.toStatus(): StatusKind = when (this) {
    DriverState.NORMAL -> StatusKind.NORMAL
    DriverState.CAUTION -> StatusKind.CAUTION
    DriverState.STOP -> StatusKind.STOP
    DriverState.RECOVER -> StatusKind.RECOVER
}

fun StatusKind.badge(): Int = when (this) {
    StatusKind.NORMAL -> R.string.badge_normal
    StatusKind.CAUTION -> R.string.badge_caution
    StatusKind.STOP -> R.string.badge_stop
    StatusKind.RECOVER -> R.string.badge_recover
}

/** 분 단위로 내린 위치만 담는다. 값이 분 안에서 같으므로 화면도 분 단위로만 다시 그려진다 */
@Immutable
data class HorizonModel(
    val ticks: List<Tick>,
    val nowFraction: Float,
    val nowLabel: UiText,
) {
    @Immutable
    data class Tick(val fraction: Float, val label: String)
}

/** 크게 보여 줄 값. 시간이면 숫자 display + 단위 headlineM, 문장이면 headlineM */
@Immutable
sealed interface BigValue {
    data class Time(val text: String) : BigValue
    data class Sentence(val text: UiText) : BigValue
}

@Immutable
data class S1Side(
    val placeName: String?,
    val placeDistance: UiText?,
    val routeLine: UiText?,
    val horizon: HorizonModel,
)

@Immutable
data class S1Model(
    val status: StatusKind,
    val label: UiText,
    val notes: List<UiText>,
    val big: BigValue,
    val placeLine: UiText?,
    val side: S1Side?,        // null이면 오른쪽 창을 비운다
    val listening: Boolean = false,
)

/** 8.2 표 그대로. EngineView만 읽고 저장 상태는 모른다 */
object S1Presenter {
    fun present(
        view: EngineView,
        place: PlaceCandidate? = null,
        routeRemainingSec: Int? = null,
        listening: Boolean = false,
        limits: UxLimits? = null,
    ): S1Model {
        val status = view.state.toStatus()
        val (label, big) = when (view.state) {
            // 시간을 세지 못하는데 "여유 2시간"을 계속 보이면 거짓이다(13장 기어·차속 모두 없음)
            DriverState.NORMAL, DriverState.CAUTION -> if (view.noDriveSignal) {
                UiText.res(R.string.s1_no_signal) to BigValue.Sentence(UiText.res(R.string.s1_no_signal_value))
            } else {
                UiText.res(R.string.s1_next_rest) to BigValue.Time(Formats.s1Time(view.remainingToRestSec ?: 0))
            }
            DriverState.STOP -> stopLabel(view) to BigValue.Sentence(UiText.res(R.string.s1_take_rest))
            DriverState.RECOVER -> recoverContent(view)
        }
        return S1Model(
            status = status,
            // 라벨을 바꾸면 "듣고 있어요 1시간 30분"이 되어 큰 시간의 뜻이 사라진다
            label = label,
            listening = listening,
            notes = buildList {
                // 체크인만 사유면 옆 숫자는 1시간이 넘게 남았는데 띠는 주의다. 왜인지 적는다. 혈당 단어는 없다
                if (view.state == DriverState.CAUTION && view.reasons == setOf(Reason.CHECKIN)) add(UiText.res(R.string.s1_checkin_reason))
                if (view.glucoseSimulated) add(UiText.res(R.string.s1_simulated_data))
                if (view.glucoseUnavailable) add(UiText.res(R.string.s1_link_lost))
                if (view.measuredSinceAppStart) add(UiText.res(R.string.s1_measured_since_start))
            },
            big = big,
            placeLine = place?.takeIf { view.state == DriverState.STOP }?.let {
                UiText.res(R.string.place_line, UiText.res(UiFormat.placeKind(it.place.kind)), distanceText(it))
            },
            side = if (limits?.limitStringLength == true) null else side(view, place, routeRemainingSec),
        )
    }

    private fun stopLabel(view: EngineView): UiText =
        if (isGlucoseStop(view)) UiText.res(R.string.s1_stop_safely)
        else UiText.res(R.string.s1_driving_for, Formats.driveTime(view.continuousDriveSec))

    /**
     * 뷰에는 트랙 값이 없어 값으로 가른다. G=CAUTION(값 ≥ 5.0)에 피로 STOP이 겹친 경우만 운전 시간을 말한다.
     * 값이 없으면 안전한 쪽 문구다. 어느 쪽이든 혈당 단어는 없다.
     */
    private fun isGlucoseStop(view: EngineView): Boolean {
        if (Reason.GLUCOSE !in view.reasons) return false
        val value = view.glucoseMmol ?: return true
        return view.glucoseRecoveryRemainingSec != null || value < Policy.DEFAULT.GLUCOSE_RECOVER_AT
    }

    /** 경로로 재지 못한 거리는 "약"을 붙인다. 쉴 곳까지 얼마나 더 버틸지 판단하는 값이다 */
    private fun distanceText(place: PlaceCandidate): UiText =
        if (place.routeDistanceM != null) UiText.Plain(UiFormat.km(place.distanceM))
        else UiText.res(R.string.km_approx, UiFormat.km(place.distanceM))

    /** 시연 전용. 소리 없이 봐도 왜 주의인지 알 수 있게 S1 오른쪽 창에 값을 보인다 */
    fun demoGlucose(view: EngineView): UiText? {
        val value = view.glucoseMmol ?: return null
        val trend = view.glucoseTrend?.let { UiText.res(UiFormat.trend(it)) }
            ?: return UiText.res(R.string.glucose_value, Formats.mgdl(value), UiFormat.mmol(value))
        return UiText.res(R.string.glucose_value_long, Formats.mgdl(value), UiFormat.mmol(value), trend)
    }

    private fun recoverContent(view: EngineView): Pair<UiText, BigValue> {
        // S3와 같은 조건이다. 혈당만 사유면 15분은 회복 조건이 아니라 음성(40분)과 어긋난다
        val fatigueOrCheckin = Reason.FATIGUE in view.reasons || Reason.CHECKIN in view.reasons
        val rest = view.restRemainingSec?.takeIf { it > 0 && (fatigueOrCheckin || !view.hasGlucoseReason()) }
        val recovery = view.glucoseRecoveryRemainingSec?.takeIf { it > 0 }
        return when {
            rest != null -> UiText.res(R.string.s1_rest_left) to BigValue.Time(Formats.s1Time(rest))
            recovery != null -> UiText.res(R.string.s1_recovery_wait) to BigValue.Time(Formats.s1Time(recovery))
            else -> UiText.res(R.string.s1_recovery_wait) to BigValue.Sentence(UiText.res(R.string.s1_take_rest))
        }
    }

    private fun side(view: EngineView, place: PlaceCandidate?, routeRemainingSec: Int?) = S1Side(
        placeName = place?.let { Formats.placeName(it.place.name) },
        placeDistance = place?.let(::distanceText),
        routeLine = routeRemainingSec?.let { UiText.res(R.string.s1_route_left, Formats.driveTime(it.toLong())) },
        horizon = driveHorizon(view),
    )

    fun driveHorizon(view: EngineView): HorizonModel {
        val end = view.stopThresholdSec.coerceAtLeast(1)
        val minutes = (view.continuousDriveSec / 60) * 60
        return HorizonModel(
            ticks = listOf(
                HorizonModel.Tick(0f, "0"),
                HorizonModel.Tick(view.cautionThresholdSec.toFloat() / end, UiFormat.clock(view.cautionThresholdSec)),
                HorizonModel.Tick(1f, UiFormat.clock(end)),
            ),
            nowFraction = (minutes.toFloat() / end).coerceIn(0f, 1f),
            nowLabel = UiText.res(R.string.now_mark),
        )
    }
}
