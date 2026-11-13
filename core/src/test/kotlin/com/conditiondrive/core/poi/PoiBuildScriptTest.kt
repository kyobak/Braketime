package com.conditiondrive.core.poi

import com.conditiondrive.core.voice.RepoFiles
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * tools/poi_build.py의 종류 보존(P-15)을 check에서도 본다. 스크립트가 종류 열을 다시 버리면 화물차휴게소가 일반 휴게소처럼 안내된다.
 * 시험 본체는 tools/poi_build_test.py(표준 라이브러리, 네트워크 없음)이고 여기서는 실행만 한다. python3이 없는 기기에서는 건너뛴다.
 */
class PoiBuildScriptTest {
    @Test
    fun scriptKeepsFacilityColumn() {
        val python = listOf("/usr/bin/python3", "/opt/homebrew/bin/python3", "/usr/local/bin/python3").firstOrNull { File(it).canExecute() }
        assumeTrue(python != null, "python3 없음")
        // -I: 사용자 환경과 현재 폴더의 모듈을 읽지 않는다. -B: tools/ 아래에 캐시 파일을 남기지 않는다
        val process = ProcessBuilder(python, "-I", "-B", "tools/poi_build_test.py")
            .directory(RepoFiles.root)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().readText()
        if (!finished) process.destroyForcibly()
        assertTrue(finished, "60초 안에 끝나지 않음")
        assertEquals(0, process.exitValue(), output)
        assertTrue("OK" in output, output)
    }
}
