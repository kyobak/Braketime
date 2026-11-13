package com.conditiondrive.core.voice

import com.conditiondrive.core.engine.AdvanceReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceCatalogTest {
    private val alone = PrivacyContext(passengerPresentOrUnknown = false)
    private val passenger = PrivacyContext(passengerPresentOrUnknown = true)

    private val sample = VoiceVars(
        driveSec = 6300, remainingSec = 720, routeMinutes = 12, distanceM = 3200, placeName = "칠곡졸음쉼터(부산방향)",
    )

    private class Row(val id: VoiceId, val line: String, val variant: String)

    /** 설계서 6.1 표 원문. 문구를 코드에 다시 옮겨 적지 않아 오타가 양쪽에 함께 들어갈 수 없다 */
    private val rows: List<Row> = RepoFiles.section(RepoFiles.text("docs/DESIGN.md"), "### 6.1").lines()
        .filter { Regex("""^\| V\d\d[A-Z]{0,2} \|""").containsMatchIn(it) }
        .map { row ->
            val cells = row.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
            Row(VoiceId.valueOf(cells[0]), cells[2], cells[3])
        }

    private fun fill(template: String): String = template
        .replace("{운전 시간}", "1시간 45분")
        .replace("{남은 시간}", "12분")
        .replace("{거리}", "3.2킬로미터")
        .replace("{장소}", "칠곡졸음쉼터")
        .replace("{분}", "12")

    @Test
    fun tableCoversEveryVoiceIdExactlyOnce() {
        assertEquals(VoiceId.entries.toSet(), rows.map { it.id }.toSet())
        assertEquals(VoiceId.entries.size, rows.size)
    }

    @Test
    fun rendersEveryLineExactlyAsDesignTable() {
        val failures = rows.mapNotNull { row ->
            val expected = fill(row.line.removePrefix("[앞당김 문장] "))
            val actual = VoiceCatalog.render(row.id, sample, alone)
            "${row.id}: [$actual] 기대 [$expected]".takeIf { actual != expected }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun rendersEveryPassengerVariantExactlyAsDesignTable() {
        val byId = rows.associateBy { it.id }
        val failures = rows.mapNotNull { row ->
            val body = when {
                row.variant.isEmpty() || row.variant.startsWith("같음") || row.variant == "앞당김 문장만 바뀜" -> row.line
                row.variant == "V08 동승 변형과 같음" -> byId.getValue(VoiceId.V08).variant
                else -> row.variant
            }
            val expected = fill(body.removePrefix("[앞당김 문장] "))
            val actual = VoiceCatalog.render(row.id, sample, passenger)
            "${row.id}: [$actual] 기대 [$expected]".takeIf { actual != expected }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun advanceSentenceComesFromDesignAndOnlyPrefixesV04() {
        val doc = RepoFiles.section(RepoFiles.text("docs/DESIGN.md"), "### 6.1")
        val advanceAlone = "어젯밤 잠이 짧아 조금 일찍 말씀드려요."
        val advancePassenger = "오늘은 조금 일찍 말씀드려요."
        assertTrue(advanceAlone in doc && advancePassenger in doc)

        val advanced = sample.copy(advance = AdvanceReason.SLEEP_SHORT)
        val body = "1시간 45분 연속 운전했어요. 3.2킬로미터 앞 칠곡졸음쉼터에서 쉬어 갈까요?"
        assertEquals("$advanceAlone $body", VoiceCatalog.render(VoiceId.V04, advanced, alone))
        assertEquals("$advancePassenger $body", VoiceCatalog.render(VoiceId.V04, advanced, passenger))

        for (id in VoiceId.entries - VoiceId.V04) {
            assertEquals(VoiceCatalog.render(id, sample, alone), VoiceCatalog.render(id, advanced, alone), "$id")
        }
    }

    /** A1, A5, C1, D3, D20, A30의 음성 변수 */
    @Test
    fun rendersScenarioValues() {
        assertEquals("1시간 45분째 운전 중이에요. 15분 안에 쉬어 가는 게 좋아요.", VoiceCatalog.render(VoiceId.V02, VoiceVars(driveSec = 6300), alone))
        assertEquals(
            "2시간 40분째 쉬지 않고 운전 중이에요. 800미터 앞 안성휴게소에서 꼭 쉬어 가세요.",
            VoiceCatalog.render(VoiceId.V05, VoiceVars(driveSec = 9600, distanceM = 800, placeName = "안성휴게소(서울방향)"), alone),
        )
        assertEquals("잘 세우셨어요. 15분 쉬어 가세요.", VoiceCatalog.render(VoiceId.V11, VoiceVars(remainingSec = 900), alone))
        assertEquals("아직 10분 더 쉬는 게 좋아요.", VoiceCatalog.render(VoiceId.V18, VoiceVars(remainingSec = 600), alone))
        assertEquals("혈당 회복 시간이 30분 남았어요. 더 쉬는 게 좋아요.", VoiceCatalog.render(VoiceId.V24, VoiceVars(remainingSec = 1800), alone))
        assertEquals("쉬는 시간이 30분 남았어요.", VoiceCatalog.render(VoiceId.V24, VoiceVars(remainingSec = 1800), passenger))
        assertEquals("목적지까지 15분 남았어요. 도착하면 쉬어 가세요.", VoiceCatalog.render(VoiceId.V22, VoiceVars(routeMinutes = 15), alone))
    }

    @Test
    fun missingPlaceFallsBackToNoPlaceVariant() {
        val noPlace = VoiceVars(driveSec = 7200)
        assertEquals("2시간 연속 운전했어요. 근처에 쉴 곳이 안 보여요. 계속 찾아볼게요.", VoiceCatalog.render(VoiceId.V04, noPlace, alone))
        assertEquals("2시간 연속 운전했어요. 근처에 쉴 곳이 안 보여요. 계속 찾아볼게요.", VoiceCatalog.render(VoiceId.V05, noPlace, alone))
        assertEquals("피곤하다고 하셨어요. 근처에 쉴 곳이 안 보여요. 계속 찾아볼게요.", VoiceCatalog.render(VoiceId.V06, noPlace, alone))
        assertEquals("혈당이 내려가고 있어요.", VoiceCatalog.render(VoiceId.V08, noPlace, alone))
        assertEquals("컨디션 알림이에요.", VoiceCatalog.render(VoiceId.V08F, noPlace, passenger))
        assertEquals("혈당이 기준보다 낮아요. 가까운 안전한 곳에 차를 세워 주세요.", VoiceCatalog.render(VoiceId.V09, noPlace, alone))
        assertEquals("가까운 안전한 곳에 차를 세워 주세요.", VoiceCatalog.render(VoiceId.V10, noPlace, alone))
    }

    /** 괄호만 있는 이름은 지우면 비므로 장소 없음과 같다. 거리 없이 "앞"만 말하지 않는다 */
    @Test
    fun unusablePlaceValuesCountAsNoPlace() {
        val parenthesesOnly = VoiceVars(driveSec = 7200, distanceM = 3200, placeName = "(부산방향)")
        assertEquals(VoiceCatalog.render(VoiceId.V04N, parenthesesOnly, alone), VoiceCatalog.render(VoiceId.V04, parenthesesOnly, alone))
        val noDistance = VoiceVars(driveSec = 7200, placeName = "칠곡졸음쉼터")
        assertEquals(VoiceCatalog.render(VoiceId.V04N, noDistance, alone), VoiceCatalog.render(VoiceId.V04, noDistance, alone))
        // V09는 장소만 쓰므로 거리가 없어도 원문이다
        assertEquals("혈당이 기준보다 낮아요. 안전한 곳에 차를 세워 주세요. 칠곡졸음쉼터까지 안내할게요.", VoiceCatalog.render(VoiceId.V09, noDistance, alone))
    }

    @Test
    fun resolveNoPlaceMapsOnlyPlaceVoices() {
        val expected = mapOf(
            VoiceId.V04 to VoiceId.V04N, VoiceId.V05 to VoiceId.V04N, VoiceId.V06 to VoiceId.V06N,
            VoiceId.V08 to VoiceId.V08N, VoiceId.V08F to VoiceId.V08FN, VoiceId.V09 to VoiceId.V09N, VoiceId.V10 to VoiceId.V10N,
            VoiceId.V10U to VoiceId.V10N, VoiceId.V04O to VoiceId.V04N, VoiceId.V06O to VoiceId.V06N,
            VoiceId.V08O to VoiceId.V08N, VoiceId.V08FO to VoiceId.V08FN,
        )
        for (id in VoiceId.entries) {
            assertEquals(expected[id] ?: id, VoiceCatalog.resolveNoPlace(id), "$id")
        }
    }

    /** V-07. 고속에서는 혈당 정차 안내만 나들목 변형으로 바뀐다. 기본값은 지금과 같다 */
    @Test
    fun highwayNoPlaceMapsOnlyGlucoseStops() {
        val highway = mapOf(VoiceId.V09 to VoiceId.V09H, VoiceId.V10 to VoiceId.V10H, VoiceId.V10U to VoiceId.V10H)
        for (id in VoiceId.entries) {
            assertEquals(highway[id] ?: VoiceCatalog.resolveNoPlace(id), VoiceCatalog.resolveNoPlace(id, highwayLikely = true), "$id")
        }
    }

    @Test
    fun highwayWithoutPlaceSaysExitNotStop() {
        val fast = VoiceVars(highwayLikely = true)
        assertEquals("혈당이 기준보다 낮아요. 다음 쉼터에 들르거나 나들목으로 나간 뒤 안전한 곳에 차를 세우세요.", VoiceCatalog.render(VoiceId.V09, fast, alone))
        assertEquals("다음 쉼터에 들르거나 나들목으로 나간 뒤 안전한 곳에 차를 세우세요.", VoiceCatalog.render(VoiceId.V10, fast, alone))
        // 장소만 있고 거리가 없는 V10U도 장소 없음으로 본다
        assertEquals("다음 쉼터에 들르거나 나들목으로 나간 뒤 안전한 곳에 차를 세우세요.", VoiceCatalog.render(VoiceId.V10U, fast.copy(placeName = "칠곡졸음쉼터"), alone))
        // 동승자가 있어도 안전 예외로 같다
        assertEquals(VoiceCatalog.render(VoiceId.V09H, fast, alone), VoiceCatalog.render(VoiceId.V09, fast, passenger))
        // 장소가 있으면 고속이어도 원래 문구다
        assertEquals(VoiceCatalog.render(VoiceId.V09, sample, alone), VoiceCatalog.render(VoiceId.V09, sample.copy(highwayLikely = true), alone))
        // 피로 N 변형은 정차를 지시하지 않으므로 그대로다
        assertEquals(VoiceCatalog.render(VoiceId.V04N, fast, alone), VoiceCatalog.render(VoiceId.V04, fast, alone))
    }

    /** V-07. 어떤 음성도 갓길이나 차로에 세우라고 하지 않는다. 장소 있는 혈당 정차 안내는 "안전한 곳에"로 시작한다 */
    @Test
    fun glucoseStopNeverPointsToShoulderOrLane() {
        for (text in VoiceCatalog.allStrings()) {
            assertFalse("갓길" in text || "차로" in text, text)
        }
        for (id in listOf(VoiceId.V09H, VoiceId.V10H)) {
            val text = VoiceCatalog.render(id, sample, alone)
            // 나들목 램프나 요금소 앞에 바로 세우라는 말로 들리지 않게 "나간 뒤 안전한 곳"의 순서를 둔다
            assertTrue("나들목으로 나간 뒤 안전한 곳에" in text, text)
        }
        // 차속을 몰라 저속용을 고를 때도 있다. 혈당 정차 음성 전체에서 "정차하세요"를 쓰지 않는다
        val glucoseStops = listOf(VoiceId.V09, VoiceId.V09N, VoiceId.V09H, VoiceId.V10, VoiceId.V10N, VoiceId.V10U, VoiceId.V10H)
        for (id in glucoseStops) {
            for (vars in listOf(sample, VoiceVars(), VoiceVars(highwayLikely = true))) {
                for (privacy in listOf(alone, passenger)) {
                    val text = VoiceCatalog.render(id, vars, privacy)
                    assertFalse("정차하세요" in text, "$id $text")
                    assertTrue("안전한 곳에" in text, "$id $text")
                }
            }
        }
        assertTrue(VoiceCatalog.render(VoiceId.V09, sample, alone).contains("낮아요. 안전한 곳에 차를 세워 주세요."))
        for (id in listOf(VoiceId.V10, VoiceId.V10U)) {
            assertTrue(VoiceCatalog.render(id, sample, alone).startsWith("안전한 곳에 차를 세워 주세요."), "$id")
        }
    }

    /** D-13: 평평한 값에 "내려가고 있어요"는 거짓이다 */
    @Test
    fun flatCautionWithoutPlaceKeepsFlatWording() {
        val noPlace = VoiceVars()
        assertEquals("혈당이 기준에 가까워요.", VoiceCatalog.render(VoiceId.V08F, noPlace, alone))
        assertEquals("혈당이 내려가고 있어요.", VoiceCatalog.render(VoiceId.V08, noPlace, alone))
    }

    /** 10.2 듣지 못하면 묻지 않는다. 대체 문장에 물음표가 있으면 대답이 버려진다 */
    @Test
    fun oneWayVariantsAreStatements() {
        for (id in listOf(VoiceId.V04, VoiceId.V05, VoiceId.V06, VoiceId.V08, VoiceId.V08F)) {
            val actual = VoiceCatalog.oneWay(id)
            assertTrue(actual != null, "$id")
            assertFalse("?" in VoiceCatalog.render(actual, sample, alone), "$id")
            assertFalse("?" in VoiceCatalog.render(actual, sample, passenger), "$id")
        }
        for (id in listOf(VoiceId.V01, VoiceId.V03, VoiceId.V21, VoiceId.V21S, VoiceId.V27)) {
            assertEquals(null, VoiceCatalog.oneWay(id), "$id")
        }
    }

    /** 경로로 재지 못한 거리는 단정하지 않는다 */
    @Test
    fun approximateDistanceSaysAbout() {
        val text = VoiceCatalog.render(VoiceId.V04, sample.copy(distanceApprox = true), alone)
        assertTrue("약 3.2킬로미터 앞" in text, text)
    }

    /** V15의 "기준"은 4.0과 5.0이 섞이지 않게 회복 기준이라고 쓴다. V11은 시동을 끄면 지킬 수 없는 약속을 하지 않는다 */
    @Test
    fun wordingDoesNotPromiseOrConfuse() {
        assertTrue("회복 기준 위로" in VoiceCatalog.render(VoiceId.V15, sample, alone))
        assertFalse("알려 드릴게요" in VoiceCatalog.render(VoiceId.V11, sample, alone))
    }

    /** V10은 "안내 중"이라고 말한다. 경유지가 없을 때 쓰는 V10U에는 그 말이 없어야 한다 */
    @Test
    fun unguidedRepeatDoesNotClaimGuidance() {
        val text = VoiceCatalog.render(VoiceId.V10U, sample, alone)
        assertFalse("안내" in text, text)
        assertTrue("칠곡졸음쉼터까지 3.2킬로미터 남았어요" in text, text)
    }

    /** P17, F3-1 */
    @Test
    fun placeParenthesesNeverReachVoice() {
        val text = VoiceCatalog.render(VoiceId.V04, VoiceVars(driveSec = 7200, distanceM = 3200, placeName = "칠곡졸음쉼터(부산방향)"), alone)
        assertEquals("2시간 연속 운전했어요. 3.2킬로미터 앞 칠곡졸음쉼터에서 쉬어 갈까요?", text)
    }

    /** "null"을 말하거나 예외로 안내를 잃지 않는다 */
    @Test
    fun missingVariablesNeverLeakOrThrow() {
        for (id in VoiceId.entries) {
            for (privacy in listOf(alone, passenger)) {
                val text = VoiceCatalog.render(id, VoiceVars(), privacy)
                assertFalse("null" in text || "{" in text || "}" in text, "$id: $text")
                assertEquals(text.trim(), text, "$id")
            }
        }
        assertEquals("15분 안에 쉬어 가는 게 좋아요.", VoiceCatalog.render(VoiceId.V02, VoiceVars(), alone))
        assertEquals("잘 세우셨어요.", VoiceCatalog.render(VoiceId.V11, VoiceVars(), alone))
        assertEquals("경유지를 추가하지 못했어요.", VoiceCatalog.render(VoiceId.V28, VoiceVars(), alone))
        assertEquals("", VoiceCatalog.render(VoiceId.V18, VoiceVars(), alone))
    }

    @Test
    fun priorityMatchesSection63() {
        val groups = mapOf(
            VoicePriority.GLUCOSE_STOP to setOf(
                VoiceId.V09, VoiceId.V09N, VoiceId.V09H, VoiceId.V10, VoiceId.V10N, VoiceId.V10U, VoiceId.V10H, VoiceId.V17,
            ),
            VoicePriority.PROMPT to setOf(
                VoiceId.V04, VoiceId.V04N, VoiceId.V04O, VoiceId.V05, VoiceId.V06, VoiceId.V06N, VoiceId.V06O,
                VoiceId.V19, VoiceId.V19R, VoiceId.V24, VoiceId.V25,
            ),
            VoicePriority.NOTICE to setOf(
                VoiceId.V02, VoiceId.V03, VoiceId.V08, VoiceId.V08F, VoiceId.V08N, VoiceId.V08FN, VoiceId.V08O, VoiceId.V08FO, VoiceId.V22,
            ),
        )
        for (id in VoiceId.entries) {
            val expected = groups.entries.firstOrNull { id in it.value }?.key ?: VoicePriority.INFO
            assertEquals(expected, VoiceCatalog.priority(id), "$id")
        }
    }

    /** 동승자 앞에서 건강 정보를 말하지 않는다. V09, V09N, V09H만 표의 안전 예외다 */
    @Test
    fun passengerVariantsHideGlucoseExceptSafetyStops() {
        val exceptions = setOf(VoiceId.V09, VoiceId.V09N, VoiceId.V09H)
        for (id in VoiceId.entries - exceptions) {
            val text = VoiceCatalog.render(id, sample.copy(advance = AdvanceReason.SLEEP_SHORT), passenger)
            assertFalse("혈당" in text, "$id: $text")
            assertFalse("잠이 짧" in text, "$id: $text")
        }
        assertTrue("혈당" in VoiceCatalog.render(VoiceId.V09, sample, passenger))
    }

    /** 혈당 수치는 어떤 음성에도 넣지 않는다. VoiceVars에 혈당 값 자리가 없는 것을 구조로 지킨다 */
    @Test
    fun voiceVarsHaveNoGlucoseValue() {
        val fields = VoiceVars::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(fields.any { "glucose" in it || "mmol" in it || "mgdl" in it }, "$fields")
    }

    @Test
    fun allStringsCoversEveryVoiceAndEveryVariant() {
        val all = VoiceCatalog.allStrings()
        assertEquals(all.distinct(), all)
        assertTrue(all.none { it.isBlank() })
        for (id in VoiceId.entries) {
            for (privacy in listOf(alone, passenger)) {
                val full = VoiceCatalog.render(id, sample.copy(distanceM = 800), privacy)
                assertTrue(full in all, "$id: $full")
            }
        }
        assertTrue(all.any { it.startsWith("어젯밤 잠이 짧아") })
        assertTrue(all.any { it.startsWith("오늘은 조금 일찍") })
    }
}
