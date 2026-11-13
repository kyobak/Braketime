package com.conditiondrive.core.voice

import com.conditiondrive.core.engine.AdvanceReason
import com.conditiondrive.core.voice.VoiceId.*

/** 설계서 6.1 문구 최종본과 6.3 우선순위. 문구를 고치면 6.1을 먼저 고친다(VoiceCatalogTest가 표와 대조한다). */
object VoiceCatalog {
    private const val DRIVE = "{운전 시간}"
    private const val REMAINING = "{남은 시간}"
    private const val DISTANCE = "{거리}"
    private const val PLACE = "{장소}"
    private const val MINUTES = "{분}"

    private const val ADVANCE_ALONE = "어젯밤 잠이 짧아 조금 일찍 말씀드려요."
    private const val ADVANCE_WITH_PASSENGER = "오늘은 조금 일찍 말씀드려요."

    private val SENTENCE_END = Regex("""(?<=[.?!])\s+""")
    // Android ICU는 짝 없는 }를 문법 오류로 본다. JVM 테스트만으로는 잡히지 않는다
    private val PLACEHOLDER = Regex("""\{[^}]*\}""")

    private class Line(val alone: String, val withPassenger: String = alone)

    private val LINES: Map<VoiceId, Line> = mapOf(
        V01 to Line("출발 전에 컨디션은 어떠세요?"),
        V02 to Line("${DRIVE}째 운전 중이에요. 15분 안에 쉬어 가는 게 좋아요."),
        V03 to Line("지금 컨디션은 어떠세요?"),
        V04 to Line("$DRIVE 연속 운전했어요. $DISTANCE 앞 ${PLACE}에서 쉬어 갈까요?"),
        V04N to Line("$DRIVE 연속 운전했어요. 근처에 쉴 곳이 안 보여요. 계속 찾아볼게요."),
        V04O to Line("$DRIVE 연속 운전했어요. $DISTANCE 앞 ${PLACE}에서 쉬어 가세요."),
        V05 to Line("${DRIVE}째 쉬지 않고 운전 중이에요. $DISTANCE 앞 ${PLACE}에서 꼭 쉬어 가세요."),
        V06 to Line("피곤하다고 하셨어요. $DISTANCE 앞 ${PLACE}에서 쉬어 갈까요?"),
        V06N to Line("피곤하다고 하셨어요. 근처에 쉴 곳이 안 보여요. 계속 찾아볼게요."),
        V06O to Line("피곤하다고 하셨어요. $DISTANCE 앞 ${PLACE}에서 쉬어 가세요."),
        V08 to Line(
            "혈당이 내려가고 있어요. $DISTANCE 앞 ${PLACE}에서 미리 쉬어 갈까요?",
            "컨디션 알림이에요. $DISTANCE 앞 ${PLACE}에서 미리 쉬어 갈까요?",
        ),
        V08F to Line(
            "혈당이 기준에 가까워요. $DISTANCE 앞 ${PLACE}에서 미리 쉬어 갈까요?",
            "컨디션 알림이에요. $DISTANCE 앞 ${PLACE}에서 미리 쉬어 갈까요?",
        ),
        V08N to Line("혈당이 내려가고 있어요.", "컨디션 알림이에요."),
        V08FN to Line("혈당이 기준에 가까워요.", "컨디션 알림이에요."),
        V08O to Line(
            "혈당이 내려가고 있어요. $DISTANCE 앞 ${PLACE}에서 미리 쉬어 가세요.",
            "컨디션 알림이에요. $DISTANCE 앞 ${PLACE}에서 미리 쉬어 가세요.",
        ),
        V08FO to Line(
            "혈당이 기준에 가까워요. $DISTANCE 앞 ${PLACE}에서 미리 쉬어 가세요.",
            "컨디션 알림이에요. $DISTANCE 앞 ${PLACE}에서 미리 쉬어 가세요.",
        ),
        V09 to Line("혈당이 기준보다 낮아요. 안전한 곳에 차를 세워 주세요. ${PLACE}까지 안내할게요."),
        // "정차하세요"는 고속도로에서 갓길 정차로 들린다. 차속을 몰라 저속용을 고를 때도 있어 "안전한 곳에 차를 세워"로 맞춘다(V-07)
        V09N to Line("혈당이 기준보다 낮아요. 가까운 안전한 곳에 차를 세워 주세요."),
        // 고속도로에서는 빠져나간 뒤에 세우라고 한다. 나들목 램프나 요금소 앞도 고속도로등이라 "나간 뒤 안전한 곳"으로 순서를 밝힌다
        V09H to Line("혈당이 기준보다 낮아요. 다음 쉼터에 들르거나 나들목으로 나간 뒤 안전한 곳에 차를 세우세요."),
        V10 to Line("안전한 곳에 차를 세워 주세요. ${PLACE}까지 안내 중이에요."),
        V10N to Line("가까운 안전한 곳에 차를 세워 주세요."),
        V10U to Line("안전한 곳에 차를 세워 주세요. ${PLACE}까지 $DISTANCE 남았어요."),
        V10H to Line("다음 쉼터에 들르거나 나들목으로 나간 뒤 안전한 곳에 차를 세우세요."),
        V11 to Line("잘 세우셨어요. $REMAINING 쉬어 가세요."),
        V12 to Line("15분 쉬었어요. 괜찮으면 출발하세요."),
        V13 to Line("15분 쉬었어요. 혈당 기준이 남아 있어 조금 더 기다려 주세요.", "15분 쉬었어요. 조금 더 쉬어 가면 좋겠어요."),
        V14 to Line("혈당이 회복되고 40분이 지나면 다시 알려 드릴게요.", "조금 쉬어 가요. 때가 되면 다시 알려 드릴게요."),
        V15 to Line("혈당이 회복 기준 위로 올라왔어요. 40분 뒤에 다시 알려 드릴게요.", "40분 뒤에 다시 알려 드릴게요."),
        V16 to Line("40분이 지났어요. 혈당과 컨디션을 확인하고 출발하세요.", "40분이 지났어요. 컨디션을 확인하고 출발하세요."),
        V16C to Line("40분이 지났어요. 혈당이 기준에 가까워요.", "40분이 지났어요."),
        V16D to Line("40분이 지났어요."),
        V17 to Line(
            "혈당이 기준보다 낮아요. 출발을 미루는 게 좋아요. 회복되고 40분이 지나면 다시 알려 드릴게요.",
            "컨디션 알림이에요. 출발을 미루는 게 좋아요.",
        ),
        V18 to Line("아직 $REMAINING 더 쉬는 게 좋아요."),
        V19 to Line("혈당 정보가 끊겼어요.", "연결이 끊겼어요."),
        V19R to Line("혈당 정보가 끊겼어요. 직접 확인하고 출발하세요.", "연결이 끊겼어요. 직접 확인하고 출발하세요."),
        V20 to Line("15분 넘게 쉬었어요. 괜찮으면 출발하세요."),
        V21 to Line("잘 못 들었어요. 컨디션은 어떠세요?"),
        V21S to Line("잘 못 들었어요. 쉬어 갈까요?"),
        V22 to Line("목적지까지 ${MINUTES}분 남았어요. 도착하면 쉬어 가세요."),
        V23 to Line("15분 더 쉬어 가면 좋겠어요. 다시 여쭐게요."),
        V24 to Line("혈당 회복 시간이 $REMAINING 남았어요. 더 쉬는 게 좋아요.", "쉬는 시간이 $REMAINING 남았어요."),
        V25 to Line("혈당이 아직 회복 기준 아래예요. 더 쉬는 게 좋아요.", "조금 더 쉬는 게 좋아요."),
        V26 to Line("이번 운행은 1시간에 한 번만 말할게요."),
        V27 to Line("바깥 공기로 바꿔 드릴까요?"),
        V27F to Line("바깥 공기로 바꾸지 못했어요."),
        V28 to Line("경유지를 추가하지 못했어요. ${PLACE}까지 $DISTANCE 남았어요."),
        V29 to Line("안내할게요."),
        V30 to Line("2시간마다 쉴 곳을 알려 드려요."),
        V31 to Line("알겠어요. 조금 뒤에 다시 여쭐게요."),
    )

