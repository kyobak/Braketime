package com.conditiondrive.vehicle.platform

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.TripEvent
import com.conditiondrive.core.poi.Place
import com.conditiondrive.core.poi.PlaceKind
import com.conditiondrive.core.voice.VoicePriority
import com.conditiondrive.vehicle.port.AirMode
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.ListenResult
import com.conditiondrive.vehicle.port.SpeakResult
import com.conditiondrive.vehicle.storage.TripLogDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pleos 에뮬레이터에서 실제 SDK로 도는 어댑터 시험. 값은 docs/spike-results.md의 실측을 따른다.
 * SDK가 없는 기기에서는 건너뛴다(Assume). 그런 기기의 대체 경로는 JVM 시험이 본다.
 */
@RunWith(AndroidJUnit4::class)
class PlatformDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val pleosPresent: Boolean
        get() = runCatching { context.packageManager.getPackageInfo("ai.pleos.playground.service.vehicle", 0) }.isSuccess

    @After fun tearDown() = scope.cancel()

    private fun onMain(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }

    @Test fun carSignalsDeliverGearAndCapabilities() = onMain {
        assumeTrue(pleosPresent)
        val connection = CarConnection(context)
        val signals = CarVehicleSignals(connection, scope)
        signals.start()
        withTimeout(5000) { signals.capabilities.first { it.gear } }
        withTimeout(5000) { signals.gear.first { it != Gear.UNKNOWN } }
        // 시동 속성이 앱에 보이지 않는 이미지(실측)에서도 ON으로 돈다
        assertEquals(com.conditiondrive.core.engine.Ignition.ON, signals.ignition.value)
        signals.stop()
        assertEquals(Gear.UNKNOWN, signals.gear.value)
    }

    @Test fun gateReachesParkedOnlyWhenParked() = onMain {
        assumeTrue(pleosPresent)
        val connection = CarConnection(context)
        val signals = CarVehicleSignals(connection, scope)
        val gate = CarDrivingGate(connection, signals, scope, holdMs = 200)
        signals.start()
        gate.start()
        val gear = withTimeout(5000) { signals.gear.first { it != Gear.UNKNOWN } }
        if (gear == Gear.PARK) {
            withTimeout(5000) { gate.uiMode.first { it == com.conditiondrive.vehicle.port.UiMode.PARKED } }
        }
        gate.stop()
        signals.stop()
    }

    @Test fun pleosTtsSpeaksKoreanAndStops() = onMain {
        assumeTrue(pleosPresent)
        val speaker = ChainSpeaker(listOf(PleosTtsEngine(context)), AndroidAudioFocus(context), scope)
        assertEquals(SpeakResult.DONE, speaker.speak("쉬어 갈 시간이에요.", VoicePriority.NOTICE))
        assertEquals(SpeechEngineKind.PLEOS, speaker.activeEngine.value)
        val long = scope.async { speaker.speak("연속 운전 시간이 길어졌어요. 다음 휴게소에서 잠시 쉬어 가시는 게 어떨까요? 안내해 드릴게요.", VoicePriority.NOTICE) }
        delay(500)
        speaker.stopNow()
        assertEquals(SpeakResult.INTERRUPTED, long.await())
        speaker.release()
    }

    /** 무음 환경이라 듣지 못하는 경우를 본다. 인식 정확도는 스파이크 표의 PCM 시험을 본다 */
    @Test fun pleosSttEndsByItselfWhenSilent() = onMain {
        assumeTrue(pleosPresent)
        val listener = ChainListener(listOf(PleosSttEngine(context, scope)), AndroidAudioFocus(context), scope)
        val result = withTimeout(20_000) { listener.listen(2000) }
        assertTrue(result == ListenResult.NoSpeech || result is ListenResult.Heard, "$result")
        listener.release()
    }

    @Test fun odometerReadsKilometers() = runBlocking {
        assumeTrue(pleosPresent)
        val km = PleosOdometer(VehicleSdk(context)).totalKm()
        assertNotNull(km)
        assertTrue(km >= 0.0)
    }

    @Test fun ventilationRoundTrip() = runBlocking {
        assumeTrue(pleosPresent)
        val port = PleosVentilation(VehicleSdk(context))
        assertTrue(port.supported())
        val before = assertNotNull(port.current())
        val other = if (before == AirMode.FRESH) AirMode.RECIRCULATE else AirMode.FRESH
        // Virtual Console 하트비트가 없으면 차량 쪽이 반영하지 않는다(실측). 반환값은 실제 값과 맞아야 한다
        val applied = port.set(other)
        assertEquals(applied, port.current() == other)
        if (applied) assertTrue(port.set(before))
    }

    @Test fun occupancyPublishesValue() = onMain {
        assumeTrue(pleosPresent)
        val port = PleosOccupancy(VehicleSdk(context), scope)
        port.start()
        val value = withTimeout(10_000) { port.passengerPresent.first { it != null } }
        assertNotNull(value)
        assertEquals(true, port.supported.value)
        port.stop()
    }

    private val place = Place("cd-test-1", "시험 쉼터", 37.4979, 127.0276, PlaceKind.DROWSY_SHELTER, address = "서울")

    @Test fun naviConnectsAndMeasures() = onMain {
        assumeTrue(pleosPresent)
        val navi = PleosNavi(context, scope)
        navi.start()
        withTimeout(10_000) { navi.available.first { it } }
        withTimeout(10_000) { navi.route.first { it != null } }
        val estimate = withTimeout(10_000) { navi.measure(place) }
        // 5초 제한 안에 답이 없으면 null이 설계대로다. 답이 오면 값이 양수여야 한다
        estimate?.let { assertTrue(it.distanceM > 0 && it.etaSec > 0) }
        navi.stop()
    }

    @Test fun naviGuideReturnsDefiniteOutcome() = onMain {
        assumeTrue(pleosPresent)
        val navi = PleosNavi(context, scope)
        navi.start()
        withTimeout(10_000) { navi.available.first { it } }
        withTimeout(10_000) { navi.route.first { it != null } }
        val first = navi.guideTo(place)
        // 내비의 경로는 앱 밖에 남아 이전 실행의 경유지가 있을 수 있다. 어느 쪽이든 실패나 미지원이면 안 된다
        assertTrue(first != GuideOutcome.FAILED && first != GuideOutcome.UNSUPPORTED, "$first")
        navi.stop()
    }

    @Test fun locationChainFindsDomesticFix() = onMain {
        assumeTrue(pleosPresent)
        val navi = PleosNavi(context, scope)
        navi.start()
        withTimeout(10_000) { navi.available.first { it } }
        val now = { System.currentTimeMillis() }
        var chain: ChainLocation? = null
        val updated = { chain?.onSourceUpdated(); Unit }
        navi.onLocation = updated
        chain = ChainLocation(listOf(FusedLocationSource(context, now, updated), NaviLocationSource(navi, now)), scope, now, tickMs = 1000)
        chain.start()
        val fix = withTimeout(15_000) { chain.fix.first { it != null } }
        assertNotNull(fix)
        assertTrue(fix.latitude in 33.0..39.0 && fix.longitude in 124.0..132.0)
        chain.stop()
        navi.stop()
    }

    @Test fun tripLogUsesSqlite() = runBlocking {
        val name = "test_trip_${System.nanoTime()}.db"
        val now = System.currentTimeMillis()
        val db = TripLogDb.open(context, { now }, name)
        db.record(TripEvent.DRIVE_STARTED, now - 1000)
        db.record(TripEvent.STOP_ADVISED, now)
        val today = db.today().first()
        assertEquals(1, today.size)
        assertEquals(1, today.single().stopAdvised)
        db.deleteAll()
        assertEquals(emptyList(), db.today().first())
        context.deleteDatabase(name)
        Unit
    }
}
