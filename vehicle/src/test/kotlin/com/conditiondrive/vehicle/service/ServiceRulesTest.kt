package com.conditiondrive.vehicle.service

import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.Reason
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.vehicle.port.TransitionLogPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceRulesTest {
    private class Recording : TransitionLogPort {
        val entries = mutableListOf<TransitionEntry>()
        override fun append(entry: TransitionEntry) { entries += entry }
        override suspend fun export(): File = File("none")
    }

    private fun entry(from: DriverState, to: DriverState, vararg voices: VoiceId) =
        TransitionEntry(1, from, to, emptySet<Reason>(), voices.toList())

    @Test fun stopAdviceIncludesReannouncementAndKeepsLogging() {
        val delegate = Recording()
        var advice = 0
        var left = 0
        val log = StopAdviceLog(delegate, { advice++ }, { left++ })
        log.append(entry(DriverState.CAUTION, DriverState.STOP, VoiceId.V04))
        log.append(entry(DriverState.STOP, DriverState.STOP, VoiceId.V04))
        log.append(entry(DriverState.STOP, DriverState.STOP, VoiceId.V09))
        assertEquals(3, advice)
        assertEquals(0, left)
        assertEquals(3, delegate.entries.size)
    }

    /** 8.1: 정차 권고에 한해서다. 예고와 체크인은 음성만 낸다 */
    @Test fun otherVoicesDoNotRaiseHeadsUp() {
        var advice = 0
        val log = StopAdviceLog(Recording(), { advice++ }, {})
        log.append(entry(DriverState.NORMAL, DriverState.NORMAL, VoiceId.V01))
        log.append(entry(DriverState.NORMAL, DriverState.CAUTION, VoiceId.V02))
        log.append(entry(DriverState.CAUTION, DriverState.CAUTION, VoiceId.V03))
        assertEquals(0, advice)
    }

    @Test fun leavingStopClearsAdvice() {
        var left = 0
        val log = StopAdviceLog(Recording(), {}, { left++ })
        log.append(entry(DriverState.STOP, DriverState.RECOVER))
        assertEquals(1, left)
    }

    @Test fun restartsOnlyOnOffToOnWhenNotRunning() = runTest {
        val ignition = MutableStateFlow(Ignition.ON)
        var running = false
        var starts = 0
        var failures = 0
        val job = Job()
        IgnitionRestarter(ignition, CoroutineScope(coroutineContext + job), { running }, { starts++; true }, { failures++ }).watch()
        runCurrent()
        assertEquals(0, starts)
        ignition.value = Ignition.OFF; runCurrent()
        ignition.value = Ignition.ON; runCurrent()
        assertEquals(1, starts)
        running = true
        ignition.value = Ignition.OFF; runCurrent()
        ignition.value = Ignition.ON; runCurrent()
        assertEquals(1, starts)
        assertEquals(0, failures)
        job.cancel()
    }

    @Test fun rejectedBackgroundStartReportsFailure() = runTest {
        val ignition = MutableStateFlow(Ignition.OFF)
        var failures = 0
        val job = Job()
        IgnitionRestarter(ignition, CoroutineScope(coroutineContext + job), { false }, { false }, { failures++ }).watch()
        runCurrent()
        ignition.value = Ignition.ON; runCurrent()
        assertEquals(1, failures)
        job.cancel()
    }

    @Test fun gapCountsOnlySameDay() {
        val noon = 1_795_000_000_000L
        assertTrue(DriveServiceState.sameDay(noon, noon + 1000))
        assertFalse(DriveServiceState.sameDay(noon, noon + 2L * 24 * 3600 * 1000))
    }

    /** 10.3을 소스에서 지킨다. location, microphone 타입이면 BOOT_COMPLETED에서 서비스를 만들 수 없다 */
    @Test fun manifestRules() {
        val xml = listOf("src/main/AndroidManifest.xml", "vehicle/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        assertTrue("android:foregroundServiceType=\"specialUse\"" in xml)
        assertTrue("driver_rest_reminder" in xml)
        assertTrue("android.intent.action.BOOT_COMPLETED" in xml)
        assertTrue("android.intent.action.MY_PACKAGE_REPLACED" in xml)
        assertTrue("android:required=\"false\"" in xml)
        assertTrue("android:allowBackup=\"false\"" in xml)
        assertFalse("pleos.car.permission.CAR_INFO" in xml)   // VIN, CRN뿐이라 쓰지 않는다
        assertFalse("FOREGROUND_SERVICE_LOCATION" in xml)
        assertFalse("FOREGROUND_SERVICE_MICROPHONE" in xml)
        listOf("TTS_SERVICE", "STT_SERVICE", "FUSED_LOCATION", "NAVI_ROUTE", "NAVI_ROUTE_SEARCH", "NAVI_CUSTOM_MAP", "CAR_DRIVE", "CAR_CLIMATE", "CONTROL_CAR_CLIMATE", "CAR_SEATS")
            .forEach { assertTrue("pleos.car.permission.$it" in xml, it) }
    }

    /** 시동을 끈 직후 켜면 내려가는 중이라 재시작을 건너뛴다. 다 내려간 뒤 다시 봐야 이번 운행을 놓치지 않는다 */
    @Test fun restartsAfterStopWhenIgnitionCameBack() = runTest {
        val ignition = MutableStateFlow(Ignition.ON)
        var running = true
        var starts = 0
        val job = Job()
        val restarter = IgnitionRestarter(ignition, CoroutineScope(coroutineContext + job), { running }, { starts++; true }, {})
        restarter.watch()
        runCurrent()
        ignition.value = Ignition.OFF; runCurrent()
        ignition.value = Ignition.ON; runCurrent()
        assertEquals(0, starts, "아직 내려가는 중이다")
        running = false
        restarter.onServiceStopped()
        assertEquals(1, starts)
        job.cancel()
    }

    @Test fun staysDownWhenIgnitionIsOff() = runTest {
        val ignition = MutableStateFlow(Ignition.OFF)
        var starts = 0
        val job = Job()
        val restarter = IgnitionRestarter(ignition, CoroutineScope(coroutineContext + job), { false }, { starts++; true }, {})
        restarter.onServiceStopped()
        assertEquals(0, starts)
        job.cancel()
    }

    /** V17은 정차 권고 헤드업과 따로다. 혈당 단어 없는 문구를 쓴다 */
    @Test fun holdAdviceRaisedAndCleared() {
        var hold = 0
        var left = 0
        val log = StopAdviceLog(Recording(), {}, {}, { hold++ }, { left++ })
        log.append(entry(DriverState.RECOVER, DriverState.RECOVER, VoiceId.V17))
        assertEquals(1, hold)
        assertEquals(0, left)
        log.append(entry(DriverState.RECOVER, DriverState.RECOVER, VoiceId.V15))
        assertEquals(0, left)
        log.append(entry(DriverState.RECOVER, DriverState.NORMAL, VoiceId.V16))
        assertEquals(1, left)
    }

    @Test fun unguidedRepeatRaisesHeadsUpToo() {
        var advice = 0
        val log = StopAdviceLog(Recording(), { advice++ }, {})
        log.append(entry(DriverState.STOP, DriverState.STOP, VoiceId.V10U))
        assertEquals(1, advice)
    }

    /** allowBackup=false만으로는 Android 12부터 기기 간 이전이 막히지 않는다 */
    @Test fun dataExtractionRulesBlockTransfer() {
        val root = listOf(File("."), File("vehicle")).first { File(it, "src/main/AndroidManifest.xml").exists() }
        val manifest = File(root, "src/main/AndroidManifest.xml").readText()
        assertTrue("android:dataExtractionRules=\"@xml/data_extraction_rules\"" in manifest)
        val rules = File(root, "src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue("<device-transfer>" in rules && "<cloud-backup>" in rules)
        for (domain in listOf("root", "file", "database", "sharedpref")) {
            assertEquals(2, Regex("""<exclude domain="$domain"/>""").findAll(rules).count(), domain)
        }
    }
}
