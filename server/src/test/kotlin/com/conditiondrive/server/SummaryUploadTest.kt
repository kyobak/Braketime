package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.SummaryLimits
import com.conditiondrive.server.routes.ApiCodes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S2~S7. 12.7 표 번호를 테스트 이름에 맞춘다. */
class SummaryUploadTest {
    private val unprocessable = HttpStatusCode.UnprocessableEntity
    private fun ts(ms: Long) = JsonPrimitive(ApiTime.format(ms))
    private fun MutableMap<String, JsonElement>.noGlucose() {
        this["glucose_mmol"] = JsonNull
        this["glucose_trend"] = JsonNull
        this["glucose_source"] = JsonNull
        this["glucose_measured_at"] = JsonNull
    }
    private fun MutableMap<String, JsonElement>.noSleep() {
        this["sleep_minutes_last_night"] = JsonNull
        this["sleep_short"] = JsonNull
        this["sleep_valid_until"] = JsonNull
    }

    /** 한도와 not_newer를 건드리지 않게 시계를 민다. */
    private suspend fun TestEnv.expect(phone: String, code: String, field: String, rule: String, change: MutableMap<String, JsonElement>.() -> Unit) {
        clock.advance(4 * SEC)
        val res = upload(phone, summary(change = change))
        res.assertError(unprocessable, code)
        assertTrue(field to rule in res.fields, "${res.fields}")
    }

    private suspend fun TestEnv.expectApplied(phone: String, change: MutableMap<String, JsonElement>.() -> Unit) {
        clock.advance(4 * SEC)
        val res = upload(phone, summary(change = change))
        res.assertOk()
        assertTrue(res.json.bool("applied"), res.text)
    }

    @Test fun goldenFixturesAreAccepted() = serverTest {
        val p = pair()
        listOf("summary_full.json", "summary_glucose_only.json", "summary_all_null.json").forEach {
            delete(ApiPaths.SUMMARY, p.phone).assertOk()
            val res = upload(p.phone, fixture(it))
            res.assertOk()
            assertTrue(res.json.bool("applied"), it)
        }
    }

    @Test fun uploadResponseShape() = serverTest {
        val p = pair()
        val json = upload(p.phone, fixture("summary_full.json")).json
        assertTrue(json.bool("applied"))
        assertEquals(ApiTime.format(BASE_MS), json.str("received_at"))
        assertEquals(ApiTime.format(BASE_MS), json.str("server_time"))
        assertEquals(ApiTime.format(BASE_MS), json.str("vehicle_last_seen_at"))
    }

    @Test fun row1BodySizeAndShape() = serverTest {
        val p = pair()
        val body = summary()
        upload(p.phone, body + " ".repeat(SummaryLimits.BODY_MAX_BYTES - body.length)).assertOk()
        upload(p.phone, body + " ".repeat(SummaryLimits.BODY_MAX_BYTES - body.length + 1))
            .assertError(HttpStatusCode.PayloadTooLarge, ApiCodes.PAYLOAD_TOO_LARGE)
        upload(p.phone, "[]").assertError(HttpStatusCode.BadRequest, ApiCodes.MALFORMED_JSON)
        upload(p.phone, "{oops").assertError(HttpStatusCode.BadRequest, ApiCodes.MALFORMED_JSON)
    }

    /** S3 */
    @Test fun row2EveryForbiddenKeyIsRejected() = serverTest {
        val p = pair()
        val canary = "3.1415"
        (SummaryLimits.FORBIDDEN_KEYS + SummaryLimits.FORBIDDEN_KEYS.map { it.uppercase() }).forEach { key ->
            clock.advance(4 * SEC)
            val res = upload(p.phone, summary { this[key] = JsonPrimitive(canary.toDouble()) })
            res.assertError(unprocessable, "raw_health_data_rejected")
            assertTrue(key.lowercase() to "forbidden_key" in res.fields, "${res.fields}")
            assertFalse(canary in res.text, "응답에 값이 없어야 한다")
        }
        assertEquals(0, rows("condition_summaries"))
    }

    @Test fun rawSamplesFixtureIsRejectedWithoutEchoingValues() = serverTest {
        val p = pair()
        val res = upload(p.phone, fixture("summary_raw_samples.json"))
        res.assertError(unprocessable, "raw_health_data_rejected")
        assertFalse("5.7" in res.text)
        assertFalse("mmol\":" in res.text)
        assertEquals(0, rows("condition_summaries"))
    }

