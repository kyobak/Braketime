package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.engine.PromptAnswer
import com.conditiondrive.core.engine.Say
import com.conditiondrive.core.time.Clock
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import com.conditiondrive.vehicle.port.AirMode
import com.conditiondrive.vehicle.port.VentilationPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/** F10(17장). 실패해도 F1~F5에 영향이 없어야 해서 묻기 전의 오류는 조용히 지원 안 함으로 처리한다. "응" 뒤의 실패만 V27F로 알린다 */
class VentilationController(
    private val port: VentilationPort?,
    private val voice: VoiceQueue,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val policy: Policy = Policy.DEFAULT,
) {
    private val _supported = MutableStateFlow<Boolean?>(null)
    /** S5 지원 현황 문장용. 아직 시도 전이면 null */
    val supported: StateFlow<Boolean?> = _supported

    private var restoreJob: Job? = null
    private var restoreTo: AirMode? = null

    /** OfferVentilation. 운행당 1회와 G=NONE은 엔진이 이미 확인했다 */
    fun offer() {
        val ventilation = port
        if (ventilation == null) {
            _supported.value = false
            return
        }
        scope.launch {
            val original = guarded {
                if (ventilation.supported()) ventilation.current() else null
            }
            _supported.value = original != null
            // 이미 바깥 공기면 바꿀 것이 없다. 묻는 것 자체가 군더더기다
            if (original == null || original == AirMode.FRESH) return@launch
            voice.askYesNo(VoiceId.V27) { answer ->
                if (answer == PromptAnswer.ACCEPT) scope.launch { switchToFresh(ventilation, original) }
            }
        }
    }

    private suspend fun switchToFresh(ventilation: VentilationPort, original: AirMode) {
        if (guarded { ventilation.set(AirMode.FRESH) } != true) {
            // "응"이라고 답했는데 아무 말이 없으면 바뀐 줄 안다. 없는 변경을 복원하지도 않는다
            voice.enqueue(Say(VoiceId.V27F, VoiceVars()), VoiceVars())
            return
        }
        restoreTo = original
        restoreJob = scope.launch {
            val manual = waitForManualChange(ventilation)
            val target = restoreTo
            restoreTo = null
            if (!manual && target != null) guarded { ventilation.set(target) }
        }
    }

    /** 복원 시간까지 기다린다. 운전자가 직접 바꾸면 그 뜻을 따르고 복원하지 않는다(F10-3) */
    private suspend fun waitForManualChange(ventilation: VentilationPort): Boolean = coroutineScope {
        val until = clock.nowMs() + policy.VENT_RESTORE_SEC * 1000
        val manual = async {
            ventilation.changes.filter { it != AirMode.FRESH }.firstOrNull() ?: awaitCancellation()
            true
        }
        val timer = async {
            // 벽시계가 아니라 주입된 시계로 잰다. 시연의 압축 시간에서도 5분이 5분이어야 한다
            while (clock.nowMs() < until) delay((until - clock.nowMs()).coerceIn(1, RESTORE_POLL_MS))
            false
        }
        val result = select { manual.onAwait { it }; timer.onAwait { it } }
        coroutineContext.cancelChildren()
        result
    }

    /** 시동이 꺼지면 5분을 기다릴 수 없다. 바꿔 놓은 채로 두지 않는다 */
    suspend fun stop() {
        restoreJob?.cancel()
        restoreJob = null
        val target = restoreTo ?: return
        restoreTo = null
        val ventilation = port ?: return
        guarded { ventilation.set(target) }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        _supported.value = false
        null
    }

    private companion object {
        const val RESTORE_POLL_MS = 1000L
    }
}
