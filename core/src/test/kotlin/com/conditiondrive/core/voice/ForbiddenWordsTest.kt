package com.conditiondrive.core.voice

import com.conditiondrive.core.poi.PlaceCatalog
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 설계서 6.4. 음성 전수, 내장 장소 이름, 차량 앱 S1~S4 문자열을 함께 본다 */
class ForbiddenWordsTest {
    @Test
    fun listMatchesDesignSection() {
        val section = RepoFiles.section(RepoFiles.text("docs/DESIGN.md"), "### 6.4")
        val backticked = Regex("`([^`]+)`").findAll(section).map { it.groupValues[1] }.toList()
        val notWords = setOf("strings_legal.xml", "core.voice.ForbiddenWords")
        val known = ForbiddenWords.MEDICAL + ForbiddenWords.TRADEMARKS + ForbiddenWords.RAW_VALUES
        assertEquals(emptyList(), backticked.filter { it !in notWords && it !in known })
        assertTrue(backticked.size >= 28, "문단을 찾지 못함: ${backticked.size}")
    }

    /** 검사기가 실제로 잡는지 먼저 확인한다. 안 잡으면 아래 전수 검사가 거짓 통과한다 */
    @Test
    fun scannerCatchesEveryWordAndIgnoresSubstrings() {
        for (word in ForbiddenWords.MEDICAL + ForbiddenWords.TRADEMARKS + ForbiddenWords.RAW_VALUES) {
            assertTrue(word in ForbiddenWords.find("앞 $word 뒤"), word)
        }
        assertTrue("Pleos" in ForbiddenWords.find("pleos 화면"))
        assertTrue("null" in ForbiddenWords.find("값이 NULL 입니다"))
        assertEquals(emptyList(), ForbiddenWords.find("MAIN FAILED annulled"))
    }

    @Test
    fun everyVoiceStringIsClean() {
        val all = VoiceCatalog.allStrings()
        assertTrue(all.size > 40, "문구가 너무 적음: ${all.size}")
        assertEquals(emptyMap(), all.associateWith(ForbiddenWords::find).filterValues { it.isNotEmpty() })
    }

    /** 장소 이름은 그대로 음성이 된다 */
    @Test
    fun bundledPlaceNamesAreClean() {
        val places = PlaceCatalog.parse(RepoFiles.text(POI_PATH))
        assertTrue(places.isNotEmpty())
        val failures = places.map { Formats.placeName(it.name) }.associateWith(ForbiddenWords::find).filterValues { it.isNotEmpty() }
        assertEquals(emptyMap(), failures)
    }

    @Test
    fun vehicleResourceStringsAreClean() {
        val failures = resourceStrings(File(RepoFiles.root, "vehicle/src")).flatMap { (file, value) ->
            val legal = file.name == "strings_legal.xml"
            val found = if (legal) ForbiddenWords.findTrademarks(value) + ForbiddenWords.findRawValues(value) else ForbiddenWords.find(value)
            found.map { "${file.name}: $it [$value]" }
        }
        assertEquals(emptyList(), failures)
    }

    /** 상표 금지는 고지문까지 모든 사용자 문자열에 적용된다 */
    @Test
    fun companionResourceStringsHaveNoTrademarks() {
        val failures = resourceStrings(File(RepoFiles.root, "companion/src")).flatMap { (file, value) ->
            ForbiddenWords.findTrademarks(value).map { "${file.name}: $it [$value]" }
        }
        assertEquals(emptyList(), failures)
    }

    /** 띄어쓰기와 존댓말만 다른 문구가 빠져나가면 뜻이 같은 문장을 고칠 때 시험이 막아 주지 못한다 */
    @Test
    fun catchesSpacingAndPoliteVariants() {
        for (text in listOf("운전금지 구역", "운전 금지", "출발해도 돼요", "출발해도 됩니다", "출발 해도 돼요", "정상입니다", "안전합니다", "안전 합니다")) {
            assertTrue(ForbiddenWords.findMedical(text).isNotEmpty(), text)
        }
    }

    /** 코드에 직접 쓴 한글 문자열. 고지문은 strings_legal.xml에만 있으므로 코드에서는 예외가 없다. 시연 표지와 자막도 대상이다 */
    @Test
    fun vehicleCodeLiteralsAreClean() {
        val ui = File(RepoFiles.root, "vehicle/src/main/kotlin/com/conditiondrive/vehicle")
        val failures = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().asSequence()
                    .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
                    .flatMap { line -> STRING_LITERAL.findAll(line).map { it.groupValues[1] } }
                    .filter { literal -> literal.any { it in '가'..'힣' } }
                    .flatMap { literal -> ForbiddenWords.find(literal).map { "${file.name}: $it [$literal]" } }
            }
            .toList()
        assertEquals(emptyList(), failures)
    }

    private fun resourceStrings(src: File): List<Pair<File, String>> = src.walkTopDown()
        .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
        .flatMap { file -> STRING_RESOURCE.findAll(file.readText()).map { file to it.groupValues[1] } }
        .toList()

    private companion object {
        const val POI_PATH = "vehicle/src/main/assets/poi.json"
        val STRING_RESOURCE = Regex("""<(?:string|item)\b[^>]*>(.*?)</(?:string|item)>""", RegexOption.DOT_MATCHES_ALL)
        val STRING_LITERAL = Regex(""""((?:[^"\\]|\\.)*)"""")
    }
}
