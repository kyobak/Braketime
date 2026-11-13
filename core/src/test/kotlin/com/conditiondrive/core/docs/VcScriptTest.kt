package com.conditiondrive.core.docs

import com.conditiondrive.core.voice.RepoFiles
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * tools/vc.sh를 가짜 adb와 curl로 돌린다.
 * 재검증: 고정 시간표가 V04 응답 대기 중에 P를 넣어 README대로 돌린 영상 경로의 대조가 "불일치 9건"이었다.
 */
class VcScriptTest {
    private val dir: File = Files.createTempDirectory("vc").toFile()
    private val events = File(dir, "events")
    private val cues = File(dir, "cues")
    private val report = File(dir, "report")
    private val serial = "vc-test-${System.nanoTime()}"

    init {
        fake(
            "platform-tools/adb",
            """
            #!/usr/bin/env bash
            # -s SERIAL 다음이 명령이다
            shift 2
            case "${'$'}1" in
              forward) exit 0 ;;
              logcat)
                if [[ "${'$'}*" == *CdDemoReport* ]]; then cat "$report"; exit 0; fi
                # 호출마다 다음 줄로 넘어가고 마지막 줄에 머문다. 앱의 대기 상태가 시간에 따라 바뀌는 것을 흉내 낸다
                n=${'$'}(cat "$dir/n" 2>/dev/null || echo 0)
                n=${'$'}((n + 1))
                echo "${'$'}n" > "$dir/n"
                total=${'$'}(wc -l < "$cues")
                if [[ ${'$'}n -gt ${'$'}total ]]; then n=${'$'}total; fi
                line=${'$'}(sed -n "${'$'}{n}p" "$cues")
                echo "--------- beginning of main"
                echo "${'$'}line"
                echo "cue ${'$'}line" >> "$events"
                ;;
              *) echo "adb ${'$'}*" >> "$events"; exit 20 ;;
            esac
            """,
        )
        fake(
            "bin/curl",
            """
            #!/usr/bin/env bash
            url="${'$'}{@: -1}"
            case "${'$'}url" in
              */browser/session*) exit 0 ;;
              */vehicle/state)
                gear=${'$'}(cat "$dir/gear" 2>/dev/null || echo Park)
                echo "{\"gear\":{\"actual\":{\"value\":\"${'$'}gear\"}},\"controls\":{\"speed_kph\":0}}"
                ;;
              */vehicle/gear*)
                g="${'$'}{url##*gear=}"
                echo "${'$'}g" > "$dir/gear"
                echo "gear ${'$'}g" >> "$events"
                echo "{}"
                ;;
              */vehicle/controls*)
                s="${'$'}{url##*speed=}"
                echo "speed ${'$'}{s%%&*}" >> "$events"
                echo "{}"
                ;;
            esac
            """,
        )
    }

