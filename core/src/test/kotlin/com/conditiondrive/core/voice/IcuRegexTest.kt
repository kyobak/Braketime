package com.conditiondrive.core.voice

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** 앱은 Android ICU 정규식으로 돈다. 짝 없는 중괄호는 JVM에서만 통과해 실기에서 초기화 실패로 음성 전체가 죽는다 */
class IcuRegexTest {
    private val literal = Regex("Regex\\(\"\"\"(.*?)\"\"\"")

    private fun repoRoot(): File {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").exists()) dir = dir.parentFile
        return dir
    }

    /** 이스케이프, \p{..}, 문자 클래스, 수량자를 지우면 중괄호가 남지 않아야 한다 */
    private fun strayBrace(pattern: String): Boolean {
        val stripped = pattern
            .replace(Regex("\\\\[pP]\\{[^}]*\\}"), "")
            .replace(Regex("\\\\."), "")
            .replace(Regex("\\[[^\\]]*\\]"), "")
            .replace(Regex("\\{\\d+(,\\d*)?\\}"), "")
            .replace(Regex("\\$\\{[^}]*\\}"), "")
        return stripped.contains('{') || stripped.contains('}')
    }

    @Test
    fun `짝 없는 중괄호를 잡아낸다`() {
        assertTrue(strayBrace("""\{[^}]*}"""))
        assertTrue(!strayBrace("""\{[^}]*\}"""))
        assertTrue(!strayBrace("""\d{4}-\d{1,2}[\p{P}]"""))
    }

    @Test
    fun `앱 소스의 정규식에 짝 없는 중괄호가 없다`() {
        val root = repoRoot()
        val bad = listOf("core", "vehicle", "companion")
            .flatMap { File(root, "$it/src/main/kotlin").walkTopDown().filter { f -> f.extension == "kt" }.toList() }
            .flatMap { file -> literal.findAll(file.readText()).map { file.name to it.groupValues[1] }.toList() }
            .filter { strayBrace(it.second) }
        assertTrue(bad.isEmpty(), "ICU에서 죽는 정규식: $bad")
    }
}
