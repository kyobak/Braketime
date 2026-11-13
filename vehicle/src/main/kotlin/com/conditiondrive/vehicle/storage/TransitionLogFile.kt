package com.conditiondrive.vehicle.storage

import com.conditiondrive.core.engine.LogEvent
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.vehicle.port.TransitionLogPort
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * R2 대조 리포트의 원본. 줄마다 JSON 하나다. 항목에는 상태, 사유, 음성 ID만 있고 혈당 수치와 좌표는 없다(14장).
 * append는 reduce 코루틴에서 불리므로 디스크를 기다리지 않는다. 쓰기 실패는 측정보다 덜 중요해 버린다.
 */
class TransitionLogFile(
    private val file: File,
    private val exportDir: File = file.parentFile ?: File("."),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : TransitionLogPort {
    private val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "cd-transition-log").apply { isDaemon = true } }

    override fun append(entry: TransitionEntry) {
        io.execute {
            try {
                file.parentFile?.mkdirs()
                rotateIfNeeded()
                file.appendText(json.encodeToString(entry) + "\n")
            } catch (_: Exception) {
            }
        }
    }

    private fun rotateIfNeeded() {
        if (file.length() < maxBytes) return
        val old = previous()
        old.delete()
        file.renameTo(old)
    }

    private fun previous() = File(file.parentFile, file.name + ".1")

    private fun exported() = File(exportDir, file.nameWithoutExtension + "_export.jsonl")

    /** S5 "기록 지우기", "서버 데이터 삭제". 혈당 사유 줄은 수치가 없어도 건강 관련 기록이다(14장). 회전본과 내보낸 복사본까지 지운다 */
    suspend fun clear() = onIo { listOf(file, previous(), exported()).forEach { it.delete() } }

    /** S5 연결 해제. 동의를 거둔 혈당 사유 줄을 지운다. 피로 기록은 그대로 둔다. 보류 풀기 줄은 사유가 비어도 혈당 기록이다 */
    suspend fun removeGlucoseEntries() = rewrite { entry ->
        entry.reasons.none { it == Reason.GLUCOSE || it == Reason.GLUCOSE_UNCONFIRMED } && entry.voices.none { it in GLUCOSE_VOICES } &&
            entry.event != LogEvent.GLUCOSE_HOLD_RELEASED
    }

    /** C-54. 서비스 시작 때 보존 기간이 지난 줄을 지운다 */
    suspend fun prune(beforeMs: Long) = rewrite { it.atMs >= beforeMs }

    private suspend fun rewrite(keep: (TransitionEntry) -> Boolean) = onIo {
        exported().delete()
        listOf(previous(), file).filter { it.exists() }.forEach { target ->
            val kept = read(target).filter(keep)
            if (kept.isEmpty()) target.delete() else target.writeText(kept.joinToString("") { json.encodeToString(it) + "\n" })
        }
    }

    private suspend fun onIo(block: () -> Unit) {
        val done = CompletableDeferred<Unit>()
        io.execute {
            try {
                block()
            } catch (_: Exception) {
            }
            done.complete(Unit)
        }
        done.await()
    }

    /** 앞선 append가 모두 끝난 뒤의 내용을 복사본으로 준다. 공유 중에 원본이 바뀌지 않게 한다 */
    override suspend fun export(): File {
        val done = CompletableDeferred<File>()
        io.execute {
            try {
                exportDir.mkdirs()
                val target = exported()
                target.writeText("")
                listOf(previous(), file).filter { it.exists() }.forEach { target.appendBytes(it.readBytes()) }
                done.complete(target)
            } catch (e: Exception) {
                done.completeExceptionally(e)
            }
        }
        return done.await()
    }

    companion object {
        const val LIVE_FILE = "transitions.jsonl"
        const val DEMO_FILE = "demo_transitions.jsonl"
        private const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024
        private val GLUCOSE_VOICES = setOf(
            VoiceId.V08, VoiceId.V08F, VoiceId.V08N, VoiceId.V08FN, VoiceId.V08O, VoiceId.V08FO,
            VoiceId.V09, VoiceId.V09N, VoiceId.V09H, VoiceId.V10, VoiceId.V10N, VoiceId.V10U, VoiceId.V10H,
            VoiceId.V13, VoiceId.V14, VoiceId.V15,
            VoiceId.V16, VoiceId.V16C, VoiceId.V16D, VoiceId.V17, VoiceId.V19, VoiceId.V19R, VoiceId.V24, VoiceId.V25,
        )
        private val json = Json { encodeDefaults = true }

        /** 대조 도구용. 저장 도중 끊긴 마지막 줄은 건너뛴다 */
        fun read(file: File): List<TransitionEntry> =
            if (!file.exists()) emptyList() else file.readLines().mapNotNull { line ->
                try {
                    json.decodeFromString<TransitionEntry>(line)
                } catch (_: Exception) {
                    null
                }
            }
    }
}
