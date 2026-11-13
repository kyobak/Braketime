package com.conditiondrive.vehicle.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** H1b와 디자인 금지 항목. 실행 테스트가 못 보는 코드 경로까지 소스로 막는다 */
class S1NoTouchSourceTest {
    private val ui = File(repoRoot, "vehicle/src/main/kotlin/com/conditiondrive/vehicle/ui")
    private val s1 = File(ui, "screens/S1Screen.kt")
    private val passive = listOf(File(ui, "components/Passive.kt"), File(ui, "components/Window.kt"))

    private val forbidden = listOf(
        "clickable", "pointerInput", "Animated", "animate", "Crossfade", "updateTransition", "InfiniteTransition",
        "infiniteTransition", "ripple", "indication", "focusable", "verticalScroll", "horizontalScroll", "draggable",
        "swipeable", "selectable", "toggleable", "TextField", "LaunchedEffect", "withFrame",
    )

    private fun File.code(): List<String> = readLines()
        .map { it.trim() }
        .filterNot { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") }

    @Test
    fun s1AndItsPartsHaveNoTouchOrMotion() {
        for (file in passive + s1) {
            val hits = file.code().filter { line -> forbidden.any { it in line } }
            assertEquals(emptyList(), hits, file.name)
        }
    }

    /** 검사가 빈 파일을 보고 통과하지 않게 하고, 금지어가 실제로 잡히는지 터치 부품으로 확인한다 */
    @Test
    fun scannerSeesRealCode() {
        assertTrue(s1.code().any { "fun S1Screen" in it })
        assertTrue(File(ui, "components/touch/Touch.kt").code().any { "clickable" in it })
    }

    @Test
    fun s1DoesNotImportTouchParts() {
        val imports = (passive + s1).flatMap { it.code() }.filter { it.startsWith("import ") }
        assertEquals(emptyList(), imports.filter { "components.touch" in it })
    }

    /** S1이 쓰는 부품이 표시 전용 파일 안에만 있어야 위 검사가 S1 전체를 덮는다 */
    @Test
    fun s1UsesOnlyPassiveFiles() {
        val declared = passive.flatMap { file ->
            Regex("""(?:fun|val)\s+(?:\w+\.)?(\w+)""").findAll(file.readText()).map { it.groupValues[1] }.toList()
        }.toSet() + setOf("TAG_RIGHT_PANE")
        val used = s1.code().filter { it.startsWith("import com.conditiondrive.vehicle.ui.components.") }
            .map { it.substringAfterLast('.') }
        assertTrue(used.isNotEmpty())
        assertEquals(emptyList(), used.filter { it !in declared })
    }

    /** S1Screen은 콜백을 받지 않는다. 함수 타입 인자가 생기면 터치 경로가 생긴 것이다 */
    @Test
    fun s1SignatureHasNoCallback() {
        val signature = Regex("""fun S1Screen\(([^)]*)\)""").find(s1.readText())!!.groupValues[1]
        assertTrue("->" !in signature, signature)
    }

    @Test
    fun noMaterialNoRoundNoShadowNoGradientInUi() {
        val banned = listOf("material3", "compose.material", "RoundedCornerShape", "CircleShape", "shadow(", "Brush.", "ripple(", "Crossfade", "AnimatedContent", "AnimatedVisibility")
        val hits = ui.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            file.code().filter { line -> banned.any { it in line } }.map { "${file.name}: $it" }
        }.toList()
        assertEquals(emptyList(), hits)
    }

    /** design-tokens: hex를 직접 쓰지 않는다 */
    @Test
    fun colorsOnlyInTheme() {
        val hits = ui.walkTopDown().filter { it.extension == "kt" && it.name != "CdTheme.kt" }.flatMap { file ->
            file.code().filter { Regex("""Color\(0x|Color\.(Red|Blue|Green|Black|White|Yellow|Gray)""").containsMatchIn(it) }.map { "${file.name}: $it" }
        }.toList()
        assertEquals(emptyList(), hits)
    }

    /** 모든 문구는 리소스 또는 :core에서 온다. 프리뷰 표본 값만 예외다 */
    @Test
    fun noHangulLiteralsOutsidePreview() {
        val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
        val hits = ui.walkTopDown()
            .filter { it.extension == "kt" && it.parentFile.name != "preview" }
            .flatMap { file ->
                file.code().flatMap { line -> literal.findAll(line).map { it.groupValues[1] }.toList() }
                    .filter { text -> text.any { it in '가'..'힣' } }
                    .map { "${file.name}: $it" }
            }.toList()
        assertEquals(emptyList(), hits)
    }

    @Test
    fun activitiesUseOnlyMaterialFreeImports() {
        val activities = listOf("MainActivity.kt", "SetupActivity.kt").map { File(repoRoot, "vehicle/src/main/kotlin/com/conditiondrive/vehicle/$it") }
        assertEquals(emptyList(), activities.flatMap { it.code() }.filter { "material" in it })
    }
}
