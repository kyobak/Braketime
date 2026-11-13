package com.conditiondrive.companion.ui

import com.conditiondrive.companion.AppUi
import com.conditiondrive.companion.CompanionController
import com.conditiondrive.companion.Notice
import com.conditiondrive.companion.pairing.ClaimResult
import com.conditiondrive.companion.simulator.GlucoseUnit
import com.conditiondrive.companion.simulator.GlucoseUnits
import com.conditiondrive.companion.simulator.SimulatorState
import com.conditiondrive.companion.sleep.HealthAvailability
import com.conditiondrive.companion.upload.UploadStatus
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.contract.SleepPolicy
import com.conditiondrive.core.voice.Formats
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 모든 사용자 문장. 화면과 분리해 "시뮬레이션 표시 누락"과 금칙어를 JVM에서 검사한다 */
object Copy {
    const val NOTIFICATION_CHANNEL = "시뮬레이션 전송"
    const val NOTIFICATION_TITLE = "시뮬레이션 전송 중이에요"

    // C1
    const val CONSENT_TITLE = "내 컨디션 요약, 차로 보낼까요?"
    val CONSENT_ITEMS = listOf(
        "받는 정보" to "어젯밤 수면 시간, 현재 혈당값과 추세",
        "쓰는 곳" to "운전 중 휴식과 정차 안내",
        "거치는 곳" to "측정값이 서버를 거쳐 내 차로 가요",
        // B-11. 현재 측정값은 측정 시각과 함께 서버를 지난다. 원본이 폰에만 있다고 쓰면 그 값까지 폰에 머문다고 읽혀 쓰지 않는다.
        // 사실인 것만 적는다: 서버는 계정당 최신 1건을 통째로 바꿔 쓰고(이력 없음), 유효 시각이 지나면 정리 작업이 지운다.
        // 수면 유효 시각은 다음 04:00이고 6시간이 안 남으면 하루를 더한다(C-36). "그날까지"는 늦게 보낸 경우와 맞지 않는다
        "보관" to "서버에는 최신 값 하나만 남고 이력은 남기지 않아요. 혈당은 15분, 수면은 다음 새벽 4시까지 남아요(밤 10시 넘어 보내면 하루 더)",
        "거부할 권리" to "동의하지 않아도 차량 앱의 시간 기준 안내는 그대로 작동해요",
    )
    /** "보관" 값에서 굵게 보일 낱말. 얼마나 남고 얼마 동안인지가 판단의 핵심이다 */
    val CONSENT_EMPHASIS = listOf("최신 값 하나만", "이력은 남기지 않아요", "15분", "다음 새벽 4시까지")
    const val CONSENT_HEART = "심박은 이 폰 화면에만 보여 드려요. 차로 보내지 않아요."
    const val CONSENT_CHECK = "건강정보를 위 내용대로 보내는 데 동의해요 (민감정보 별도 동의)"
    const val CONSENT_NEED_CHECK = "동의하려면 위 칸에 표시해 주세요."
    const val CONSENT_ACCEPT = "동의하고 계속"
    const val CONSENT_DECLINE = "동의하지 않기"
    const val DECLINED_TITLE = "동의하지 않았어요"
    const val DECLINED_BODY = "이 앱은 아무것도 보내지 않아요. 차량 앱은 시간 기준 안내로 그대로 작동해요."
    const val DECLINED_BACK = "동의 내용 다시 보기"

    // C2
    const val PAIR_TITLE = "차와 연결"
    const val PAIR_GUIDE = "차량 설정의 스마트폰 연결 화면에 나온 QR을 찍거나 8자 코드를 입력해 주세요."
    const val PAIR_CONFIRM = "차량 화면의 코드와 같은지 확인한 뒤 연결해 주세요."
    const val PAIR_CODE_LABEL = "연결 코드"
    const val PAIR_CODE_HINT = "8자 코드"
    const val PAIR_SCAN = "QR 찍기"
    const val PAIR_SCAN_CLOSE = "QR 닫기"
    const val PAIR_SUBMIT = "연결"
    const val PAIR_BUSY = "연결하는 중이에요"
    const val PAIR_NO_CAMERA = "카메라를 쓸 수 없어요. 코드를 직접 입력해 주세요."
    const val PAIR_REPLACE_TITLE = "다른 차로 연결을 바꿀까요?"
    const val PAIR_REPLACE_BODY = "지금 차와의 연결은 끊어지고, 요약은 새 차로 가요."
    const val PAIR_REPLACE_CANCEL = "지금 연결 유지"
    const val SERVER_LABEL = "서버 주소"
    const val SERVER_SAVE = "주소 저장"
    const val SERVER_REJECTED = "이 주소는 쓸 수 없어요."
    const val SERVER_SAVED = "저장했어요. 이 주소로 연결해요."

