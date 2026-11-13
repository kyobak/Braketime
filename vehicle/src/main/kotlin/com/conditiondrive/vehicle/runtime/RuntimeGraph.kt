package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.Engine
import com.conditiondrive.core.time.Clock
import com.conditiondrive.vehicle.port.ListenerPort
import com.conditiondrive.vehicle.port.LocationPort
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.OccupancyPort
import com.conditiondrive.vehicle.port.OdometerPort
import com.conditiondrive.vehicle.port.PlaceProvider
import com.conditiondrive.vehicle.port.SpeakerPort
import com.conditiondrive.vehicle.port.StateStore
import com.conditiondrive.vehicle.port.SummarySource
import com.conditiondrive.vehicle.port.TransitionLogPort
import com.conditiondrive.vehicle.port.TripLogPort
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import com.conditiondrive.vehicle.port.VentilationPort
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin

class RuntimePorts(
    val signals: VehicleSignalsPort,
    val summary: SummarySource,
    val navi: NaviPort,
    val store: StateStore,
    val log: TransitionLogPort,
    val trip: TripLogPort,
    val odometer: OdometerPort,
    val speaker: SpeakerPort,
    val listener: ListenerPort,
    val occupancy: OccupancyPort,
    val location: LocationPort,
    val ventilation: VentilationPort?,      // F10을 뺀 차종이면 null
    val providers: List<PlaceProvider>,
)

/**
 * 실사용과 시연이 같은 조립을 쓰게 하려는 것이다. 다른 점은 들어오는 포트와 시계뿐이다(규칙 6).
 * 서비스와 시연 컨트롤러가 각자 만들면 시간이 지나며 조립이 어긋난다.
 * parent는 단일 스레드 디스패처여야 한다(10.1 규칙 2). 포트 호출이 느리면 포트 안에서 스레드를 옮긴다.
 */
class RuntimeGraph(
    ports: RuntimePorts,
    clock: Clock,
    parent: CoroutineScope,
    engine: Engine = Engine(),
    hooks: RuntimeHooks = RuntimeHooks(),
) {
    private val job = SupervisorJob(parent.coroutineContext[Job])
    // 음성이나 경유지 처리 중의 예기치 않은 오류로 앱이 죽으면 측정이 끊긴다. 오류는 기록만 하고 계속 돈다
    private val scope = CoroutineScope(
        parent.coroutineContext + job + CoroutineExceptionHandler { _, e -> hooks.onError(e) },
    )

    val voice = VoiceQueue(ports.speaker, ports.listener, ports.occupancy, ports.signals, clock, scope)
    // 장소 검색과 음성이 같은 고속 판정을 쓴다. 판정 입력은 VoiceQueue가 차속과 기어로 채운다
    val guidance = StopGuidanceController(ports.providers, ports.location, ports.navi, ports.signals, clock, scope, highwayNow = voice.highway::likely)
    val ventilation = VentilationController(ports.ventilation, voice, clock, scope)
    val runtime = EngineRuntime(
        engine, clock, ports.signals, ports.summary, ports.navi, ports.store, ports.log, ports.trip,
        ports.odometer, voice, guidance, ventilation, scope, hooks,
    )

    fun start() = runtime.start()

    suspend fun stop() {
        runtime.stop()
        job.cancelAndJoin()
    }
}
