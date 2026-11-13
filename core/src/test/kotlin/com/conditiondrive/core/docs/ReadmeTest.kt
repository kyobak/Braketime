package com.conditiondrive.core.docs

import com.conditiondrive.core.voice.RepoFiles
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** platform-facts의 제출 조건과 설계서 18.3. 알려진 한계가 숨은 한계가 되지 않게 고지 항목의 존재를 CI가 본다 */
class ReadmeTest {
    private val readme by lazy { RepoFiles.text("README.md") }

    @Test
    fun hasSubmissionRequirements() {
        val required = listOf(
            "# Braketime",                         // 앱 이름(U-26)
            "3분 실행",
            "adb install",
            "설정** 탭 > **시연 모드**",            // 시연 시작 위치
            "apk/",                                // APK 위치
            "테스트 환경",
            "tools/vc.sh",                         // 콘솔 조작
            "서버 없이",
            "./gradlew check",
        )
        assertEquals(emptyList(), required.filter { it !in readme })
    }

    @Test
    fun explainsBothApks() {
        for (name in listOf("hyunkyo_Braketime_release.apk", "hyunkyo_BraketimeCompanion_release.apk")) {
            assertTrue(name in readme, name)
        }
        assertTrue("차량 앱" in readme && "폰 동반 앱" in readme)
        // 임시 팀명과 옛 이름이 설치 명령에 남으면 심사자가 없는 파일을 설치하려 한다
        assertTrue("TEAM_" !in readme && "ConditionDrive" !in readme)
    }

    /** 제출 규칙 팀명_앱이름_release.apk. 빌드가 내는 파일명과 README가 어긋나지 않게 빌드 설정을 함께 본다(U-26) */
    @Test
    fun apkNamesFollowSubmissionRule() {
        val props = RepoFiles.text("gradle.properties")
        assertTrue(Regex("""(?m)^cd\.apkTeam=hyunkyo$""").containsMatchIn(props))
        val vehicle = RepoFiles.text("vehicle/build.gradle.kts")
        val companion = RepoFiles.text("companion/build.gradle.kts")
        assertTrue("\"\${team}_Braketime_release.apk\"" in vehicle)
        assertTrue("\"\${team}_BraketimeCompanion_release.apk\"" in companion)
        for (build in listOf(vehicle, companion)) assertTrue("getOrElse(\"TEAM\")" !in build)
    }

    /** 사람이 가장 많이 보는 이름은 앱 라벨이다. 라벨이 README·파일명과 따로 놀면 안 된다(U-26) */
    @Test
    fun appLabelsMatchAppName() {
        val label = Regex("""<string name="app_name"[^>]*>([^<]*)</string>""")
        val vehicle = label.find(RepoFiles.text("vehicle/src/main/res/values/strings.xml"))?.groupValues?.get(1)
        val companion = label.find(RepoFiles.text("companion/src/main/res/values/strings.xml"))?.groupValues?.get(1)
        assertEquals("Braketime", vehicle)
        assertEquals("Braketime 동반 앱", companion)
        assertTrue("앱 목록에서 $vehicle" in readme)
    }

    @Test
    fun disclosesKnownLimits() {
        val limits = listOf(
            "실차 미검증", "CGM 실연동 없음", "시뮬레이션", "평문 HTTP", "출시 불가", "Grade C", "1형",
            "병력", "2030년", "심박", "효과는 측정하지",
            "실제 소리와 마이크를 확인하지 않았다", "H6",
        )
        assertEquals(emptyList(), limits.filter { it !in readme })
    }

    /** 14장 비공식 고지 */
    @Test
    fun saysItIsNotOfficial() {
        assertTrue("공식 앱이 아니며 후원이나 승인을 받지 않았습니다" in readme)
    }

    @Test
    fun hasSdkMappingAndLicense() {
        for (word in listOf("TextToSpeech", "SpeechToText", "FusedLocation", "NaviHelper", "HVAC", "Odometer", "Seat", "CONTROL_CAR_CLIMATE", "Pretendard", "OFL")) {
            assertTrue(word in readme, word)
        }
    }

    /** 사람이 정할 일을 잊지 않게 결정 기록 끝에 모은다 */
    @Test
    fun decisionsEndWithHumanItems() {
        val decisions = RepoFiles.text("docs/decisions.md")
        val heading = "## 제출 전 사람이 정할 것"
        val at = decisions.lastIndexOf("\n## ")
        assertTrue(decisions.substring(at + 1).startsWith(heading), "결정 기록의 마지막 절이 아니다")
    }

    /** 시연 시작 경로가 실제 화면 문구와 맞아야 README만 보고 재현할 수 있다(R9) */
    @Test
    fun scenarioNamesMatchTheApp() {
        val strings = File(RepoFiles.root, "vehicle/src/main/res/values/strings.xml").readText()
        for (id in listOf("demo_scenario_1", "demo_scenario_2", "demo_scenario_3", "s5_demo", "demo_stop", "demo_restart", "demo_send_place", "demo_real_signals")) {
            val text = Regex("""<string name="$id">([^<]+)</string>""").find(strings)?.groupValues?.get(1)
            assertTrue(text != null && text in readme, "$id: $text")
        }
    }
}