    /** null은 아직 저장을 누르지 않았거나 고쳐 쓰는 중이다 */
    fun serverSaveNote(accepted: Boolean?): String? = when (accepted) {
        null -> null
        true -> SERVER_SAVED
        false -> SERVER_REJECTED
    }

    // 공통 고지(8.3)
    const val NOTICE_ALARM = "차 안내는 CGM 앱 알람을 대신하지 않아요. 알람을 그대로 켜 두세요."
    const val NOTICE_HIGH = "혈당이 높을 때는 안내하지 않아요."

    // C3
    const val TAB_STATUS = "상태"
    const val TAB_SIMULATOR = "시뮬레이터"
    const val TAB_DATA = "데이터"
    const val STATUS_TITLE = "컨디션 요약"
    const val SLEEP_LABEL = "어젯밤 수면"
    const val HEART_LABEL = "심박"
    const val GLUCOSE_LABEL = "혈당 (시뮬레이션)"
    const val UPLOAD_LABEL = "마지막 전송"
    // B-11. C1 "보관"과 같은 사실을 같은 말로 적는다. 화면마다 말이 다르면 어느 쪽이 맞는지 묻게 된다
    const val SENT_SCOPE = "보내는 것은 수면 시간, 혈당값과 추세뿐이에요. 서버에는 최신 값 하나만 남고 이력은 남기지 않아요."
    const val SLEEP_ALLOW = "수면 정보 허용"
    const val SLEEP_SYNC = "수면 다시 읽기"
    const val SLEEP_UPDATE_PROVIDER = "Health Connect 업데이트"
    const val PAUSE = "전송 중단"
    const val RESUME = "전송 다시 시작"
    const val PAUSED_NOTE = "이미 보낸 요약은 혈당 15분, 수면 다음 새벽 4시까지(밤 10시 넘어 보냈으면 하루 더) 서버에 남아요. 바로 지우려면 데이터 탭에서 지워 주세요."
    const val SIM_RUNNING_MARKER = "시뮬레이션 혈당 전송 중"
    // 센서 끊김 재현·혈당 없는 재생은 켜져 있어도 혈당이 요약에 실리지 않는다. 이때 "전송 중"이라 하면 거짓이다
    const val SIM_IDLE_MARKER = "시뮬레이터 실행 중 · 혈당 보내지 않음"
    const val SIM_SLEEP_NOTE = "수면 시뮬레이션 값이에요"

    // C4
    const val SIM_TITLE = "혈당 시뮬레이터"
    const val SIM_ALWAYS = "시뮬레이션"
    const val SIM_HONEST = "실제 측정값이 아니에요. 시연을 위해 직접 만든 값이고, 차량 화면에도 시뮬레이션으로 표시돼요."
    const val SIM_START = "시뮬레이터 켜기"
    const val SIM_STOP = "시뮬레이터 끄기"
    const val SIM_VALUE = "값"
    const val SIM_PRESETS = "기준 값"
    const val SIM_TREND = "추세"
    const val SIM_UNIT = "단위"
    const val SIM_CUT_ON = "센서 끊김 재현"
    const val SIM_CUT_OFF = "센서 끊김 풀기"
    const val SIM_SCENARIO = "시나리오 재생 (실제 시간)"
    const val SIM_SC1 = "SC1 혈당이 내려갔다 회복"
    const val SIM_SC3 = "SC3 수면 부족"
    const val SIM_SKIP = "다음 변화로 건너뛰기"
    const val SIM_SLEEP = "수면 시뮬레이션"
    const val SIM_SLEEP_OFF = "끔"
    const val MMOL = "mmol/L"
    const val MGDL = "mg/dL"

