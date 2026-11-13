package com.conditiondrive.vehicle.platform

import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalMappingTest {
    @Test fun gearValuesMeasuredOnEmulator() {
        assertEquals(Gear.PARK, SignalMapping.gear(4))
        assertEquals(Gear.REVERSE, SignalMapping.gear(2))
        assertEquals(Gear.NEUTRAL, SignalMapping.gear(1))
        assertEquals(Gear.DRIVE, SignalMapping.gear(8))
    }

    @Test fun manualGearsAreDrive() {
        listOf(16, 32, 64, 128, 256).forEach { assertEquals(Gear.DRIVE, SignalMapping.gear(it)) }
    }

    @Test fun unknownGearStaysUnknown() {
        assertEquals(Gear.UNKNOWN, SignalMapping.gear(0))
        assertEquals(Gear.UNKNOWN, SignalMapping.gear(null))
        assertEquals(Gear.UNKNOWN, SignalMapping.gear(3))
    }

    @Test fun ignitionMapping() {
        listOf(1, 2, 3).forEach { assertEquals(Ignition.OFF, SignalMapping.ignition(it)) }
        listOf(4, 5).forEach { assertEquals(Ignition.ON, SignalMapping.ignition(it)) }
        assertEquals(Ignition.UNKNOWN, SignalMapping.ignition(0))
        assertEquals(Ignition.UNKNOWN, SignalMapping.ignition(null))
    }

    @Test fun speedIsKmhAndAbsolute() {
        assertEquals(80.0, SignalMapping.speedKmh(22.222223f), 0.001)
        assertEquals(36.0, SignalMapping.speedKmh(-10f), 0.001)
    }

    @Test fun throttleLimitsToOncePerSecond() {
        val t = SpeedThrottle()
        assertTrue(t.accept(80.0, 0))
        assertFalse(t.accept(80.0, 100))
        assertFalse(t.accept(81.0, 999))
        assertTrue(t.accept(81.0, 1000))
    }

    @Test fun throttlePassesStopBoundaryImmediately() {
        val t = SpeedThrottle()
        assertTrue(t.accept(30.0, 0))
        assertTrue(t.accept(0.0, 50))
        assertTrue(t.accept(30.0, 100))
    }

    @Test fun throttleResetAcceptsNextValue() {
        val t = SpeedThrottle()
        assertTrue(t.accept(10.0, 0))
        t.reset()
        assertTrue(t.accept(10.0, 1))
    }

    @Test fun speechErrorClassification() {
        listOf("NOT_SUPPORTED_API", "ON_DEVICE_ENGINE_ERROR", "PERMISSION_DENIED", "UNAVAILABLE_LANGUAGE", "SOMETHING_NEW")
            .forEach { assertEquals(SpeechFailure.FATAL, classifySpeechError(it), it) }
        listOf("NETWORK_ERROR", "TIMEOUT").forEach { assertEquals(SpeechFailure.RETRY_ONCE, classifySpeechError(it), it) }
    }
}