    /** S3 */
    @Test fun row3NonScalarValuesAreRawData() = serverTest {
        val p = pair()
        listOf<JsonElement>(
            JsonArray(listOf(JsonPrimitive(5.4), JsonPrimitive(5.5))),
            JsonObject(mapOf("mmol" to JsonPrimitive(5.4))),
        ).forEach { value ->
            SummaryLimits.ALLOWED_KEYS.forEach { key ->
                clock.advance(4 * SEC)
                val res = upload(p.phone, summary { this[key] = value })
                res.assertError(unprocessable, "raw_health_data_rejected")
                assertFalse("5.4" in res.text)
            }
        }
    }

    @Test fun row4UnknownKeyIsNamedWithoutValue() = serverTest {
        val p = pair()
        val res = upload(p.phone, summary { this["note"] = JsonPrimitive("hello-canary") })
        res.assertError(unprocessable, "unknown_field")
        assertTrue("note" to "unknown_field" in res.fields)
        assertFalse("hello-canary" in res.text)
        // 키 이름도 입력이다
        val phoneLike = upload(p.phone, summary { this["+821099990000"] = JsonPrimitive(1) })
        phoneLike.assertError(unprocessable, "unknown_field")
        assertFalse("821099990000" in phoneLike.text)
    }

    @Test fun row5EveryKeyIsRequired() = serverTest {
        val p = pair()
        SummaryLimits.ALLOWED_KEYS.forEach { key ->
            expect(p.phone, "validation_failed", key, "required") { remove(key) }
        }
    }

    /** S6 */
    @Test fun row6ClockSkewBoundary() = serverTest {
        val p = pair()
        val now = clock.nowMs()
        upload(p.phone, summary(now - 300 * SEC) { this["glucose_measured_at"] = ts(now - 300 * SEC); this["valid_until"] = ts(now + 100 * SEC) }).assertOk()
        val late = upload(p.phone, summary(now - 301 * SEC) { this["valid_until"] = ts(now + 100 * SEC); this["glucose_measured_at"] = ts(now - 301 * SEC) })
        late.assertError(unprocessable, "clock_skew")
        assertTrue("generated_at" to "clock_skew" in late.fields)
        upload(p.phone, summary(now + 300 * SEC) { this["glucose_measured_at"] = ts(now + 300 * SEC) }).assertOk()
        upload(p.phone, summary(now + 301 * SEC)).assertError(unprocessable, "clock_skew")
    }

    @Test fun row6Format() = serverTest {
        val p = pair()
        val bad = listOf("2026-11-20", "2026-11-19T23:12:01", "2026-11-19T23:12Z", "yesterday", "2026-11-19t23:12:01z", "2026-13-45T23:12:01Z")
        bad.forEach { expect(p.phone, "validation_failed", "generated_at", "format") { this["generated_at"] = JsonPrimitive(it) } }
        expect(p.phone, "validation_failed", "generated_at", "format") { this["generated_at"] = JsonNull }
        expect(p.phone, "validation_failed", "generated_at", "format") { this["generated_at"] = JsonPrimitive(1) }
        expect(p.phone, "validation_failed", "valid_until", "format") { this["valid_until"] = JsonNull }
    }

    /** S6 */
    @Test fun row7ValidUntilBoundary() = serverTest {
        val p = pair()
        expect(p.phone, "validation_failed", "valid_until", "time_order") { this["valid_until"] = this.getValue("generated_at") }
        clock.advance(4 * SEC)
        val now = clock.nowMs()
        upload(p.phone, summary(now) { this["valid_until"] = ts(now + 901 * SEC) })
            .assertError(unprocessable, "validation_failed")
        upload(p.phone, summary(now) { this["valid_until"] = ts(now + 900 * SEC); this["glucose_measured_at"] = ts(now) }).assertOk()

        clock.advance(4 * SEC)
        val t = clock.nowMs()
        val generated = t - 200 * SEC
        val body = { until: Long -> summary(generated) { noGlucose(); this["valid_until"] = ts(until) } }
        delete(ApiPaths.SUMMARY, p.phone).assertOk()
        upload(p.phone, body(t - 1 * SEC)).assertError(unprocessable, "summary_expired")
        upload(p.phone, body(t)).assertError(unprocessable, "summary_expired")
        upload(p.phone, body(t + 1 * SEC)).assertOk()
    }

