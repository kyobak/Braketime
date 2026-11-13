package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.ConditionInput
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.engine.TripEvent
import com.conditiondrive.core.poi.GeoFix
import com.conditiondrive.core.poi.Place
import com.conditiondrive.core.poi.PlaceKind
import com.conditiondrive.core.time.Clock
import com.conditiondrive.core.voice.VoicePriority
import com.conditiondrive.vehicle.port.AirMode
import com.conditiondrive.vehicle.port.EngineStateRecord
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.ListenerPort
import com.conditiondrive.vehicle.port.LocationPort
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.OccupancyPort
import com.conditiondrive.vehicle.port.OdometerPort
import com.conditiondrive.vehicle.port.PlaceProvider
import com.conditiondrive.vehicle.port.PlaceQuery
import com.conditiondrive.vehicle.port.RouteSnapshot
import com.conditiondrive.vehicle.port.SpeakResult
import com.conditiondrive.vehicle.port.SpeakerPort
import com.conditiondrive.vehicle.port.StateStore
import com.conditiondrive.vehicle.port.SummarySnapshot
import com.conditiondrive.vehicle.port.SummarySource
import com.conditiondrive.vehicle.port.TransitionLogPort
import com.conditiondrive.vehicle.port.TripEstimate
import com.conditiondrive.vehicle.port.TripLogPort
import com.conditiondrive.vehicle.port.TripRecord
import com.conditiondrive.vehicle.port.VehicleCapabilities
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import com.conditiondrive.vehicle.port.VentilationPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import java.io.File
import java.time.OffsetDateTime

const val BASE_MS = 1_795_000_000_000L
val SCENARIO_NOON: Long = OffsetDateTime.parse("2026-11-20T12:00:00+09:00").toInstant().toEpochMilli()

/** runTest의 가상 시간을 그대로 엔진 시계로 쓴다. 실제 대기 없이 2시간을 돌린다 */
fun TestScope.virtualClock(startMs: Long = SCENARIO_NOON): Clock {
    val zero = testScheduler.currentTime
    return Clock { startMs + testScheduler.currentTime - zero }
}

class FakeSignals(
    gear: Gear = Gear.DRIVE,
    speed: Double? = 80.0,
    ignition: Ignition = Ignition.ON,
) : VehicleSignalsPort {
    override val gear = MutableStateFlow(gear)
    override val speedKmh = MutableStateFlow(speed)
    override val ignition = MutableStateFlow(ignition)
    override val capabilities = MutableStateFlow(VehicleCapabilities(gear = true, speed = true, ignition = true))
    var started = 0
    override fun start() { started++ }
    override fun stop() = Unit
}

/** 재생 시각과 문구를 남긴다. 다음 결과를 줄로 지정할 수 있다 */
class FakeSpeaker(private val speakMs: Long = 2000) : SpeakerPort {
    data class Spoken(val text: String, val priority: VoicePriority, val atMs: Long, var endAtMs: Long? = null, var result: SpeakResult? = null)

    override val available = MutableStateFlow(true)
    val spoken = mutableListOf<Spoken>()
    val results = ArrayDeque<SpeakResult>()
    var stops = 0
    var now: () -> Long = { 0 }

    override suspend fun speak(text: String, priority: VoicePriority): SpeakResult {
        val entry = Spoken(text, priority, now())
        spoken += entry
        val result = results.removeFirstOrNull()
        if (result != null) {
            entry.result = result
            entry.endAtMs = now()
            return result
        }
        delay(speakMs)
        entry.endAtMs = now()
        entry.result = SpeakResult.DONE
        return SpeakResult.DONE
    }

    override fun stopNow() {
        stops++
    }

    val texts: List<String> get() = spoken.map { it.text }
}

class FakeListener : ListenerPort {
    override val available = MutableStateFlow(true)
    val answers = ArrayDeque<ListenResult>()
    val listenAtMs = mutableListOf<Long>()
    var listenMs = 100L
    var stops = 0
    var now: () -> Long = { 0 }

    var readyAfterMs = 0L
    override suspend fun listen(timeoutMs: Long, onReady: () -> Unit): ListenResult {
        listenAtMs += now()
        if (readyAfterMs > 0) delay(readyAfterMs)
        onReady()
        delay(listenMs)
        return answers.removeFirstOrNull() ?: ListenResult.NoSpeech
    }

    override fun stopNow() { stops++ }
}

class FakeOccupancy(present: Boolean? = false) : OccupancyPort {
    override val passengerPresent = MutableStateFlow(present)
    override fun start() = Unit
    override fun stop() = Unit
}

