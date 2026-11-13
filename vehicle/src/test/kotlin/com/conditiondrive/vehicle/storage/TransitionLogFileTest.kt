package com.conditiondrive.vehicle.storage

import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.LogEvent
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.voice.VoiceId
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitionLogFileTest {
    @get:Rule val temp = TemporaryFolder()
    private fun entry(at: Long) = TransitionEntry(at, DriverState.NORMAL, DriverState.CAUTION, setOf(Reason.FATIGUE), listOf(VoiceId.V02))

    @Test fun appendKeepsOrderAndExportWaitsForWrites() = runBlocking {
        val log = TransitionLogFile(temp.root.resolve("logs/t.jsonl"), temp.root.resolve("out"))
        (1L..200L).forEach { log.append(entry(it)) }
        val exported = log.export()
        assertEquals((1L..200L).toList(), TransitionLogFile.read(exported).map { it.atMs })
    }

    @Test fun exportIsACopy() = runBlocking {
        val file = temp.root.resolve("t.jsonl")
        val log = TransitionLogFile(file, temp.root.resolve("out"))
        log.append(entry(1))
        val exported = log.export()
        log.append(entry(2))
        // append 처리까지 기다린 뒤에도 앞서 내보낸 사본은 그대로여야 한다. 대조 리포트가 바뀌면 안 된다
        val second = temp.root.resolve("copy.jsonl")
        exported.copyTo(second)
        log.export()
        assertEquals(listOf(1L), TransitionLogFile.read(second).map { it.atMs })
        assertEquals(listOf(1L, 2L), TransitionLogFile.read(file).map { it.atMs })
    }

    @Test fun rotationKeepsPreviousFile() = runBlocking {
        val file = temp.root.resolve("t.jsonl")
        val log = TransitionLogFile(file, temp.root.resolve("out"), maxBytes = 300)
        (1L..50L).forEach { log.append(entry(it)) }
        val exported = log.export()
        assertTrue(temp.root.resolve("t.jsonl.1").exists())
        val all = TransitionLogFile.read(exported).map { it.atMs }
        assertEquals(50L, all.last())
        assertEquals(all.sorted(), all)
        assertFalse(temp.root.list()!!.any { it.endsWith(".2") })
    }

    @Test fun truncatedLastLineIsSkipped() {
        val file = temp.root.resolve("t.jsonl")
        file.writeText("""{"atMs":1,"from":"NORMAL","to":"STOP","reasons":["FATIGUE"],"voices":["V04"]}""" + "\n" + """{"atMs":2,"from":"NOR""")
        assertEquals(listOf(1L), TransitionLogFile.read(file).map { it.atMs })
    }

    @Test fun missingFileReadsEmpty() {
        assertEquals(emptyList(), TransitionLogFile.read(temp.root.resolve("none.jsonl")))
    }

    /** 14장: 사유, 상태, 음성 ID 말고는 없다 */
    @Test fun nothingSensitiveInFormat() = runBlocking {
        val file = temp.root.resolve("t.jsonl")
        val log = TransitionLogFile(file, temp.root.resolve("out"))
        log.append(entry(1))
        log.export()
        val line = file.readText()
        listOf("mmol", "glucoseValue", "lat", "lon", "sleep").forEach { assertFalse(line.contains(it), it) }
    }

    @Test fun unwritablePathDoesNotThrow() {
        val blocker = temp.newFile("blocker")
        val log = TransitionLogFile(blocker.resolve("child/t.jsonl"), temp.root)
        log.append(entry(1))
    }

    private fun glucoseEntry(at: Long) = TransitionEntry(at, DriverState.CAUTION, DriverState.STOP, setOf(Reason.GLUCOSE), listOf(VoiceId.V09))
    private fun lostEntry(at: Long) = TransitionEntry(at, DriverState.NORMAL, DriverState.NORMAL, emptySet(), listOf(VoiceId.V19))
    private fun releasedEntry(at: Long) =
        TransitionEntry(at, DriverState.CAUTION, DriverState.NORMAL, emptySet(), emptyList(), LogEvent.GLUCOSE_HOLD_RELEASED)

    /** 수치가 없어도 "GLUCOSE, V09, 시각" 줄은 건강 관련 기록이다. 회전본과 내보낸 복사본까지 지운다 */
    @Test fun clearRemovesLiveRotatedAndExport() = runBlocking {
        val file = temp.root.resolve("logs/t.jsonl")
        val out = temp.root.resolve("out")
        val log = TransitionLogFile(file, out, maxBytes = 200)
        repeat(20) { log.append(glucoseEntry(it.toLong())) }
        val exported = log.export()
        assertTrue(exported.exists())
        assertTrue(temp.root.resolve("logs/t.jsonl.1").exists())
        log.clear()
        assertFalse(file.exists())
        assertFalse(temp.root.resolve("logs/t.jsonl.1").exists())
        assertFalse(exported.exists())
    }

    @Test fun unpairRemovesOnlyGlucoseEntries() = runBlocking {
        val file = temp.root.resolve("t.jsonl")
        val log = TransitionLogFile(file, temp.root.resolve("out"))
        log.append(entry(1))
        log.append(glucoseEntry(2))
        log.append(lostEntry(3))
        log.append(entry(4))
        log.append(releasedEntry(5))
        log.removeGlucoseEntries()
        assertEquals(listOf(1L, 4L), TransitionLogFile.read(file).map { it.atMs })
    }

    /** 보류 풀기 줄은 조작 표시를 남긴다(E34). 다른 줄에는 "event" 키가 붙지 않는다 */
    @Test fun releaseEventRoundTripsAndOthersOmitIt() = runBlocking {
        val file = temp.root.resolve("t.jsonl")
        val log = TransitionLogFile(file, temp.root.resolve("out"))
        log.append(entry(1))
        log.append(releasedEntry(2))
        log.removeGlucoseEntries()
        log.append(releasedEntry(3))
        log.prune(0)
        val lines = file.readLines()
        assertFalse("event" in lines.first(), lines.first())
        assertEquals(listOf(null, LogEvent.GLUCOSE_HOLD_RELEASED), TransitionLogFile.read(file).map { it.event })
    }

    /** C-54 */
    @Test fun pruneRemovesOldLines() = runBlocking {
        val file = temp.root.resolve("t.jsonl")
        val log = TransitionLogFile(file, temp.root.resolve("out"))
        (1L..5L).forEach { log.append(entry(it)) }
        log.prune(4)
        assertEquals(listOf(4L, 5L), TransitionLogFile.read(file).map { it.atMs })
        log.prune(100)
        assertFalse(file.exists())
    }
}
