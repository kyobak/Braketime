package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.server.routes.ApiCodes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S15, 12.11 */
class TripApiTest {
    private val unprocessable = HttpStatusCode.UnprocessableEntity

    private suspend fun TestEnv.expectInvalid(vehicle: String, code: String, change: MutableMap<String, JsonElement>.() -> Unit): Res {
        clock.advance(4 * SEC)
        val res = post(ApiPaths.TRIPS, trip(tripId = UUID.randomUUID().toString(), change = change), vehicle)
        res.assertError(unprocessable, code)
        return res
    }

    private fun segments(vararg items: String): JsonElement = ContractJson.Strict.parseToJsonElement("[${items.joinToString(",")}]")

    private fun segment(start: Long, end: Long?, type: String = "drive"): String =
        """{"type":"$type","start":"${ApiTime.format(start)}","end":${end?.let { "\"${ApiTime.format(it)}\"" } ?: "null"}}"""

    @Test fun goldenFixture() = serverTest(now = ms("2026-11-20T10:32:00+09:00")) {
        val vehicle = vehicleToken()
        val res = post(ApiPaths.TRIPS, fixture("trip.json"), vehicle)
        res.assertOk(HttpStatusCode.Created)
        assertTrue(res.json.bool("applied"))
        assertEquals("6f1e6a9c-52f7-4f3e-9a54-0f0c2c9c1a11", res.json.str("trip_id"))
        assertEquals(3, res.json.getValue("rev").toString().toInt())
    }

    /** S15 rev 재전송 */
    @Test fun revRules() = serverTest {
        val vehicle = vehicleToken()
        val start = clock.nowMs() - HOUR
        post(ApiPaths.TRIPS, trip(rev = 2, start = start), vehicle).assertOk(HttpStatusCode.Created)
        val same = post(ApiPaths.TRIPS, trip(rev = 2, start = start), vehicle)
        same.assertOk()
        assertFalse(same.json.bool("applied"))
        val older = post(ApiPaths.TRIPS, trip(rev = 1, start = start), vehicle)
        older.assertOk()
        assertFalse(older.json.bool("applied"))
        assertEquals(2, older.json.getValue("rev").toString().toInt())
        val newer = post(ApiPaths.TRIPS, trip(rev = 3, start = start), vehicle)
        newer.assertOk()
        assertTrue(newer.json.bool("applied"))
        assertEquals(1, rows("trips"))
    }

    /** S15: 정차 사유를 올리면 건강 상태가 추론된다 */
    @Test fun healthAndReasonFieldsAreRejected() = serverTest {
        val vehicle = vehicleToken()
        listOf("reason", "glucose", "checkin", "glucose_mmol", "sleep_minutes").forEach { key ->
            val res = expectInvalid(vehicle, "unknown_field") { this[key] = JsonPrimitive("low") }
            assertTrue(key to "unknown_field" in res.fields)
            assertFalse("low" in res.text)
        }
        listOf("lat", "lng", "location", "samples").forEach { key ->
            expectInvalid(vehicle, "raw_health_data_rejected") { this[key] = JsonPrimitive(37.5) }
        }
        assertEquals(0, rows("trips"))
    }

    @Test fun nestedSegmentFieldsAreRejected() = serverTest {
        val vehicle = vehicleToken()
        val t = clock.nowMs() - HOUR
        val withReason = """{"type":"rest","start":"${ApiTime.format(t)}","end":null,"reason":"glucose"}"""
        val res = expectInvalid(vehicle, "unknown_field") { this["segments"] = segments(withReason) }
        assertTrue("segments.reason" to "unknown_field" in res.fields)
        val withLat = """{"type":"rest","start":"${ApiTime.format(t)}","end":null,"lat":37.5}"""
        expectInvalid(vehicle, "raw_health_data_rejected") { this["segments"] = segments(withLat) }
        expectInvalid(vehicle, "validation_failed") { this["segments"] = segments("1") }
    }