    private val NO_PLACE_VARIANTS: Map<VoiceId, VoiceId> = mapOf(
        V04 to V04N,
        V05 to V04N,   // 표에 따로 없다. 같은 피로 정차 권고라 V04N이 뜻이 같다
        V06 to V06N,
        V08 to V08N,
        V08F to V08FN,
        V08O to V08N,
        V08FO to V08FN,
        V04O to V04N,
        V06O to V06N,
        V09 to V09N,
        V10 to V10N,
        V10U to V10N,
    )

    // 장소 없는 혈당 정차 안내를 시속 70km 이상에서 바꾼다. 피로 N 변형은 이미 정차를 지시하지 않는다
    private val HIGHWAY_NO_PLACE_VARIANTS: Map<VoiceId, VoiceId> = mapOf(
        V09 to V09H,
        V10 to V10H,
        V10U to V10H,
    )

    // 마이크를 쓸 수 없을 때의 평서문. 묻기만 하고 듣지 않으면 대답이 언제나 버려진다(10.2)
    private val ONE_WAY: Map<VoiceId, VoiceId> = mapOf(
        V04 to V04O,
        V05 to V05,
        V06 to V06O,
        V08 to V08O,
        V08F to V08FO,
    )

    fun priority(id: VoiceId): VoicePriority = when (id) {
        V09, V09N, V09H, V10, V10N, V10U, V10H, V17 -> VoicePriority.GLUCOSE_STOP
        // N 변형은 원래 음성을 대신하므로 그 묶음을 따른다. 6.3에 없다고 정보로 내리면 2분 폐기에 걸린다
        // V19, V19R은 유일한 끊김 안내다. 폐기되면 운전자는 혈당 감시가 켜져 있다고 믿고 달린다
        V04, V04N, V04O, V05, V06, V06N, V06O, V19, V19R, V24, V25 -> VoicePriority.PROMPT
        V02, V03, V08, V08F, V08N, V08FN, V08O, V08FO, V22 -> VoicePriority.NOTICE
        else -> VoicePriority.INFO
    }