    private fun fake(path: String, body: String) {
        val file = File(dir, path)
        file.parentFile.mkdirs()
        file.writeText(body.trimIndent() + "\n")
        file.setExecutable(true)
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("bash", File(RepoFiles.root, "tools/vc.sh").path) + args)
            .redirectErrorStream(true)
            .apply {
                environment()["ANDROID_HOME"] = dir.path
                environment()["PATH"] = "${File(dir, "bin").path}:${environment()["PATH"]}"
                environment()["VC_SERIAL"] = serial
                environment()["VC_POLL_SEC"] = "0"
                environment()["VC_WAIT_SEC"] = "30"
            }
            .start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "vc.sh가 끝나지 않았다")
        return process.exitValue() to process.inputStream.bufferedReader().readText()
    }

    private fun gearEvents() = events.readLines().filter { it.startsWith("gear ") }

    @AfterTest
    fun cleanUp() {
        File(RepoFiles.root, ".vc/heartbeat.$serial.pid").delete()
        dir.deleteRecursively()
    }

    @Test
    fun `scenario는 앱이 기다릴 때만 기어를 넣는다`() {
        cues.writeText(
            """
            run=7 id=sc3 await=none
            run=7 id=sc3 await=none
            run=7 id=sc3 await=DRIVE
            run=7 id=sc3 await=DRIVE
            run=7 id=sc3 await=none
            run=7 id=sc3 await=none
            run=7 id=sc3 await=none
            run=7 id=sc3 await=PARK
            run=7 id=sc3 await=none
            run=7 id=sc3 await=done

            """.trimIndent(),
        )
        report.writeText("sc3|대조 결과: 일치 (시뮬레이션 시각 ±1초)\n")
        val (code, out) = run("scenario", "sc3")
        assertEquals(0, code, out)
        assertTrue("대조 결과: 일치" in out, out)

        val log = events.readLines()
        // 맨 앞 P 고정 뒤, D는 D 대기를 본 다음에, P는 P 대기를 본 다음에만 들어간다
        assertEquals(listOf("gear Park", "gear Drive", "gear Park"), gearEvents())
        val drive = log.indexOf("gear Drive")
        val lastPark = log.lastIndexOf("gear Park")
        assertTrue(log.indexOf("cue run=7 id=sc3 await=DRIVE") in 0 until drive, log.toString())
        assertTrue(log.indexOf("cue run=7 id=sc3 await=PARK") in drive until lastPark, log.toString())
        assertTrue("speed 80" in log, log.toString())
    }

    @Test
    fun `끝난 이전 실행의 줄을 이번 실행으로 읽지 않는다`() {
        cues.writeText(
            """
            run=3 id=sc1 await=done
            run=3 id=sc1 await=done
            run=9 id=sc1 await=DRIVE
            run=9 id=sc1 await=PARK
            run=9 id=sc1 await=done

            """.trimIndent(),
        )
        report.writeText("sc1|대조 결과: 일치\n")
        val (code, out) = run("scenario", "sc1")
        assertEquals(0, code, out)
        assertEquals(listOf("gear Park", "gear Drive", "gear Park"), gearEvents())
    }

    @Test
    fun `다른 시나리오의 대기에는 기어를 넣지 않고 대조 불일치면 실패로 끝난다`() {
        cues.writeText(
            """
            run=4 id=sc2 await=DRIVE
            run=5 id=sc3 await=done

            """.trimIndent(),
        )
        report.writeText("sc3|대조 결과: 불일치 9건\n")
        val (code, out) = run("scenario", "sc3")
        assertEquals(1, code, out)
        assertTrue("불일치 9건" in out, out)
        assertEquals(listOf("gear Park"), gearEvents())
    }

    @Test
    fun `README가 적은 명령은 전부 vc_sh에 있다`() {
        val script = RepoFiles.text("tools/vc.sh")
        val main = script.substringAfter("vc_main() {")
        val commands = Regex("""^\s+([a-z-]+)\)""", RegexOption.MULTILINE).findAll(main).map { it.groupValues[1] }.toSet()
        val readme = RepoFiles.text("README.md")
        val used = Regex("""tools/vc\.sh ([a-z-]+)""").findAll(readme).map { it.groupValues[1] }.toSet()
        assertTrue("scenario" in used && "hvac" in used, used.toString())
        assertEquals(emptySet(), used - commands)
    }

    /** platform-facts: 이 이미지에는 hmg_car_service가 없다. README 표와 스크립트가 없는 명령을 된다고 하면 안 된다 */
    @Test
    fun `없는 주행 제한 강제 명령을 쓰지 않는다`() {
        assertFalse("hmg_car_service" in RepoFiles.text("tools/vc.sh").substringAfter("vc_main() {"))
        assertFalse("regulation on" in RepoFiles.text("README.md"))
        val (code, _) = run("regulation", "on")
        assertEquals(2, code)
        assertFalse(events.exists() && events.readText().contains("hmg_car_service"))
    }
}