    @Test fun fieldValidation() = serverTest {
        val vehicle = vehicleToken()
        expectInvalid(vehicle, "validation_failed") { this["trip_id"] = JsonPrimitive("not-a-uuid") }
        expectInvalid(vehicle, "validation_failed") { this["rev"] = JsonPrimitive(0) }
        expectInvalid(vehicle, "validation_failed") { this["rev"] = JsonPrimitive("1") }
        expectInvalid(vehicle, "validation_failed") { this["started_at"] = JsonPrimitive("yesterday") }
        expectInvalid(vehicle, "validation_failed") { this["ended_at"] = JsonPrimitive("soon") }
        expectInvalid(vehicle, "validation_failed") { this["ended_at"] = JsonPrimitive(ApiTime.format(clock.nowMs() - 2 * HOUR)) }
        expectInvalid(vehicle, "validation_failed") { this["drive_sec"] = JsonPrimitive(-1) }
        expectInvalid(vehicle, "validation_failed") { this["rest_sec"] = JsonPrimitive(-1) }
        expectInvalid(vehicle, "validation_failed") { this["stop_advised_count"] = JsonPrimitive(1001) }
        expectInvalid(vehicle, "validation_failed") { this["declined_count"] = JsonPrimitive(-1) }
        expectInvalid(vehicle, "validation_failed") { this["rest_completed_count"] = JsonPrimitive(1001) }
        expectInvalid(vehicle, "validation_failed") { remove("segments") }
        expectInvalid(vehicle, "validation_failed") { this["segments"] = JsonPrimitive("x") }
        val t = clock.nowMs() - HOUR
        expectInvalid(vehicle, "validation_failed") { this["segments"] = segments(segment(t, t + MIN, type = "stop")) }
    }

    @Test fun countsBoundaryAccepted() = serverTest {
        val vehicle = vehicleToken()
        val res = post(ApiPaths.TRIPS, trip { this["stop_advised_count"] = JsonPrimitive(1000); this["declined_count"] = JsonPrimitive(0) }, vehicle)
        res.assertOk(HttpStatusCode.Created)
    }

    @Test fun segmentsOrderAndOverlap() = serverTest {
        val vehicle = vehicleToken()
        val t = clock.nowMs() - HOUR
        expectInvalid(vehicle, "validation_failed") { this["segments"] = segments(segment(t + MIN, t)) }
        expectInvalid(vehicle, "validation_failed") { this["segments"] = segments(segment(t, t + 10 * MIN), segment(t + 5 * MIN, t + 20 * MIN)) }
        expectInvalid(vehicle, "validation_failed") { this["segments"] = segments(segment(t, null), segment(t + 10 * MIN, t + 20 * MIN)) }
        expectInvalid(vehicle, "validation_failed") { this["segments"] = segments("""{"type":"drive","start":"bad","end":null}""") }
        clock.advance(4 * SEC)
        // 맞닿은 구간과 마지막 진행 중 구간은 정상이다
        post(
            ApiPaths.TRIPS,
            trip { this["segments"] = segments(segment(t, t + 10 * MIN), segment(t + 10 * MIN, null, type = "rest")) },
            vehicle,
        ).assertOk(HttpStatusCode.Created)
    }

    @Test fun segmentCountLimit() = serverTest {
        val vehicle = vehicleToken()
        val t = clock.nowMs() - HOUR
        // 32KB 안에 들어가도록 짧은 구간으로 만든다
        val make = { n: Int -> JsonArray(List(n) { ContractJson.Strict.parseToJsonElement(segment(t + it * SEC, t + it * SEC)) }) }
        expectInvalid(vehicle, "validation_failed") { this["segments"] = make(201) }
        post(ApiPaths.TRIPS, trip { this["segments"] = make(200) }, vehicle).assertOk(HttpStatusCode.Created)
    }

    @Test fun elapsedBudget() = serverTest {
        val vehicle = vehicleToken()
        val start = clock.nowMs() - HOUR // 3600초 경과
        val body = { drive: Long, rest: Long ->
            trip(tripId = UUID.randomUUID().toString(), start = start) {
                this["drive_sec"] = JsonPrimitive(drive)
                this["rest_sec"] = JsonPrimitive(rest)
                this["segments"] = JsonArray(emptyList())
            }
        }
        post(ApiPaths.TRIPS, body(3000, 660), vehicle).assertOk(HttpStatusCode.Created)
        post(ApiPaths.TRIPS, body(3000, 661), vehicle).assertError(unprocessable, "validation_failed")
        post(ApiPaths.TRIPS, body(Long.MAX_VALUE, Long.MAX_VALUE), vehicle).assertError(unprocessable, "validation_failed")
        // 끝난 기록은 지금이 아니라 ended_at까지만 경과로 친다
        post(
            ApiPaths.TRIPS,
            trip(tripId = UUID.randomUUID().toString(), start = start) {
                this["ended_at"] = JsonPrimitive(ApiTime.format(start + 10 * MIN))
                this["drive_sec"] = JsonPrimitive(661)
                this["rest_sec"] = JsonPrimitive(0)
                this["segments"] = JsonArray(emptyList())
            },
            vehicle,
        ).assertError(unprocessable, "validation_failed")
    }