    // 데이터
    const val DATA_TITLE = "데이터와 동의"
    const val DATA_CONNECTED = "연결된 차"
    const val DATA_DELETE_SUMMARY = "서버의 요약 지우기"
    const val DATA_DELETE_SUMMARY_NOTE = "전송을 멈추고 서버에 있는 요약도 바로 지워요."
    const val DATA_UNPAIR = "연결 해제"
    const val DATA_UNPAIR_NOTE = "서버의 요약과 이 폰의 연결 정보를 지워요."
    const val DATA_WITHDRAW = "동의 철회하고 모두 지우기"
    const val DATA_WITHDRAW_NOTE = "서버의 요약과 이 폰에 저장한 수면 값, 연결 정보를 모두 지워요."
    const val DATA_CONFIRM = "한 번 더 누르면 실행해요"
    const val DATA_LICENSE = "글꼴 라이선스"
    const val DATA_LICENSE_NAME = "Pretendard · SIL OFL 1.1"
    const val DATA_SOURCE = "혈당 기준은 Diabetes Canada 2018 21장(캐나다 지침), 수면 6시간은 설계값이에요."
    const val CLOSE = "닫기"

    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun time(ms: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun trend(trend: GlucoseTrendDto?): String = when (trend) {
        GlucoseTrendDto.FALLING_FAST -> "빠르게 내려가는 중"
        GlucoseTrendDto.FALLING -> "내려가는 중"
        GlucoseTrendDto.FLAT -> "변화 적음"
        GlucoseTrendDto.RISING -> "오르는 중"
        null -> "추세 모름"
    }

    /** 정보 행에 보일 값. [present]가 false면 값 대신 왜 없는지 말하는 문장이다 */
    data class Shown(val text: String, val present: Boolean)

    /** C3 혈당 행의 값. 보내는 값이 없는 상태는 문장으로 쓴다("--" 금지) */
    // 분기 순서는 simStatus와 같다(끊김 먼저). 끊김과 혈당 없는 재생이 겹칠 때 C3와 C4가 다른 이유를 말하지 않게 한다
    fun glucoseValue(sim: SimulatorState): Shown = when {
        !sim.running -> Shown("혈당 정보 없음. 시뮬레이터가 꺼져 있어요", false)
        sim.sensorCut -> Shown("센서 끊김 재현 중, 혈당을 보내지 않아요", false)
        !sim.glucoseActive -> Shown("혈당 없이 재생 중이에요", false)
        else -> Shown("${value(sim.mmol)} $MMOL", true)
    }

    /** C3 혈당 행 설명. 값에 항상 시뮬레이션을 붙인다. 호출하는 쪽이 빠뜨릴 수 없게 문장에 넣었다 */
    fun glucoseNote(sim: SimulatorState): String {
        if (!glucoseValue(sim).present) return SIM_ALWAYS
        return "${shownMgdl(sim)} $MGDL · ${trend(sim.trend)} · $SIM_ALWAYS"
    }

    // 내림 변환이라 되돌린 값이 입력보다 작을 수 있다. 큰 숫자와 같은 수를 보여 한 화면에 98과 97이 같이 나오지 않게 한다
    private fun shownMgdl(sim: SimulatorState): Int = sim.enteredMgdl ?: GlucoseUnits.toMgdl(sim.mmol)

    /** 모든 탭 위 표지. 꺼져 있으면 없다. "전송 중"은 실제로 혈당이 요약에 실리는 상태(sendsGlucose)에서만 말한다 */
    fun runningMarker(sim: SimulatorState): String? = when {
        !sim.running -> null
        sim.sendsGlucose -> SIM_RUNNING_MARKER
        else -> SIM_IDLE_MARKER
    }

    /** C4 큰 숫자 바로 위 한 줄. 큰 숫자는 꺼져 있어도 켤 때 보낼 값으로 남으니, 지금 나가는지는 이 줄이 말한다 */
    fun simStatus(sim: SimulatorState): String = when {
        !sim.running -> "꺼짐 · 켜면 이 값을 보내요"
        sim.sensorCut -> "센서 끊김 재현 · 보내지 않아요"
        !sim.glucoseActive -> "혈당 없이 재생 · 보내지 않아요"
        else -> "보내는 중"
    }

    /** 2자리 소수까지 끝 0 없이. 차량 표시와 같은 값이 보이게 반올림하지 않는다 */
    fun value(mmol: Double): String {
        val hundredths = Math.round(mmol * 100)
        val whole = hundredths / 100
        val fraction = hundredths % 100
        return when {
            fraction % 10 == 0L -> "$whole.${fraction / 10}"
            else -> "$whole.${fraction.toString().padStart(2, '0')}"
        }
    }

    /** C4 큰 숫자, 그 옆 단위, 아래 한 줄. 같은 값을 세 번 되풀이하지 않게 아래 줄에는 다른 단위만 쓴다 */
    data class Big(val number: String, val unit: String, val line: String)

    // mg/dL 보기의 "보내는 값"은 실제로 나가는 상태에서만 붙인다. 꺼짐·끊김·혈당 없는 재생에서 붙이면 바로 위 상태 줄과 어긋난다
    fun bigValue(sim: SimulatorState): Big = when (sim.unit) {
        GlucoseUnit.MMOL -> Big(value(sim.mmol), MMOL, "${shownMgdl(sim)} $MGDL · ${trend(sim.trend)}")
        GlucoseUnit.MGDL -> {
            val mmol = "${value(sim.mmol)} $MMOL · ${trend(sim.trend)}"
            Big(shownMgdl(sim).toString(), MGDL, if (sim.sendsGlucose) "보내는 값 $mmol" else mmol)
        }
    }

    /** 값 칩. 빼기는 붙임표가 아닌 U+2212로 써야 더하기와 굵기·폭이 맞는다 */
    fun nudgeLabel(sim: SimulatorState, up: Boolean): String {
        val step = if (sim.unit == GlucoseUnit.MMOL) "0.1" else "1"
        return if (up) "+ $step" else "\u2212 $step"
    }

    fun sleepValue(ui: AppUi): Shown {
        val sleep = CompanionController.effectiveSleep(ui.stored, ui.simulator)
        if (sleep != null) return Shown(Formats.driveTime(sleep.minutes * 60L), true)
        return Shown(when {
            ui.health.availability == HealthAvailability.UNAVAILABLE -> "이 폰에서 Health Connect를 쓸 수 없어요"
            ui.health.availability == HealthAvailability.NEEDS_PROVIDER_UPDATE -> "Health Connect 업데이트가 필요해요"
            !ui.health.sleepPermission -> "수면 정보를 허용하지 않았어요"
            else -> "지난 24시간 수면 기록이 없어요"
        }, false)
    }

    fun sleepNote(ui: AppUi): String? {
        val sleep = CompanionController.effectiveSleep(ui.stored, ui.simulator) ?: return "수면 값이 없으면 휴식 기준을 앞당기지 않아요."
        val simulated = ui.simulator.running && ui.simulator.sleepOverrideMinutes != null
        val basis = if (SleepPolicy.isShort(sleep.minutes)) "기준 6시간보다 짧아요 (설계값)" else "기준 6시간 이상이에요 (설계값)"
        return if (simulated) "$basis · $SIM_SLEEP_NOTE" else basis
    }

    fun heartValue(ui: AppUi): Shown = ui.health.heart?.let { Shown("${it.bpm}회/분", true) } ?: Shown("심박 정보 없음", false)

    const val HEART_NOTE = "이 화면에만 보여요. 차로 보내지 않아요."

    fun lastUpload(ui: AppUi, zone: ZoneId): Shown =
        ui.stored.lastUploadAtMs?.let { Shown(time(it, zone), true) } ?: Shown("아직 보내지 않았어요", false)

    fun uploadNote(ui: AppUi): String? = when {
        ui.stored.uploadPaused -> PAUSED_NOTE
        else -> when (ui.upload) {
            UploadStatus.Retrying -> "연결 안 됨. 최신 값만 다시 보내 볼게요."
            UploadStatus.Rejected -> "이번 요약은 보내지 못했어요. 다음 값으로 다시 보낼게요."
            UploadStatus.ClockOff -> "폰 시계가 맞지 않아 보내지 못했어요. 날짜와 시간 설정을 확인해 주세요."
            UploadStatus.Delivered, UploadStatus.Idle -> null
        }
    }

    fun scenarioLine(sim: SimulatorState): String? {
        if (!sim.inScenario) return null
        val elapsed = clock(sim.scenarioElapsedMs)
        val next = sim.scenarioNextInMs
        if (next != null) return "$elapsed 경과. 다음 변화까지 ${clock(next)}"
        // SC3는 지점이 하나라 곧바로 여기에 온다. "마지막 값"만 쓰면 위 상태 줄 "보내지 않아요"와 엇갈려 읽혀, 혈당을 안 보낼 때는 무엇을 보내는지 밝힌다
        return when {
            sim.sendsGlucose -> "$elapsed 경과. 마지막 값을 계속 보내요"
            sim.sleepOverrideMinutes != null -> "$elapsed 경과. 수면 값만 계속 보내요"
            else -> "$elapsed 경과. 더 바뀌는 값은 없어요"
        }
    }

    private fun clock(ms: Long): String {
        val total = ms / 1000
        return "%d:%02d:%02d".format(total / 3600, total / 60 % 60, total % 60)
    }

    fun claimResult(result: ClaimResult): String? = when (result) {
        ClaimResult.PAIRED -> null
        ClaimResult.CODE_FORMAT -> "코드는 8자예요. 차량 화면의 코드를 다시 확인해 주세요."
        ClaimResult.CODE_INVALID -> "코드가 맞지 않아요. 차량 화면의 코드를 다시 확인해 주세요."
        ClaimResult.CODE_EXPIRED -> "코드 시간이 지났어요. 차량 화면의 새 코드를 입력해 주세요."
        ClaimResult.CONSENT_MISSING -> "동의를 먼저 받아야 해요."
        ClaimResult.RATE_LIMITED -> "시도가 많았어요. 잠시 뒤 다시 해 주세요."
        ClaimResult.NOT_REACHED -> "연결 안 됨. 네트워크를 확인하고 다시 해 주세요."
        ClaimResult.FAILED -> "연결하지 못했어요. 다시 해 주세요."
    }

    /** 안내 막대에서 첫 문장(결과)만 굵게 쓰려고 나눈다. 나머지는 없을 수 있다 */
    fun noticeParts(text: String): Pair<String, String?> {
        val end = text.indexOf(". ")
        return if (end < 0) text to null else text.substring(0, end + 1) to text.substring(end + 2)
    }

    /**
     * 라이선스 원문은 80자마다 줄을 끊어 두어 폰 폭에서 한두 낱말만 남는 줄이 생긴다.
     * 문단 안의 줄바꿈만 공백으로 바꾸고, 빈 줄(문단), "---" 구분선, 대문자 절 제목은 그대로 둔다. 글자는 바꾸지 않는다
     */
    fun reflow(text: String): String {
        val out = StringBuilder()
        var previous: String? = null
        for (line in text.lines()) {
            // 구분선과 PREAMBLE 같은 대문자 절 제목은 앞뒤 줄과 합치지 않는다
            val ruled = { l: String -> l.isNotBlank() && (l.trim().all { it == '-' } || (l.length < 40 && l.any(Char::isLetter) && l.none(Char::isLowerCase))) }
            val join = previous != null && previous.isNotBlank() && line.isNotBlank() && !ruled(previous) && !ruled(line)
            if (previous != null) out.append(if (join) " " else "\n")
            out.append(if (join) line.trimStart() else line)
            previous = line
        }
        return out.toString()
    }

    // D-32. 차는 폰 쪽 해제·철회와 서버 401을 구별할 수 없어 혈당 보류를 남긴다. 푸는 곳을 폰에서도 알려 준다
    const val CAR_HOLD_HINT = "차에 남은 끊김 보류는 차 설정의 연결된 기기에서 풀 수 있어요."

    fun notice(notice: Notice): String = when (notice) {
        Notice.UNPAIRED_DONE -> "연결을 해제했어요. 서버의 요약도 지웠어요. $CAR_HOLD_HINT"
        Notice.UNPAIRED_PENDING -> "연결을 해제했어요. 서버의 요약은 네트워크가 돌아오면 마저 지울게요. $CAR_HOLD_HINT"
        Notice.WITHDRAWN_DONE -> "동의를 철회했어요. 서버와 이 폰의 데이터를 지웠어요. $CAR_HOLD_HINT"
        Notice.WITHDRAWN_PENDING -> "동의를 철회했어요. 이 폰의 데이터는 지웠고, 서버 데이터는 네트워크가 돌아오면 마저 지울게요. $CAR_HOLD_HINT"
        Notice.SUMMARY_DELETED -> "서버의 요약은 지웠어요. 전송은 멈춰 있어요."
        Notice.SUMMARY_DELETE_FAILED -> "연결 안 됨. 서버의 요약이 아직 남아 있어요. 전송은 멈춰 있고, 요약은 유효 시간이 지나면 사라져요."
        Notice.TOKEN_REVOKED -> "차와의 연결이 끊겼어요. 다시 연결해 주세요."
        Notice.CONSENT_LOST -> "동의 기록이 없어 전송을 멈췄어요. 다시 동의해 주세요."
    }

    val TREND_CHOICES: List<GlucoseTrendDto?> = listOf(
        GlucoseTrendDto.FALLING_FAST, GlucoseTrendDto.FALLING, GlucoseTrendDto.FLAT, GlucoseTrendDto.RISING, null,
    )
    val PRESET_HUNDREDTHS = listOf(380, 400, 500, 540)      // 기준 경계. 4.0과 5.0을 정확히 넣어 볼 수 있다
    val SLEEP_CHOICES: List<Int?> = listOf(null, 290, 359, 360)

    fun sleepChoice(minutes: Int?): String = if (minutes == null) SIM_SLEEP_OFF else Formats.driveTime(minutes * 60L)
}
