package com.conditiondrive.companion

import com.conditiondrive.companion.simulator.GlucoseSimulator
import com.conditiondrive.companion.simulator.GlucoseUnit
import com.conditiondrive.companion.simulator.SimScenarios
import com.conditiondrive.companion.simulator.SimulatorState
import com.conditiondrive.companion.storage.StoredState
import com.conditiondrive.companion.ui.Copy
import com.conditiondrive.companion.ui.Copy.Big
import com.conditiondrive.companion.upload.SleepValue
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.voice.ForbiddenWords
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopyAndPolicyTest {
    /** 테스트 작업 폴더는 모듈 폴더지만 IDE에서 돌리면 저장소 루트일 수 있다 */
    private val module: File = listOf(File("."), File("companion")).first { File(it, "src/main/AndroidManifest.xml").exists() }
    private val main = File(module, "src/main")
    private val manifest = File(main, "AndroidManifest.xml").readText()
    private val sources = File(main, "kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 값이 나가는 모든 상태의 문장에 시뮬레이션이 붙어 있다 */
    @Test
    fun everyGlucoseLineSaysSimulated() {
        val states = listOf(
            SimulatorState(),
            SimulatorState(running = true),
            SimulatorState(running = true, sensorCut = true),
            SimulatorState(running = true, glucoseActive = false),
            SimulatorState(running = true, hundredths = 380, trend = null),
        ) + GlucoseTrendDto.entries.map { SimulatorState(running = true, trend = it) }
        for (state in states) assertTrue("시뮬레이션" in Copy.glucoseNote(state), Copy.glucoseNote(state))
        assertTrue("시뮬레이션" in Copy.GLUCOSE_LABEL)
        assertTrue("시뮬레이션" in Copy.SIM_ALWAYS)
        assertTrue("시뮬레이션" in Copy.SIM_RUNNING_MARKER)
        assertTrue("시뮬레이터" in Copy.SIM_IDLE_MARKER, "혈당을 안 보내는 동안에도 시뮬레이터가 켜져 있음은 보인다")
    }

    @Test
    fun simulatedSleepIsLabelled() {
        val ui = AppUi(
            stored = StoredState(sleep = SleepValue(500, 1)),
            simulator = SimulatorState(running = true, sleepOverrideMinutes = 290, sleepOverrideAtMs = 1),
        )
        assertEquals(Copy.Shown("4시간 50분", true), Copy.sleepValue(ui))
        assertTrue("시뮬레이션" in Copy.sleepNote(ui).orEmpty())
        assertEquals(Copy.Shown("8시간 20분", true), Copy.sleepValue(ui.copy(simulator = SimulatorState())))
        assertFalse("시뮬레이션" in Copy.sleepNote(ui.copy(simulator = SimulatorState())).orEmpty())
    }

    @Test
    fun valueFormatting() {
        assertEquals("5.4", Copy.value(5.4))
        assertEquals("5.0", Copy.value(5.0))
        assertEquals("4.99", Copy.value(4.99))
        assertEquals("3.05", Copy.value(3.05))
        assertEquals("33.3", Copy.value(33.3))
    }

    @Test
    fun mgdlViewShowsWhatIsActuallySent() {
        val sim = GlucoseSimulator()
        sim.start()
        sim.setUnit(GlucoseUnit.MGDL)
        sim.setMmolHundredths(500)
        sim.nudgeMgdl(0)
        val big = Copy.bigValue(sim.state.value)
        assertEquals("90", big.number)
        assertEquals(Copy.MGDL, big.unit)
        assertTrue("보내는 값 4.99 mmol/L" in big.line, big.line)
    }

    /** 실기 C4: mg/dL +1 뒤 큰 숫자 98과 문장의 97이 한 화면에 함께 나왔다 */
    @Test
    fun mgdlLineMatchesTheEnteredNumber() {
        val sim = GlucoseSimulator()
        sim.start()
        sim.setUnit(GlucoseUnit.MGDL)
        sim.nudgeMgdl(1)
        val state = sim.state.value
        assertEquals("98", Copy.bigValue(state).number)
        assertTrue(Copy.glucoseNote(state).startsWith("98 mg/dL"), Copy.glucoseNote(state))
        assertEquals(Copy.Shown("5.43 mmol/L", true), Copy.glucoseValue(state), "보내는 값은 내림한 그대로다")
        sim.setUnit(GlucoseUnit.MMOL)
        val mmolView = sim.state.value
        assertTrue(Copy.glucoseNote(mmolView).startsWith("97 mg/dL"), "mmol/L 보기에서는 입력값이 없어 변환값이다")
        assertEquals("97 mg/dL · 변화 적음", Copy.bigValue(mmolView).line, "큰 숫자 아래 줄과 C3 설명의 mg/dL이 같다")
    }

    /** F10: C4 큰 숫자 아래에는 한 줄만. 같은 값을 세 번 되풀이하지 않는다 */
    @Test
    fun bigValueLineDoesNotRepeatTheNumber() {
        val state = SimulatorState(running = true)
        val big = Copy.bigValue(state)
        assertEquals(Big("5.4", "mmol/L", "97 mg/dL · 변화 적음"), big)
        assertFalse(big.number in big.line)
    }

    /** F3: 보내는지 아닌지 큰 숫자 위 한 줄이 상태마다 다르게 말한다 */
    @Test
    fun simStatusTellsWhetherItIsSending() {
        assertEquals("꺼짐 · 켜면 이 값을 보내요", Copy.simStatus(SimulatorState()))
        assertEquals("보내는 중", Copy.simStatus(SimulatorState(running = true)))
        assertEquals("센서 끊김 재현 · 보내지 않아요", Copy.simStatus(SimulatorState(running = true, sensorCut = true)))
        assertEquals("혈당 없이 재생 · 보내지 않아요", Copy.simStatus(SimulatorState(running = true, glucoseActive = false)))
        for (state in listOf(SimulatorState(), SimulatorState(running = true, sensorCut = true), SimulatorState(running = true, glucoseActive = false))) {
            assertFalse(Copy.glucoseValue(state).present, "보내지 않는 상태는 C3 값 자리에 문장")
        }
    }

    /** 실기 27, 28: SC3 재생 중 mg/dL 보기에서 상태 줄 "보내지 않아요" 바로 아래에 "보내는 값 5.6 mmol/L"이 나왔다 */
    @Test
    fun bigValueLineSaysSendingOnlyWhileSending() {
        val base = SimulatorState(hundredths = 560)
        val states = listOf(
            base,
            base.copy(running = true),
            base.copy(running = true, sensorCut = true),
            base.copy(running = true, glucoseActive = false),
            base.copy(running = true, glucoseActive = false, sensorCut = true),
        )
        for (state in states) for (unit in GlucoseUnit.entries) {
            val shown = state.copy(unit = unit)
            val line = Copy.bigValue(shown).line
            val sending = Copy.simStatus(shown) == "보내는 중"
            // mmol/L 보기의 아래 줄은 mg/dL 환산이라 원래 "보내는 값"이 없다. 어느 보기든 보내지 않는 상태에서는 나오면 안 된다
            if (!sending) assertFalse("보내는 값" in line, "${Copy.simStatus(shown)} / $line")
            if (unit == GlucoseUnit.MGDL) assertEquals(sending, "보내는 값" in line, line)
            assertEquals(state.sendsGlucose, sending, "상태 줄과 실제 전송 판단이 같다")
        }
        // 끊김과 혈당 없는 재생이 겹치면 C3 값과 C4 상태 줄이 같은 이유(끊김)를 말한다
        val both = base.copy(running = true, glucoseActive = false, sensorCut = true)
        assertTrue("센서 끊김" in Copy.glucoseValue(both).text)
        assertTrue("센서 끊김" in Copy.simStatus(both))
        val sc3 = base.copy(running = true, glucoseActive = false, unit = GlucoseUnit.MGDL)
        assertEquals("혈당 없이 재생 · 보내지 않아요", Copy.simStatus(sc3))
        assertEquals(Big("100", "mg/dL", "5.6 mmol/L · 변화 적음"), Copy.bigValue(sc3))
        assertEquals("5.6 mmol/L · 변화 적음", Copy.bigValue(sc3.copy(glucoseActive = true, sensorCut = true)).line)
        assertEquals("5.6 mmol/L · 변화 적음", Copy.bigValue(base.copy(unit = GlucoseUnit.MGDL)).line, "꺼짐도 지금 보내는 값이 아니다")
        assertEquals("보내는 값 5.6 mmol/L · 변화 적음", Copy.bigValue(sc3.copy(glucoseActive = true)).line)
    }

    /** 실기 27, 28: 맨 위 표지가 혈당을 보내지 않는 상태에서도 "전송 중"이라 했다. 표지는 실제 전송(readingAt)과 같이 간다 */
    @Test
    fun runningMarkerFollowsWhatIsActuallySent() {
        val now = 1_000_000L
        val sim = GlucoseSimulator()
        fun check(expected: String?) {
            val state = sim.state.value
            assertEquals(expected, Copy.runningMarker(state), state.toString())
            assertEquals(expected == Copy.SIM_RUNNING_MARKER, sim.readingAt(now) != null, "전송 중이라고 말할 때만 혈당이 요약에 실린다")
        }
        check(null)
        sim.start()
        check(Copy.SIM_RUNNING_MARKER)
        sim.setSensorCut(true)
        check(Copy.SIM_IDLE_MARKER)
        sim.setSensorCut(false)
        check(Copy.SIM_RUNNING_MARKER)
        sim.play(SimScenarios.load(SimScenarios.SC3), now)
        check(Copy.SIM_IDLE_MARKER)
        // 실제 SC3 파일 재생 직후의 진행 줄. 표지·상태 줄의 "보내지 않음"과 같은 화면에서 엇갈리지 않는다
        assertEquals("0:00:00 경과. 수면 값만 계속 보내요", Copy.scenarioLine(sim.state.value))
        sim.stop()
        check(null)
        assertFalse("전송" in Copy.SIM_IDLE_MARKER)
        assertEquals("시뮬레이터 실행 중 · 혈당 보내지 않음", Copy.SIM_IDLE_MARKER)
    }

    /** F11: 빼기는 U+2212. 붙임표는 더하기보다 짧고 가늘다 */
    @Test
    fun nudgeLabelsUseTrueMinus() {
        assertEquals("\u2212 0.1", Copy.nudgeLabel(SimulatorState(), up = false))
        assertEquals("+ 0.1", Copy.nudgeLabel(SimulatorState(), up = true))
        assertEquals("\u2212 1", Copy.nudgeLabel(SimulatorState(unit = GlucoseUnit.MGDL), up = false))
    }

    /** F15: 안내 막대는 첫 문장만 굵게. 마침표가 없으면 통째로 */
    @Test
    fun noticeSplitsFirstSentence() {
        assertEquals(
            "동의를 철회했어요." to "서버와 이 폰의 데이터를 지웠어요. ${Copy.CAR_HOLD_HINT}",
            Copy.noticeParts(Copy.notice(Notice.WITHDRAWN_DONE)),
        )
        assertEquals("한 문장" to null, Copy.noticeParts("한 문장"))
    }

    /** 라이선스 화면: 문단 안 줄바꿈만 합치고 글자는 그대로다. 구분선과 빈 줄은 남는다 */
    @Test
    fun licenseReflowKeepsEveryWord() {
        val original = File(main, "assets/licenses/Pretendard-OFL.txt").readText()
        val shown = Copy.reflow(original)
        assertEquals(original.split(Regex("\\s+")).filter { it.isNotEmpty() }, shown.split(Regex("\\s+")).filter { it.isNotEmpty() })
        assertTrue("Copyright (c) 2021, Kil Hyung-jin (https://github.com/orioncactus/pretendard), with Reserved Font Name Pretendard." in shown)
        assertEquals("a b\n\nc\n---\nd", Copy.reflow("a\nb\n\nc\n---\nd"))
        assertTrue("PREAMBLE\nThe goals" in shown, "절 제목은 본문과 붙지 않는다")
    }

    /** F4: 굵게 보일 낱말이 실제로 보관 문장에 있다. 문구가 바뀌면 굵기가 조용히 사라지는 것을 막는다 */
    @Test
    fun consentEmphasisWordsExist() {
        val keep = Copy.CONSENT_ITEMS.first { it.first == "보관" }.second
        Copy.CONSENT_EMPHASIS.forEach { assertTrue(it in keep, it) }
    }

    /** F2: 글꼴은 Pretendard 원본 하나, 라이선스 원문이 함께 들어 있다 */
    @Test
    fun fontAndLicenseShipTogether() {
        val fonts = File(main, "res/font").listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(setOf("pretendard.ttf"), fonts)
        val license = File(main, "assets/licenses").listFiles().orEmpty().map { it.name }.toSet()
        assertEquals(setOf("Pretendard-OFL.txt"), license)
        assertTrue("Reserved Font Name Pretendard" in File(main, "assets/licenses/Pretendard-OFL.txt").readText())
        val screens = sources.first { it.name == "Screens.kt" }.readText()
        assertTrue("\"licenses/Pretendard-OFL.txt\"" in screens)
        assertTrue("Pretendard" in Copy.DATA_LICENSE_NAME)
    }

    /** 실기 C2: 주소 저장을 눌러도 아무 표시가 없었다 */
    @Test
    fun serverSaveAlwaysAnswers() {
        assertEquals(null, Copy.serverSaveNote(null))
        assertEquals(Copy.SERVER_SAVED, Copy.serverSaveNote(true))
        assertEquals(Copy.SERVER_REJECTED, Copy.serverSaveNote(false))
    }

    /** 실기: 상태 표시줄과 알림 목록에서 알림 아이콘이 흰 사각형으로 보였다. 배경을 채운 앱 아이콘을 쓴 탓이다 */
    @Test
    fun notificationIconIsASilhouette() {
        val service = sources.first { it.name == "SimulatorService.kt" }.readText()
        assertTrue("R.drawable.ic_notification" in service)
        val icon = File(main, "res/drawable/ic_notification.xml").readText()
        val fullBleed = Regex("""M0,0h\d+v\d+H0z""")
        assertFalse(fullBleed.containsMatchIn(icon), "면 전체를 채우면 알파가 사각형이 된다")
        assertTrue(fullBleed.containsMatchIn(File(main, "res/drawable/ic_launcher.xml").readText()), "앱 아이콘이 바뀌면 이 테스트의 전제를 다시 본다")
    }

    /** 실기 C2: 미리보기가 정사각형 칸 밖으로 넘쳐 안내 문장을 덮었다 */
    @Test
    fun qrPreviewStaysInsideItsBox() {
        val scanner = sources.first { it.name == "QrScanner.kt" }.readText()
        assertTrue("ImplementationMode.COMPATIBLE" in scanner)
        assertTrue("clipToBounds()" in scanner, "두 설정이 함께 있어야 잘린다. 실기에서 COMPATIBLE만으로는 계속 넘쳤다")
    }

    @Test
    fun scenarioLine() {
        assertEquals(null, Copy.scenarioLine(SimulatorState()))
        val line = Copy.scenarioLine(SimulatorState(scenarioId = SimScenarios.SC1, scenarioElapsedMs = 61_000, scenarioNextInMs = 3_600_000))
        assertEquals("0:01:01 경과. 다음 변화까지 1:00:00", line)
        // SC3: 지점 하나라 다음 변화가 없다. 혈당을 보내지 않으니 "마지막 값을 계속 보내요"라고 하면 상태 줄과 엇갈린다
        val sc3 = SimulatorState(running = true, scenarioId = SimScenarios.SC3, glucoseActive = false, sleepOverrideMinutes = 290)
        assertEquals("0:00:00 경과. 수면 값만 계속 보내요", Copy.scenarioLine(sc3))
        assertEquals("0:00:00 경과. 더 바뀌는 값은 없어요", Copy.scenarioLine(sc3.copy(sleepOverrideMinutes = null)))
        assertEquals("0:00:00 경과. 마지막 값을 계속 보내요", Copy.scenarioLine(sc3.copy(glucoseActive = true)))
    }

    /** 8.3 고지 두 문장 */
    @Test
    fun requiredDisclosuresAreExact() {
        assertEquals("차 안내는 CGM 앱 알람을 대신하지 않아요. 알람을 그대로 켜 두세요.", Copy.NOTICE_ALARM)
        assertEquals("혈당이 높을 때는 안내하지 않아요.", Copy.NOTICE_HIGH)
    }

    /** 14장: 동의 다섯 항목. 측정값이 서버를 거친다는 말을 숨기지 않는다 */
    @Test
    fun consentCoversRequiredItems() {
        val text = Copy.CONSENT_ITEMS.joinToString { "${it.first} ${it.second}" }
        for (word in listOf("어젯밤 수면", "혈당값과 추세", "휴식과 정차", "서버를 거쳐", "최신 값 하나만", "15분", "다음 새벽 4시까지", "이력은 남기지 않아요", "시간 기준 안내")) {
            assertTrue(word in text, word)
        }
    }

    /**
     * W16, B-11. 현재 측정값은 서버를 지나므로 "측정 원본은 이 폰에만"류 문장을 어디에도 쓰지 않는다.
     * C1 "보관"과 C3 아래 줄은 같은 사실(최신 값 하나, 이력 없음)을 말하고, 화면의 15분은 계약의 혈당 유효 시간과 같다.
     */
    @Test
    fun retentionCopyMatchesWhatServerKeeps() {
        val copy = sources.first { it.name == "Copy.kt" }.readText()
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        val shown = literal.findAll(copy).map { it.groupValues[1] }.toList()
        for (phrase in listOf("이 폰에만", "측정 원본", "원본은", "폰 밖으로")) {
            assertTrue(shown.none { phrase in it }, "\"$phrase\"는 현재 측정값이 폰에 머문다고 읽힌다")
        }
        val keep = Copy.CONSENT_ITEMS.first { it.first == "보관" }.second
        for (fact in listOf("최신 값 하나만", "이력은 남기지 않아요")) {
            assertTrue(fact in keep, fact)
            assertTrue(fact in Copy.SENT_SCOPE, "C3 아래 줄도 같은 말: $fact")
        }
        assertEquals(900L, com.conditiondrive.core.contract.SummaryLimits.GLUCOSE_TTL_SEC, "화면의 15분이 바뀌면 문구도 고친다")
        assertTrue("혈당은 15분" in keep)
        assertTrue("서버를 거쳐" in Copy.CONSENT_ITEMS.first { it.first == "거치는 곳" }.second, "측정값이 서버를 지난다는 말은 숨기지 않는다")

        // 수면 보관은 SleepPolicy(C-36)와 대조한다. "그날까지"는 밤늦게 보낸 요약이 하루 더 남는 것과 맞지 않았다
        assertFalse("그날까지" in keep)
        val rule = com.conditiondrive.core.engine.Policy.DEFAULT.SLEEP_VALID_RULE
        assertEquals(java.time.LocalTime.of(4, 0), rule.boundary, "화면의 새벽 4시")
        assertEquals(java.time.LocalTime.of(22, 0), rule.boundary.minusHours(rule.minHoursUntilBoundary.toLong()), "화면의 밤 10시")
        for (text in listOf(keep, Copy.PAUSED_NOTE)) {
            assertTrue("다음 새벽 4시까지" in text && "밤 10시 넘어" in text && "하루 더" in text, text)
        }
        val zone = java.time.ZoneId.of("Asia/Seoul")
        fun until(local: String) = com.conditiondrive.core.contract.SleepPolicy
            .validUntil(java.time.OffsetDateTime.parse("2026-11-06T$local+09:00"), zone).toLocalDateTime().toString()
        assertEquals("2026-11-07T04:00", until("08:00:00"), "아침에 보내면 다음 새벽 4시")
        assertEquals("2026-11-07T04:00", until("22:00:00"), "밤 10시까지는 다음 새벽 4시")
        assertEquals("2026-11-08T04:00", until("22:00:01"), "밤 10시 넘어 보내면 하루 더")
    }

    /** D-32. 폰에서 해제·철회해도 차의 혈당 보류는 남는다. 푸는 곳을 폰 완료 문장이 알려 준다 */
    @Test
    fun unpairAndWithdrawTellWhereToReleaseCarHold() {
        for (notice in listOf(Notice.UNPAIRED_DONE, Notice.UNPAIRED_PENDING, Notice.WITHDRAWN_DONE, Notice.WITHDRAWN_PENDING)) {
            val text = Copy.notice(notice)
            assertTrue("차 설정의 연결된 기기에서 풀 수 있어요" in text, text)
            assertFalse("혈당" in text, text)
        }
        assertFalse("풀 수 있어요" in Copy.notice(Notice.SUMMARY_DELETED))
    }

    /** 14장: 알림에 수치와 "혈당" 없음 */
    @Test
    fun notificationHasNoHealthWord() {
        val service = sources.first { it.name == "SimulatorService.kt" }.readText()
        assertTrue("addAction(0, Copy.SIM_STOP" in service, "알림의 작업 버튼도 이 검사 대상이다")
        for (text in listOf(Copy.NOTIFICATION_CHANNEL, Copy.NOTIFICATION_TITLE, Copy.SIM_STOP)) {
            assertFalse("혈당" in text)
            assertFalse(text.any(Char::isDigit))
        }
    }

    /** 6.4. 고지문 파일은 경계를 말하느라 금칙어를 쓰므로 상표와 원시값만 본다 */
    @Test
    fun userFacingLiteralsAreClean() {
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        val failures = sources.flatMap { file ->
            val legal = file.name == "LegalCopy.kt"
            file.readLines().asSequence()
                .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
                .flatMap { line -> literal.findAll(line).map { it.groupValues[1] } }
                .filter { text -> text.any { it in '가'..'힣' } }
                .flatMap { text ->
                    val found = if (legal) ForbiddenWords.findTrademarks(text) + ForbiddenWords.findRawValues(text) else ForbiddenWords.find(text)
                    found.map { "${file.name}: $it [$text]" }
                }
                .toList()
        }
        assertEquals(emptyList(), failures)
    }

    /** 2장. 이 문자열들은 일부러 나눠 적었다. 합쳐 적으면 이 테스트 자신이 금지 패턴이 된다 */
    @Test
    fun noGlucoseReadFromHealthConnect() {
        val forbiddenPermission = "READ_BLOOD_" + "GLUCOSE"
        val forbiddenRecord = "BloodGlucose" + "Record"
        assertFalse(forbiddenPermission in manifest)
        sources.forEach {
            val text = it.readText()
            assertFalse(forbiddenRecord in text, it.name)
            assertFalse(forbiddenPermission in text, it.name)
        }
    }

    /** F6: Health Connect는 수면과 표시용 심박, 백그라운드 읽기만. 쓰기 권한 없음 */
    @Test
    fun healthPermissionsAreExactlyThese() {
        val declared = Regex("""android\.permission\.health\.[A-Z_]+""").findAll(manifest).map { it.value }.toSet()
        assertEquals(
            setOf(
                "android.permission.health.READ_SLEEP",
                "android.permission.health.READ_HEART_RATE",
                "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
            ),
            declared,
        )
    }

    /** 11.3 */
    @Test
    fun backupIsOff() {
        assertTrue("""android:allowBackup="false"""" in manifest)
        assertTrue("""android:dataExtractionRules="@xml/data_extraction_rules"""" in manifest)
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        assertTrue("<cloud-backup>" in rules && "<device-transfer>" in rules)
    }

    /** 11.3: 로그와 인터셉터 금지. 이 앱 코드에는 로그 호출 자체가 없다 */
    @Test
    fun noLoggingPathsExist() {
        val banned = listOf("HttpLogging" + "Interceptor", "android.util." + "Log", "Log." + "d(", "Log." + "i(", "Log." + "e(", "Log." + "w(", "print" + "ln(", "printStack" + "Trace")
        sources.forEach { file ->
            val text = file.readText()
            banned.forEach { assertFalse(it in text, "${file.name}: $it") }
        }
    }

    @Test
    fun deprecatedCryptoLibraryIsNotUsed() {
        // 주석에 이유로 적어 둔 곳이 있어 import만 본다
        sources.forEach { assertFalse("import androidx.security." + "crypto" in it.readText(), it.name) }
        val build = File(module, "build.gradle.kts").readText()
        assertFalse("security-" + "crypto" in build)
    }

    /** 7번 규칙. 상표와 공식 앱 표현 */
    @Test
    fun manifestAndResourcesHaveNoTrademarks() {
        assertEquals(emptyList(), ForbiddenWords.findTrademarks(File(main, "res/values/strings.xml").readText()))
    }

    /** 요약 입력 타입에 원본이 담길 필드가 없다. 필드가 늘면 이 테스트가 알린다 */
    @Test
    fun summaryInputShapeHasNoRawFields() {
        val fields = com.conditiondrive.companion.upload.SummaryInput::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }.toSet()
        assertEquals(setOf("sleep", "glucose"), fields)
        val sleep = SleepValue::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") || it == "Companion" }.toSet()
        assertEquals(setOf("minutes", "computedAtMs"), sleep)
    }
}