    @Test fun bodyLimit32Kb() = serverTest {
        val vehicle = vehicleToken()
        val body = trip()
        post(ApiPaths.TRIPS, body + " ".repeat(Limits.BODY_TRIP - body.length), vehicle).assertOk(HttpStatusCode.Created)
        post(ApiPaths.TRIPS, body + " ".repeat(Limits.BODY_TRIP - body.length + 1), vehicle)
            .assertError(HttpStatusCode.PayloadTooLarge, ApiCodes.PAYLOAD_TOO_LARGE)
    }

    /** S15 삭제 후 재업로드 무효 */
    @Test fun deleteAllThenReuploadIsIgnored() = serverTest {
        val p = pair()
        val start = clock.nowMs() - HOUR
        post(ApiPaths.TRIPS, trip(rev = 1, start = start), p.vehicle).assertOk(HttpStatusCode.Created)
        clock.advance(MIN)
        val res = delete(ApiPaths.TRIPS, p.phone)
        res.assertOk()
        assertEquals(1, res.json.getValue("deleted_count").toString().toInt())
        assertEquals(ApiTime.format(clock.nowMs()), res.json.str("trips_deleted_before"))
        assertEquals(0, rows("trips"))

        // 큐에 남아 있던 기록이 뒤늦게 올라온 경우
        val late = post(ApiPaths.TRIPS, trip(rev = 2, start = start), p.vehicle)
        late.assertOk()
        assertFalse(late.json.bool("applied"))
        assertEquals(0, rows("trips"))

        // 삭제 뒤 시작한 주행은 받는다
        clock.advance(MIN)
        post(
            ApiPaths.TRIPS,
            trip(tripId = UUID.randomUUID().toString(), start = clock.nowMs()) {
                this["drive_sec"] = JsonPrimitive(0); this["rest_sec"] = JsonPrimitive(0); this["segments"] = JsonArray(emptyList())
            },
            p.vehicle,
        ).assertOk(HttpStatusCode.Created)
    }

    @Test fun deleteBeforeKeepsNewerAndIsIdempotent() = serverTest {
        val vehicle = vehicleToken()
        val now = clock.nowMs()
        val old = now - 2 * HOUR
        val recent = now - 1 * HOUR
        post(ApiPaths.TRIPS, trip(tripId = UUID.randomUUID().toString(), start = old), vehicle).assertOk(HttpStatusCode.Created)
        post(ApiPaths.TRIPS, trip(tripId = UUID.randomUUID().toString(), start = recent), vehicle).assertOk(HttpStatusCode.Created)
        val cut = java.net.URLEncoder.encode(ApiTime.format(recent), "UTF-8")
        repeat(2) { i ->
            val res = delete("${ApiPaths.TRIPS}?before=$cut", vehicle)
            res.assertOk()
            assertEquals(if (i == 0) 1 else 0, res.json.getValue("deleted_count").toString().toInt())
        }
        assertEquals(1, rows("trips"))
        delete("${ApiPaths.TRIPS}?before=not-a-time", vehicle).assertError(unprocessable, "validation_failed")
    }

    @Test fun deleteBeforeDoesNotMoveCutoffBackward() = serverTest {
        val vehicle = vehicleToken()
        delete(ApiPaths.TRIPS, vehicle).assertOk()
        val cut = java.net.URLEncoder.encode(ApiTime.format(clock.nowMs() - DAY), "UTF-8")
        assertEquals(ApiTime.format(clock.nowMs()), delete("${ApiPaths.TRIPS}?before=$cut", vehicle).json.str("trips_deleted_before"))
    }

    @Test fun futureBeforeIsClampedToNow() = serverTest {
        val vehicle = vehicleToken()
        val cut = java.net.URLEncoder.encode(ApiTime.format(clock.nowMs() + DAY), "UTF-8")
        assertEquals(ApiTime.format(clock.nowMs()), delete("${ApiPaths.TRIPS}?before=$cut", vehicle).json.str("trips_deleted_before"))
    }

    @Test fun endedAtNullIsStored() = serverTest {
        val vehicle = vehicleToken()
        post(ApiPaths.TRIPS, trip { this["ended_at"] = JsonNull }, vehicle).assertOk(HttpStatusCode.Created)
    }
}