class FakeLocation(fix: GeoFix?) : LocationPort {
    override val fix = MutableStateFlow(fix)
    var started = 0
    override fun start() { started++ }
    override fun stop() = Unit
}

class FakeNavi(available: Boolean = true) : NaviPort {
    override val available = MutableStateFlow(available)
    override val route = MutableStateFlow<RouteSnapshot?>(null)
    override val waypointArrived = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val routeM = mutableMapOf<String, Int?>()
    var measures = 0
    val guided = mutableListOf<Place>()
    var outcome = GuideOutcome.WAYPOINT_ADDED
    var guideDelayMs = 0L

    override suspend fun measure(place: Place): TripEstimate? {
        measures++
        return routeM[place.id]?.let { TripEstimate(it, it / 20) }
    }

    override suspend fun guideTo(place: Place): GuideOutcome {
        guided += place
        if (guideDelayMs > 0) delay(guideDelayMs)
        return outcome
    }

    override fun openSearch(keyword: String) = true
}

class FakeProvider(private val places: List<Place>, override val id: String = "fake") : PlaceProvider {
    var queries = 0
    var failing = false
    override suspend fun nearby(query: PlaceQuery): List<Place> {
        queries++
        if (failing) error("공급자 실패")
        return places
    }
}

class FakeSummary(initial: SummarySnapshot = SummarySnapshot(null, unpaired = false, receivedAtMs = null, phoneName = null)) : SummarySource {
    override val snapshot = MutableStateFlow(initial)
    override val link = MutableStateFlow(LinkState.OFFLINE)
    override fun start() = Unit
    override fun stop() = Unit
    override suspend fun fetchNow(timeoutMs: Long) = Unit

    fun glucose(mmol: Double, atMs: Long, ttlMs: Long = 900_000) {
        snapshot.value = SummarySnapshot(
            ConditionInput(null, null, mmol, null, atMs, atMs + ttlMs, true),
            unpaired = false,
            receivedAtMs = atMs,
            phoneName = "폰",
        )
    }
}

class FakeStore(initial: EngineStateRecord? = null) : StateStore {
    var record = initial
    var saves = 0
    var clears = 0
    override suspend fun load() = record
    override suspend fun save(record: EngineStateRecord) {
        saves++
        this.record = record
    }
    override suspend fun clear() {
        clears++
        record = null
    }
}

class FakeLog : TransitionLogPort {
    val entries = mutableListOf<TransitionEntry>()
    override fun append(entry: TransitionEntry) { entries += entry }
    override suspend fun export(): File = File("unused")
}

class FakeTrip : TripLogPort {
    val events = mutableListOf<TripEvent>()
    var deletes = 0
    override suspend fun record(event: TripEvent, atMs: Long) { events += event }
    override fun today(): Flow<List<TripRecord>> = emptyFlow()
    override fun recent(days: Int): Flow<List<TripRecord>> = emptyFlow()
    override suspend fun deleteAll() { deletes++ }
}

class FakeOdometer(var km: Double? = null) : OdometerPort {
    override suspend fun totalKm() = km
}

class FakeVentilation(
    var isSupported: Boolean = true,
    var mode: AirMode? = AirMode.RECIRCULATE,
    var setWorks: Boolean = true,
) : VentilationPort {
    val sets = mutableListOf<AirMode>()
    override val changes = MutableSharedFlow<AirMode>(extraBufferCapacity = 4)
    override suspend fun supported() = isSupported
    override suspend fun current() = mode
    override suspend fun set(mode: AirMode): Boolean {
        sets += mode
        if (!setWorks) return false     // 실측: 콘솔 하트비트가 없으면 onComplete 뒤에도 값이 그대로다
        this.mode = mode
        return true
    }
}

/** P 표의 기준 좌표. 경부고속도로 임의 지점, 남쪽, 80km/h */
val TEST_FIX = GeoFix(36.5, 128.0, 180.0, BASE_MS)

/** 기준 좌표에서 남쪽으로 km만큼 떨어진 곳 */
fun placeSouth(id: String, km: Double, name: String = "쉼터$id") =
    Place(id, name, TEST_FIX.latitude - km / 111.195, TEST_FIX.longitude, PlaceKind.DROWSY_SHELTER)

fun placeNorth(id: String, km: Double) =
    Place(id, "북쪽$id", TEST_FIX.latitude + km / 111.195, TEST_FIX.longitude, PlaceKind.DROWSY_SHELTER)
