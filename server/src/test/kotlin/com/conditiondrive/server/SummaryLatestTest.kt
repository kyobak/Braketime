package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.LatestSummaryResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S8. 12.8 표. */
class SummaryLatestTest {
    private val fullValidUntil = ms("2026-11-20T08:26:30+09:00")
    private val fullSleepUntil = ms("2026-11-21T04:00:00+09:00")

    private suspend fun TestEnv.latest(vehicle: String) = get(ApiPaths.SUMMARY_LATEST, vehicle).also { it.assertOk() }

    @Test fun beforeValidUntilIsUnchanged() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_full.json")).assertOk()
        clock.set(fullValidUntil - 1)
        val json = latest(p.vehicle).json
        assertTrue(json.bool("paired"))
        assertEquals("Galaxy S25", json.str("phone_name"))
        assertEquals(ContractJson.Strict.parseToJsonElement(fixture("summary_full.json")), json.getValue("summary"))
    }

    /** 표 2행. 응답 전체가 골든 픽스처와 같아야 차량 파서 테스트와 어긋나지 않는다. */
    @Test fun afterValidUntilMatchesGoldenMaskedFixture() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_full.json")).assertOk()
        clock.set(ms("2026-11-20T00:13:05Z"))
        assertEquals(ContractJson.Strict.parseToJsonElement(fixture("latest_masked.json")), latest(p.vehicle).json)
    }

    @Test fun glucoseIsMaskedAtExactBoundary() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_full.json")).assertOk()
        clock.set(fullValidUntil)
        val summary = latest(p.vehicle).json.getValue("summary") as JsonObject
        listOf("glucose_mmol", "glucose_trend", "glucose_source", "glucose_measured_at").forEach { assertEquals(JsonNull, summary[it], it) }
        assertEquals("290", summary.getValue("sleep_minutes_last_night").toString())
    }

    /** 표 3행. 수면이 먼저 만료되는 경우. */
    @Test fun sleepExpiredGlucoseValid() = serverTest {
        val p = pair()
        val now = clock.nowMs()
        upload(p.phone, summary(now) { this["sleep_valid_until"] = kotlinx.serialization.json.JsonPrimitive(ApiTime.format(now + 100 * SEC)) }).assertOk()
        clock.advance(100 * SEC)
        val json = latest(p.vehicle).json
        val summary = json.getValue("summary") as JsonObject
        listOf("sleep_minutes_last_night", "sleep_short", "sleep_valid_until").forEach { assertEquals(JsonNull, summary[it], it) }
        assertEquals("5.4", summary.getValue("glucose_mmol").toString())
        assertFalse(json.isNull("received_at"))
    }

    /** 표 4행 */
    @Test fun bothExpiredReturnsNullSummary() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_full.json")).assertOk()
        clock.set(fullSleepUntil)
        val json = latest(p.vehicle).json
        assertTrue(json.bool("paired"))
        assertTrue(json.isNull("summary"))
        assertTrue(json.isNull("received_at"))
        assertFalse(json.isNull("server_time"))
    }

    @Test fun glucoseOnlyExpiresToNull() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_glucose_only.json")).assertOk()
        clock.set(fullValidUntil)
        assertTrue(latest(p.vehicle).json.isNull("summary"))
    }

    @Test fun allNullSnapshotIsReturnedUntilValidUntil() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_all_null.json")).assertOk()
        assertFalse(latest(p.vehicle).json.isNull("summary"))
        clock.set(ms("2026-11-20T08:27:00+09:00"))
        assertTrue(latest(p.vehicle).json.isNull("summary"))
    }

    @Test fun noSummaryYet() = serverTest {
        val p = pair()
        val json = latest(p.vehicle).json
        assertTrue(json.bool("paired"))
        assertTrue(json.isNull("summary"))
        assertTrue(json.isNull("received_at"))
    }

    /** 표 5행 */
    @Test fun unpairedVehicle() = serverTest {
        val json = latest(vehicleToken()).json
        assertFalse(json.bool("paired"))
        assertTrue(json.isNull("summary"))
        assertTrue(json.isNull("phone_name"))
        assertEquals(ApiTime.format(BASE_MS), json.str("server_time"))
    }

    /** 폰과 차량이 쓰는 Lenient 파서로 실제 응답을 읽을 수 있어야 한다. */
    @Test fun clientParserReadsRealResponse() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_full.json")).assertOk()
        val parsed = ContractJson.Lenient.decodeFromString(LatestSummaryResponse.serializer(), latest(p.vehicle).text)
        assertEquals(5.4, assertNotNull(parsed.summary).glucoseMmol)
        clock.set(fullSleepUntil)
        val empty = ContractJson.Lenient.decodeFromString(LatestSummaryResponse.serializer(), latest(p.vehicle).text)
        assertNull(empty.summary)
    }
}
