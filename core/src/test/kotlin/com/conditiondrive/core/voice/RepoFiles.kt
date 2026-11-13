package com.conditiondrive.core.voice

import java.io.File

/** 문서와 다른 모듈의 문자열을 원본으로 대조하는 테스트용. Gradle과 IDE의 작업 디렉터리가 달라도 저장소 루트를 찾는다 */
object RepoFiles {
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    fun text(relative: String): String = File(root, relative).readText()

    /** "### a" 제목부터 다음 같은 수준 제목 전까지 */
    fun section(markdown: String, heading: String): String {
        val lines = markdown.lines()
        val start = lines.indexOfFirst { it.startsWith(heading) }
        check(start >= 0) { "제목을 찾지 못함: $heading" }
        val level = heading.takeWhile { it == '#' }.length
        val end = (start + 1 until lines.size).firstOrNull { i ->
            val hashes = lines[i].takeWhile { it == '#' }.length
            hashes in 1..level && lines[i].getOrNull(hashes) == ' '
        } ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }
}
