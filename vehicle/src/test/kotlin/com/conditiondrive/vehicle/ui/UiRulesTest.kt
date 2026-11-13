package com.conditiondrive.vehicle.ui

import com.conditiondrive.vehicle.port.UiMode
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.LocationVia
import com.conditiondrive.vehicle.ui.model.MainGate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.voice.ForbiddenWords
import com.conditiondrive.vehicle.port.VehicleCapabilities
import com.conditiondrive.vehicle.ui.components.demoBannerText
import com.conditiondrive.vehicle.ui.components.demoMark
import com.conditiondrive.vehicle.ui.components.splitDemoMark
import com.conditiondrive.vehicle.ui.components.encodeQr
import com.conditiondrive.vehicle.ui.model.DeniedPermission
import com.conditiondrive.vehicle.ui.model.GlucoseHistory
import com.conditiondrive.vehicle.ui.model.GlucosePoint
import com.conditiondrive.vehicle.ui.model.HiddenTapCounter
import com.conditiondrive.vehicle.ui.model.S5Presenter
import com.conditiondrive.vehicle.ui.model.ServerUrlRule
import com.conditiondrive.vehicle.ui.model.TimePart
import com.conditiondrive.vehicle.ui.model.UiFormat
import com.conditiondrive.vehicle.ui.preview.UiSamples
import com.conditiondrive.vehicle.ui.theme.CdColors
import com.conditiondrive.vehicle.ui.theme.CdDayColors
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdNightColors
import com.conditiondrive.vehicle.ui.theme.CdTypeScale
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 토큰이 문서와 어긋나면 H7의 대비 계산이 코드와 다른 값을 검증하게 된다 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UiRulesTest {
    private val tokens = File(repoRoot, "docs/design-tokens.md").readText()

    private fun section(title: String): String {
        val start = tokens.indexOf(title)
        val next = Regex("""\n#{2,3} """).find(tokens, start + 1)?.range?.first ?: tokens.length
        return tokens.substring(start, next)
    }

    private fun hex(color: Color): String = "#%06X".format(color.toArgb() and 0xFFFFFF)

    private fun assertTable(heading: String, colors: CdColors) {
        val table = Regex("""^\| (\w+) \| (#[0-9A-F]{6}) \|""", RegexOption.MULTILINE)
            .findAll(section(heading)).associate { it.groupValues[1] to it.groupValues[2] }
        val actual = mapOf(
            "bg" to colors.bg, "surface1" to colors.surface1, "surface2" to colors.surface2,
            "text1" to colors.text1, "text2" to colors.text2, "text3" to colors.text3,
            "track" to colors.track, "hairline" to colors.hairline, "faceStrong" to colors.faceStrong,
            "onFaceCaption" to colors.onFaceCaption, "select" to colors.select,
        ).mapValues { hex(it.value) }
        assertEquals(table, actual, heading)
    }

    @Test
    fun colorTablesMatchDocument() {
        assertTable("### 2.1", CdDayColors)
        assertTable("### 2.2", CdNightColors)
    }

    @Test
    fun statusColorsMatchDocument() {
        val state = section("### 2.3")
        val used = listOf(CdDayColors, CdNightColors).flatMap { c ->
            listOfNotNull(c.cautionFace, c.onCaution, c.cautionOutline, c.stopFace, c.onStop, c.stopText, c.recover)
        }.map(::hex).toSet()
        used.forEach { assertTrue(it in state, "$it 가 2.3 표에 없다") }
        assertEquals("#FFFFFF", hex(CdDayColors.onFaceStrong))
        assertEquals("#131417", hex(CdNightColors.onFaceStrong))
    }

    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance() + 0.05
        val lb = b.luminance() + 0.05
        return maxOf(la, lb) / minOf(la, lb).toDouble()
    }

    /** F04: 표지 자막은 표지 문구보다 한 단계 낮지만 4.5:1 기준은 지킨다 */
    @Test
    fun demoCaptionIsDimmerButReadable() {
        listOf(CdDayColors, CdNightColors).forEach { c ->
            val caption = contrast(c.onFaceCaption, c.faceStrong)
            assertTrue(caption >= 4.5, "자막 대비 $caption")
            assertTrue(caption < contrast(c.onFaceStrong, c.faceStrong), "자막이 표지 문구와 구분되지 않는다")
        }
    }

    /** 3.2 스케일: 표의 sp, 굵기, 줄높이를 그대로 쓴다 */
    @Test
    fun typeScaleMatchesDocument() {
        val rows = Regex("""^\| (\w+) \| (\d+) \| W(\d+) \| ([\d.]+) \|""", RegexOption.MULTILINE)
            .findAll(section("### 3.2")).associate { it.groupValues[1] to it.groupValues.drop(2) }
        val actual = CdTypeScale.run {
            mapOf(
                "display" to display, "headlineM" to headlineM, "keyLabel" to keyLabel, "titleL" to titleL, "titleM" to titleM,
                "titleS" to titleS, "bodyL" to bodyL, "bodyM" to bodyM, "bodyS" to bodyS,
            )
        }
        assertEquals(rows.keys, actual.keys)
        actual.forEach { (name, style) ->
            val (sp, weight, line) = rows.getValue(name)
            assertEquals(sp.toFloat(), style.fontSize.value, name)
            assertEquals(weight.toInt(), style.fontWeight?.weight, name)
            assertEquals(line.toFloat(), style.lineHeight.value, 0.001f, name)
            // 큰 숫자만 비례 숫자다. tnum이면 160sp에서 "12"가 "1 2"로 벌어진다(3.2)
            assertEquals(if (name == "display") "pnum" else "tnum", style.fontFeatureSettings, name)
        }
    }

    /** 3.3: 한글은 어절 단위로만 줄을 바꾼다. 제목은 Heading, 본문은 Paragraph + Phrase, 로캘은 ko-KR */
    @Test
    fun koreanBreaksOnlyBetweenWords() {
        val styles = CdTypeScale.run {
            mapOf(
                "display" to display, "headlineM" to headlineM, "keyLabel" to keyLabel, "titleL" to titleL, "titleM" to titleM,
                "titleS" to titleS, "bodyL" to bodyL, "bodyM" to bodyM, "bodyS" to bodyS,
            )
        }
        styles.forEach { (name, style) ->
            assertEquals(LocaleList("ko-KR"), style.localeList, name)
            assertEquals(LineBreak.WordBreak.Phrase, style.lineBreak.wordBreak, name)
        }
        assertEquals(LineBreak.Heading, CdTypeScale.headlineM.lineBreak)
        assertEquals(LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase), CdTypeScale.bodyL.lineBreak)
    }

    /** 12sp, 24sp처럼 3mm에 미달하는 크기를 스케일에 넣지 않는다 */
    @Test
    fun nothingBelowBodyS() {
        val sizes = CdTypeScale.run { listOf(display, headlineM, keyLabel, titleL, titleM, titleS, bodyL, bodyM, bodyS) }.map { it.fontSize.value }
        assertTrue(sizes.min() >= 26f, sizes.toString())
    }

    @Test
    fun dimensionsMatchDocument() {
        assertEquals(40.dp, CdDimens.windowPadding(628.dp))
        assertEquals(40.dp, CdDimens.windowPadding(799.dp))
        assertEquals(48.dp, CdDimens.windowPadding(800.dp))
        assertEquals(48.dp, CdDimens.windowPadding(1700.dp))
        assertEquals(1100.dp, CdDimens.TwoPaneMinWidth)
    }

    /** 3.1 폭 검증: 가장 넓은 시간 문구 한 줄이 12.9 Portrait 내부 폭에 들어간다 */
    @Test
    fun timePartsSplitNumbersAndUnits() {
        assertEquals(
            listOf(TimePart("1", true), TimePart("시간 ", false), TimePart("59", true), TimePart("분", false)),
            UiFormat.timeParts("1시간 59분"),
        )
        assertEquals(listOf(TimePart("곧", false)), UiFormat.timeParts("곧"))
    }

    @Test
    fun formats() {
        assertEquals("800m", UiFormat.km(800))
        assertEquals("3.2km", UiFormat.km(3200))
        assertEquals("3km", UiFormat.km(3000))
        assertEquals("12km", UiFormat.km(12_340))
        assertEquals("1:45", UiFormat.clock(6300))
        assertEquals("2:00", UiFormat.clock(7200))
        assertEquals("5.4", UiFormat.mmol(5.4))
        assertEquals("4.1", UiFormat.mmol(4.1))
        // 기준 아래 값이 반올림으로 기준에 닿아 보이면 안 된다
        assertEquals("3.9", UiFormat.mmol(3.98))
    }

    @Test
    fun everyTrendHasKoreanLabel() {
        Trend.entries.forEach { assertTrue(com.conditiondrive.vehicle.ui.model.UiText.res(UiFormat.trend(it)).str().isNotBlank()) }
    }

    @Test
    fun serverUrlRule() {
        for (ok in listOf("https://x.example.com", "https://x.example.com/api", "http://10.0.2.2:8080", "http://127.0.0.1:8080/", " https://a.b ")) {
            assertTrue(ServerUrlRule.accepts(ok), ok)
        }
        for (bad in listOf("", "http://x.example.com", "http://192.168.0.2:8080", "http://10.0.2.2", "ftp://x", "https://", "http://10.0.2.2.evil.com:80", "http://127.0.0.1:80@evil.com")) {
            assertFalse(ServerUrlRule.accepts(bad), bad)
        }
    }

    @Test
    fun hiddenTapCounter() {
        val counter = HiddenTapCounter()
        repeat(4) { assertFalse(counter.tap(1000L + it)) }
        assertTrue(counter.tap(1005))
        // 간격이 벌어지면 처음부터 다시 센다
        val slow = HiddenTapCounter()
        repeat(4) { assertFalse(slow.tap(1000L + it)) }
        assertFalse(slow.tap(10_000))
    }

    /** 새 측정만 쌓고 값은 메모리에 제한된 개수만 둔다 */
    @Test
    fun glucoseHistory() {
        val a = UiSamples.view(mmol = 5.0, measuredAt = 1000)
        var h = GlucoseHistory.append(emptyList(), a)
        assertEquals(listOf(GlucosePoint(1000, 5.0)), h)
        assertEquals(h, GlucoseHistory.append(h, a))
        // 값이 끊긴 동안은 남기고, 값이 사라지면(연결 해제) 지운다
        assertEquals(h, GlucoseHistory.append(h, UiSamples.view(unavailable = true)))
        assertEquals(emptyList(), GlucoseHistory.append(h, UiSamples.view()))
        h = GlucoseHistory.append(h, UiSamples.view(mmol = 4.0, measuredAt = 2000))
        assertEquals(2, h.size)
        var many = h
        repeat(500) { many = GlucoseHistory.append(many, UiSamples.view(mmol = 5.0, measuredAt = 3000L + it)) }
        assertEquals(240, many.size)
    }

    /** 표지와 자막을 한 문자열로 내려도 첫 줄바꿈에서만 나뉜다. 자막은 여러 줄일 수 있다 */
    @Test
    fun demoMarkSplitsLabelAndCaption() {
        assertEquals("시연" to null, splitDemoMark(demoMark("시연", null)))
        assertEquals("시연" to "앱 음성: 쉬어 가세요\n공기 모드 읽음", splitDemoMark(demoMark("시연", "앱 음성: 쉬어 가세요\n공기 모드 읽음")))
    }

    @Test
    fun demoBannerDoesNotDuplicate() {
        assertEquals("시연 · 혈당 시뮬레이션", demoBannerText("시연", true, "혈당 시뮬레이션"))
        assertEquals("시연", demoBannerText("시연", false, "혈당 시뮬레이션"))
        assertEquals("시연 · 혈당 시뮬레이션", demoBannerText("시연 · 혈당 시뮬레이션", true, "혈당 시뮬레이션"))
    }

    private fun supportLines(facts: SupportFacts, denied: Set<DeniedPermission> = emptySet()) =
        S5Presenter.supportRows(facts, denied).map { "${it.label.str()}: ${it.value.str()}" }

    /** 8.3 지원 현황 예문과 13장 표. 미지원을 문장으로 알린다 */
    @Test
    fun supportSentences() {
        val facts = SupportFacts(
            capabilities = VehicleCapabilities(gear = true, speed = false, ignition = false),
            naviAvailable = true,
            waypointSupported = false,
            speaker = EngineKind.VEHICLE,
            listener = EngineKind.NONE,
            location = LocationVia.NONE,
            ventilationSupported = false,
            occupancySupported = false,
        )
        val lines = supportLines(facts, setOf(DeniedPermission.MIC))
        for (expected in listOf(
            "남은 경로 시간: 내비 경로가 있을 때",
            "경유지 추가: 이 차량에서 지원 안 됨",
            "음성: 차량 음성 엔진 사용 중",
            "차속: 쓸 수 없어요. 기어로 판단해요",
            "공기 모드 제안: 이 차량에서 지원 안 됨",
            "마이크 권한: 허용 안 됨",
        )) {
            assertTrue(expected in lines, "$expected / $lines")
        }
        assertEquals("화면 정보: 밀도 1.0, ydpi 160.0", S5Presenter.screenLine(1f, 160f).str())
        lines.forEach { assertEquals(emptyList(), ForbiddenWords.find(it), it) }
    }

    @Test
    fun noDriveSignalIsOneSentence() {
        val facts = SupportFacts(capabilities = VehicleCapabilities(gear = false, speed = false, ignition = true))
        val lines = supportLines(facts)
        assertTrue(lines.any { "운전 시간을 세지 않아요" in it }, lines.toString())
        assertFalse(lines.any { it.startsWith("기어:") }, lines.toString())
    }

    @Test
    fun unknownFactsReadAsChecking() {
        val lines = supportLines(SupportFacts())
        assertTrue(lines.any { it == "기어: 확인 중이에요" }, lines.toString())
    }

    /** 8.3 기준과 출처, 14장 "캐나다 지침", "설계값", 값은 Policy와 같다 */
    @Test
    fun sourcesCiteGuideline() {
        val lines = S5Presenter.sourceLines().map { it.str() }
        assertTrue(lines.any { "화물자동차 운수사업법 시행규칙 제21조·제22조" in it && "참고" in it && "법적 의무" in it }, lines.toString())
        // U-28. 1시간 45분은 규정이 아니라 설계값이고, 값은 Policy에서 읽는다
        assertTrue(lines.any { "기본 주의 시점 1시간 45분" in it && "정차 권고 15분 전" in it && "설계값" in it }, lines.toString())
        assertTrue(lines.any { "4.0, 5.0, 40분" in it && "Diabetes Canada 2018 21장" in it && "캐나다 지침" in it }, lines.toString())
        assertTrue(lines.any { "수면 6시간 미만이면 30분 앞당김" in it && "설계값" in it }, lines.toString())
        assertTrue(lines.any { "7.3" in it && "5.7" in it && "5.0" in it && "설계값" in it }, lines.toString())
        assertTrue(lines.any { "제조사 공식 앱이 아니에요" in it }, lines.toString())
    }

    /** 12.4: QR에는 코드만 들어가고 카메라가 읽을 조용한 영역이 있다 */
    @Test
    fun qrEncodes() {
        val matrix = encodeQr("conditiondrive://pair?v=1&code=K7M2QX9T")
        assertEquals(matrix.width, matrix.height)
        assertTrue(matrix.width > 21)
        for (i in 0 until matrix.width) assertFalse(matrix.get(i, 0), "위쪽 조용한 영역")
    }

    /** QR은 야간에도 어두운 칸이 밝은 면 위에 온다 */
    @Test
    fun qrColorsAreDarkOnLight() {
        listOf(CdDayColors, CdNightColors).forEach { c ->
            assertTrue(c.qrDark.red + c.qrDark.green + c.qrDark.blue < c.qrLight.red + c.qrLight.green + c.qrLight.blue)
        }
    }

    /** 6.4 strings_legal.xml 예외가 실제로 고지문에만 쓰인다 */
    @Test
    fun legalStringsStayInLegalFile() {
        val res = File(repoRoot, "vehicle/src/main/res/values/strings.xml").readText()
        val found = Regex("""<string[^>]*>(.*?)</string>""").findAll(res).flatMap { ForbiddenWords.find(it.groupValues[1]) }.toList()
        assertEquals(emptyList(), found)
    }

    /** 8.3 앱을 켠 직후 P인데 S1이 먼저 보이지 않는다. 실제로 달리면 바로 S1이다 */
    @Test
    fun coldStartWaitsOnlyBeforeFirstJudgement() {
        assertTrue(MainGate.showWaiting(settled = false, UiMode.DRIVING, driving = false, onboardingPending = false))
        assertFalse(MainGate.showWaiting(settled = false, UiMode.DRIVING, driving = true, onboardingPending = false))
        assertFalse(MainGate.showWaiting(settled = true, UiMode.DRIVING, driving = false, onboardingPending = false))
        assertFalse(MainGate.showWaiting(settled = true, UiMode.PARKED, driving = false, onboardingPending = false))
        // 온보딩이 곧 덮는다. S2가 먼저 보이면 첫 화면이 온보딩이 아니다
        assertTrue(MainGate.showWaiting(settled = true, UiMode.PARKED, driving = false, onboardingPending = true))
        // 주행 중 첫 실행은 온보딩 없이 S1이다(8.3)
        assertFalse(MainGate.showWaiting(settled = true, UiMode.DRIVING, driving = true, onboardingPending = true))
    }

    /** 이력이 런타임 사이에 이어지면 곡선이 절벽이 되고 재실행 때 멈춘다 */
    @Test
    fun glucoseHistoryRestartsWhenTimeGoesBack() {
        val later = GlucoseHistory.append(emptyList(), UiSamples.view(mmol = 6.0, measuredAt = 1_000_000))
        val h = GlucoseHistory.append(later, UiSamples.view(mmol = 5.0, measuredAt = 500))
        assertEquals(listOf(GlucosePoint(500, 5.0)), h)
    }

    @Test
    fun glucoseHistoryKeepsOnlyRecentWindow() {
        val hour = 3_600_000L
        var h = GlucoseHistory.append(emptyList(), UiSamples.view(mmol = 6.0, measuredAt = hour))
        h = GlucoseHistory.append(h, UiSamples.view(mmol = 5.5, measuredAt = 2 * hour))
        h = GlucoseHistory.append(h, UiSamples.view(mmol = 5.0, measuredAt = 8 * hour))
        assertEquals(listOf(2 * hour, 8 * hour), h.map { it.atMs })
    }

    /** 재시작 후 서버에 닿지 않아도 연결은 살아 있다. "없음"이면 없는 연결을 다시 맺으려 한다 */
    @Test
    fun s5RowsKeepLinkWhileOffline() {
        assertEquals("서버 연결 안 됨", S5Presenter.rows(LinkState.OFFLINE, null, SettingsFacts()).devices.str())
        assertEquals("없음", S5Presenter.rows(LinkState.UNPAIRED, null, SettingsFacts()).devices.str())
        assertEquals("폰", S5Presenter.rows(LinkState.FRESH, "폰", SettingsFacts()).devices.str())
    }

    @Test
    fun seatLineSaysUnverified() {
        val lines = supportLines(SupportFacts(occupancySupported = true))
        assertTrue(lines.any { "확인하기 전이라 혈당 수치를 숨겨요" in it }, lines.toString())
    }

    /** poi-sources.md: 앱 정보에 출처와 한계를 적는다 */
    @Test
    fun sourcesNamePoiDataAndLimits() {
        val lines = S5Presenter.sourceLines().map { it.str() }
        assertTrue(lines.any { "한국도로공사" in it && "2026-09-17" in it && "임시 폐쇄" in it }, lines.toString())
    }

    /** 대체 경로를 FusedLocation처럼 말하지 않는다 */
    @Test
    fun locationLineNamesTheSource() {
        fun line(via: LocationVia?) = supportLines(SupportFacts(location = via)).first { it.startsWith("위치:") }
        assertEquals("위치: 쉼터를 찾을 때 차량 위치 서비스 사용 중", line(LocationVia.FUSED))
        assertEquals("위치: 차량 위치 서비스 값이 없어 내비 위치로 대신해요", line(LocationVia.NAVI))
        assertEquals("위치: 차량 위치 서비스 값이 없어 기기 위치로 대신해요", line(LocationVia.DEVICE))
        assertEquals("위치: 위치를 몰라 장소 없이 안내해요", line(LocationVia.NONE))
        assertEquals("위치: 확인 중이에요", line(null))
    }

    /** 재검증: FUSED_LOCATION이 온보딩 요청에 없어 -g 없는 설치에서 FusedLocation SDK를 호출하지 못했다 */
    @Test
    fun onboardingRequestsEveryDangerousPermissionInTheManifest() {
        val manifest = java.io.File(repoRoot, "vehicle/src/main/AndroidManifest.xml").readText()
        // spike-results "권한" 표의 dangerous 목록
        val dangerous = listOf(
            "android.car.permission.CAR_SPEED",
            "pleos.car.permission.FUSED_LOCATION",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.POST_NOTIFICATIONS",
        )
        for (permission in dangerous) {
            assertTrue("android:name=\"$permission\"" in manifest, permission)
            assertTrue(permission in DeniedPermission.REQUESTED, permission)
        }
        assertEquals(DeniedPermission.LOCATION, DeniedPermission.REQUESTED.getValue("pleos.car.permission.FUSED_LOCATION"))
    }

    /** 재검증(U-17): 방향을 고정하면 Pleos 세로 창에서 레터박스돼 창 아래가 비고 기록 탭 끝줄이 잘렸다 */
    @Test
    fun noActivityLocksScreenOrientation() {
        val manifest = java.io.File(repoRoot, "vehicle/src/main/AndroidManifest.xml").readText()
        assertTrue("screenOrientation" !in manifest, "방향은 고정하지 않고 실제 폭으로 레이아웃을 나눈다")
        // 회전·창 크기 변경에 Activity를 다시 만들지 않아야 주행 중 화면 상태가 유지된다
        assertTrue("orientation|screenSize|smallestScreenSize|screenLayout" in manifest)
    }

    /**
     * Pleos CCP 정책(U-27): 로터리를 지원하지 않는 사용자 설치 앱은 <application> 안에서 미지원을 선언한다.
     * 선언이 빠지면 CCP 포커스 이동 심사 대상이 되는데 앱에는 포커스 순서가 없다.
     */
    @Test
    fun declaresRotaryUnsupportedInApplication() {
        val manifest = java.io.File(repoRoot, "vehicle/src/main/AndroidManifest.xml").readText()
            .replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
        val application = manifest.substring(manifest.indexOf("<application"), manifest.indexOf("</application>"))
        // 컴포넌트 안의 meta-data는 그 컴포넌트 것이다. 블록과 자기 닫힘 태그를 지우고 <application> 바로 아래만 본다
        val direct = Regex("""<(activity|service|receiver)\b[^>]*?/>|<(activity|service|receiver)\b[^>]*>.*?</\2>""", RegexOption.DOT_MATCHES_ALL)
            .replace(application, "")
        val rotary = Regex("""<meta-data\s+android:name="com\.hmg\.rotary\.ROTARY_SUPPORTED"\s+android:value="(\w+)"\s*/>""")
        assertEquals(listOf("false"), rotary.findAll(direct).map { it.groupValues[1] }.toList())
        assertEquals(1, rotary.findAll(manifest).count())
    }
}