    @Test fun row8SleepMinutes() = serverTest {
        val p = pair()
        expect(p.phone, "validation_failed", "sleep_minutes_last_night", "range") { this["sleep_minutes_last_night"] = JsonPrimitive(-1) }
        expect(p.phone, "validation_failed", "sleep_minutes_last_night", "range") { this["sleep_minutes_last_night"] = JsonPrimitive(1441); this["sleep_short"] = JsonPrimitive(false) }
        expect(p.phone, "validation_failed", "sleep_minutes_last_night", "range") { this["sleep_minutes_last_night"] = JsonPrimitive(290.5) }
        expect(p.phone, "validation_failed", "sleep_minutes_last_night", "range") { this["sleep_minutes_last_night"] = JsonPrimitive("290") }
        expectApplied(p.phone) { this["sleep_minutes_last_night"] = JsonPrimitive(0) }
        expectApplied(p.phone) { this["sleep_minutes_last_night"] = JsonPrimitive(1440); this["sleep_short"] = JsonPrimitive(false) }
    }

    /** S5 */
    @Test fun row9SleepCombination() = serverTest {
        val p = pair()
        expect(p.phone, "validation_failed", "sleep_short", "combination") { this["sleep_short"] = JsonPrimitive(false) }
        expect(p.phone, "validation_failed", "sleep_short", "combination") { this["sleep_short"] = JsonNull }
        expect(p.phone, "validation_failed", "sleep_short", "combination") { this["sleep_minutes_last_night"] = JsonNull }
        expect(p.phone, "validation_failed", "sleep_short", "combination") { this["sleep_valid_until"] = JsonNull }
        expect(p.phone, "validation_failed", "sleep_short", "combination") { this["sleep_short"] = JsonPrimitive("true") }
        expect(p.phone, "validation_failed", "sleep_valid_until", "time_order") { this["sleep_valid_until"] = this.getValue("generated_at") }
        clock.advance(4 * SEC)
        val now = clock.nowMs()
        upload(p.phone, summary(now) { this["sleep_valid_until"] = ts(now + 108_001 * SEC) }).assertError(unprocessable, "validation_failed")
        upload(p.phone, summary(now) { this["sleep_valid_until"] = ts(now + 108_000 * SEC) }).assertOk()
    }

    @Test fun row10GlucoseRange() = serverTest {
        val p = pair()
        listOf(JsonPrimitive(0.9), JsonPrimitive(33.4), JsonPrimitive(5.123), JsonPrimitive("5.4"), JsonPrimitive(true)).forEach { v ->
            expect(p.phone, "validation_failed", "glucose_mmol", "range") { this["glucose_mmol"] = v }
        }
        // 무한대는 형태 검사를 지나 DTO 디코딩에서 걸린다. 디코딩 예외에는 입력이 담겨 필드 없이 거절한다
        clock.advance(4 * SEC)
        val infinite = upload(p.phone, summary { this["glucose_mmol"] = kotlinx.serialization.json.JsonUnquotedLiteral("1e999") })
        infinite.assertError(unprocessable, "validation_failed")
        assertFalse("1e999" in infinite.text)
        // 저혈당 값을 거절하면 차량이 끊김으로 착각한다
        listOf(1.0, 33.3, 2.25).forEach { v -> expectApplied(p.phone) { this["glucose_mmol"] = JsonPrimitive(v) } }
    }

    @Test fun row11Trend() = serverTest {
        val p = pair()
        expect(p.phone, "validation_failed", "glucose_trend", "enum") { this["glucose_trend"] = JsonPrimitive("down") }
        expect(p.phone, "validation_failed", "glucose_trend", "enum") { this["glucose_trend"] = JsonPrimitive(1) }
        listOf("falling_fast", "falling", "flat", "rising").forEach { t -> expectApplied(p.phone) { this["glucose_trend"] = JsonPrimitive(t) } }
        expectApplied(p.phone) { this["glucose_trend"] = JsonNull }
    }

    /** S4 */
    @Test fun row12SourceDefault() = serverTest {
        val p = pair()
        expect(p.phone, "glucose_source_not_allowed", "glucose_source", "not_allowed") { this["glucose_source"] = JsonPrimitive("partner_realtime") }
        expect(p.phone, "glucose_source_not_allowed", "glucose_source", "not_allowed") { this["glucose_source"] = JsonPrimitive("health_connect") }
    }

    /** S4 */
    @Test fun row12SourceWithPartnerAllowed() = serverTest(allowPartner = true) {
        val p = pair()
        expectApplied(p.phone) { this["glucose_source"] = JsonPrimitive("partner_realtime") }
        expect(p.phone, "glucose_source_not_allowed", "glucose_source", "not_allowed") { this["glucose_source"] = JsonPrimitive("health_connect") }
    }