    /**
     * {거리}, {장소}가 필요한데 placeName이 없으면 N 변형으로 바꾼 id를 반환.
     * highwayLikely면 혈당 정차 안내는 V09N, V10N 대신 나들목 변형(V09H, V10H)이다.
     */
    fun resolveNoPlace(id: VoiceId, highwayLikely: Boolean = false): VoiceId =
        (if (highwayLikely) HIGHWAY_NO_PLACE_VARIANTS[id] else null) ?: NO_PLACE_VARIANTS[id] ?: id

    /** 듣지 못할 때 대신 말할 음성. 체크인처럼 평서문으로 바꿀 수 없는 질문이면 null이고 말하지 않는다 */
    fun oneWay(id: VoiceId): VoiceId? = ONE_WAY[id]

    /**
     * 변수가 비면 "null"을 말하지 않는다. 장소가 빠졌으면 N 변형으로, 그 밖의 변수가 빠졌으면 그 문장만 뺀다.
     * 주행 중에 예외로 안내 전체를 잃지 않으려는 것이다. 말할 문장이 없으면 빈 문자열이고 런타임은 건너뛴다.
     */
    fun render(id: VoiceId, vars: VoiceVars, privacy: PrivacyContext): String {
        val place = vars.placeName?.let(Formats::placeName)?.takeIf { it.isNotEmpty() }
        val actual = if (lacksPlace(id, place, vars.distanceM)) resolveNoPlace(id, vars.highwayLikely) else id
        val line = LINES.getValue(actual)
        val template = if (privacy.passengerPresentOrUnknown) line.withPassenger else line.alone

        val values = mapOf(
            DRIVE to vars.driveSec?.let(Formats::driveTime),
            REMAINING to vars.remainingSec?.let(Formats::remaining),
            MINUTES to vars.routeMinutes?.toString(),
            DISTANCE to vars.distanceM?.let { if (vars.distanceApprox) "약 ${Formats.distance(it)}" else Formats.distance(it) },
            PLACE to place,
        )
        val body = template.split(SENTENCE_END)
            .map { sentence -> values.entries.fold(sentence) { acc, (key, value) -> if (value == null) acc else acc.replace(key, value) } }
            .filterNot { PLACEHOLDER.containsMatchIn(it) }
            .joinToString(" ")

        val prefix = when {
            actual != V04 || vars.advance == null -> null
            privacy.passengerPresentOrUnknown -> ADVANCE_WITH_PASSENGER
            else -> ADVANCE_ALONE
        }
        return listOfNotNull(prefix, body.ifEmpty { null }).joinToString(" ")
    }

    /** 금칙어 테스트용. 변수 자리는 샘플 값 */
    fun allStrings(): List<String> {
        val samples = listOf(
            VoiceVars(),
            VoiceVars(driveSec = 6300, remainingSec = 720, routeMinutes = 12, distanceM = 800, placeName = "칠곡졸음쉼터(부산방향)"),
            VoiceVars(driveSec = 7200, remainingSec = 60, routeMinutes = 2, distanceM = 3200, placeName = "칠곡졸음쉼터(부산방향)", advance = AdvanceReason.SLEEP_SHORT),
            VoiceVars(driveSec = 9000, remainingSec = 2400, routeMinutes = 15, distanceM = 12000, placeName = "안성휴게소(서울방향)"),
            VoiceVars(driveSec = 7200, remainingSec = 900, routeMinutes = 15, advance = AdvanceReason.SLEEP_SHORT),
            VoiceVars(driveSec = 7200, distanceM = 3200, distanceApprox = true, placeName = "칠곡졸음쉼터(부산방향)"),
        )
        return VoiceId.entries.flatMap { id ->
            listOf(false, true).flatMap { passenger ->
                samples.map { render(id, it, PrivacyContext(passengerPresentOrUnknown = passenger)) }
            }
        }.filter { it.isNotEmpty() }.distinct()
    }

    private fun lacksPlace(id: VoiceId, place: String?, distanceM: Int?): Boolean {
        val template = LINES.getValue(id).alone
        return (PLACE in template && place == null) || (DISTANCE in template && distanceM == null)
    }
}