    @Test fun row13GlucoseCombination() = serverTest {
        val p = pair()
        expect(p.phone, "validation_failed", "glucose_source", "combination") { this["glucose_source"] = JsonNull }
        expect(p.phone, "validation_failed", "glucose_measured_at", "combination") { this["glucose_measured_at"] = JsonNull }
        expect(p.phone, "validation_failed", "glucose_trend", "combination") { noGlucose(); this["glucose_trend"] = JsonPrimitive("flat") }
        expect(p.phone, "validation_failed", "glucose_source", "combination") { noGlucose(); this["glucose_source"] = JsonPrimitive("simulated") }
        expect(p.phone, "validation_failed", "glucose_measured_at", "combination") { noGlucose(); this["glucose_measured_at"] = ts(clock.nowMs()) }
        // 전체 null도 허용한다
        expectApplied(p.phone) { noGlucose(); noSleep() }
    }

    @Test fun row14MeasuredAt() = serverTest {
        val p = pair()
        clock.advance(4 * SEC)
        val now = clock.nowMs()
        upload(p.phone, summary(now) { this["glucose_measured_at"] = ts(now + 61 * SEC) }).assertError(unprocessable, "validation_failed")
        upload(p.phone, summary(now) { this["glucose_measured_at"] = ts(now + 60 * SEC) }).assertOk()
        clock.advance(4 * SEC)
        val t = clock.nowMs()
        // 끊긴 센서의 오래된 값이 새 값처럼 보이면 안 된다
        val stale = upload(p.phone, summary(t) { this["valid_until"] = ts(t + 870 * SEC); this["glucose_measured_at"] = ts(t - 31 * SEC) })
        stale.assertError(unprocessable, "validation_failed")
        assertTrue("glucose_measured_at" to "time_order" in stale.fields)
        upload(p.phone, summary(t) { this["valid_until"] = ts(t + 870 * SEC); this["glucose_measured_at"] = ts(t - 30 * SEC) }).assertOk()
    }

    /** S7 */
    @Test fun olderOrSameGeneratedAtIsNotApplied() = serverTest {
        val p = pair()
        val t = clock.nowMs()
        upload(p.phone, summary(t) { this["glucose_mmol"] = JsonPrimitive(5.4) }).assertOk()

        val same = upload(p.phone, summary(t) { this["glucose_mmol"] = JsonPrimitive(9.9) })
        same.assertOk()
        assertFalse(same.json.bool("applied"))
        assertEquals("not_newer", same.json.str("reason"))

        clock.advance(4 * SEC)
        val older = upload(p.phone, summary(t - 1 * SEC) { this["glucose_mmol"] = JsonPrimitive(8.8) })
        assertFalse(older.json.bool("applied"))

        val latest = get(ApiPaths.SUMMARY_LATEST, p.vehicle).json.getValue("summary") as JsonObject
        assertEquals("5.4", latest.getValue("glucose_mmol").toString())
    }

    /** S7: 10장 끊김이 즉시 전달된다 */
    @Test fun nullOverwritesStoredValue() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        clock.advance(30 * SEC)
        upload(p.phone, summary { noGlucose() }).assertOk()
        val latest = get(ApiPaths.SUMMARY_LATEST, p.vehicle).json.getValue("summary") as JsonObject
        assertTrue(latest.isNull("glucose_mmol"))
        assertEquals("290", latest.getValue("sleep_minutes_last_night").toString())
        assertEquals(1, rows("condition_summaries"))
    }

    /** S12 */
    @Test fun withdrawnConsentBlocksUploadAndDeletesSummary() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        delete("${ApiPaths.CONSENTS}/health_summary", p.phone).also { it.assertOk() }
        assertEquals(0, rows("condition_summaries"))
        upload(p.phone).assertError(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
        assertTrue(get(ApiPaths.SUMMARY_LATEST, p.vehicle).json.isNull("summary"))
    }

    @Test fun deleteSummaryIsIdempotent() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        repeat(2) {
            val res = delete(ApiPaths.SUMMARY, p.phone)
            res.assertOk()
            assertTrue(res.json.bool("deleted"))
        }
        assertEquals(0, rows("condition_summaries"))
    }

    @Test fun storedPayloadIsCanonicalDto() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_full.json")).assertOk()
        val stored = app.db.tx {
            prepareStatement("SELECT payload_json FROM condition_summaries").use { st -> st.executeQuery().use { it.next(); it.getString(1) } }
        }
        assertEquals(ContractJson.Strict.parseToJsonElement(fixture("summary_full.json")), ContractJson.Strict.parseToJsonElement(stored))
    }
}
