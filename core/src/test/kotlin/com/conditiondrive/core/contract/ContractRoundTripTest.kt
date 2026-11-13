package com.conditiondrive.core.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** S1, S20. 픽스처와 12장 JSON이 DTO를 거쳐도 키 하나 바뀌지 않아야 한다 */
class ContractRoundTripTest {
    private inline fun <reified T> assertStrictRoundTrip(raw: String) {
        val serializer: KSerializer<T> = serializer()
        val decoded = ContractJson.Strict.decodeFromString(serializer, raw)
        val encoded = ContractJson.Strict.encodeToString(serializer, decoded)
        assertEquals(ContractJson.Strict.parseToJsonElement(raw), ContractJson.Strict.parseToJsonElement(encoded))
    }

    @Test
    fun goldenFixturesRoundTrip() {
        assertStrictRoundTrip<SummaryDto>(ContractFixtures.text("summary_full.json"))
        assertStrictRoundTrip<SummaryDto>(ContractFixtures.text("summary_glucose_only.json"))
        assertStrictRoundTrip<SummaryDto>(ContractFixtures.text("summary_all_null.json"))
        assertStrictRoundTrip<LatestSummaryResponse>(ContractFixtures.text("latest_masked.json"))
        assertStrictRoundTrip<TripUploadRequest>(ContractFixtures.text("trip.json"))
        assertStrictRoundTrip<ApiErrorEnvelope>(ContractFixtures.text("error_validation.json"))
    }

    @Test
    fun summaryFullDecodesToExpectedValues() {
        val dto = ContractJson.Strict.decodeFromString<SummaryDto>(ContractFixtures.text("summary_full.json"))
        assertEquals("2026-11-20T08:12:00+09:00", dto.generatedAt)
        assertEquals(290, dto.sleepMinutesLastNight)
        assertEquals(true, dto.sleepShort)
        assertEquals(5.4, dto.glucoseMmol)
        assertEquals(GlucoseTrendDto.FALLING, dto.glucoseTrend)
        assertEquals(GlucoseSourceDto.SIMULATED, dto.glucoseSource)
    }

    @Test
    fun rawSamplesFixtureIsRejectedByStrictAndByRawCheck() {
        assertFailsWith<SerializationException> {
            ContractJson.Strict.decodeFromString<SummaryDto>(ContractFixtures.text("summary_raw_samples.json"))
        }
        val violations = SummaryValidator.validateRawKeys(ContractFixtures.obj("summary_raw_samples.json"))
        assertEquals(ViolationCodes.RAW_HEALTH_DATA_REJECTED, violations.first().code)
    }

    /** 모든 키가 항상 있어야 한다(12.7). 빠진 키를 null로 메워 주면 끊김과 누락이 구분되지 않는다 */
    @Test
    fun strictRejectsMissingNullableKey() {
        val raw = ContractFixtures.text("summary_all_null.json").replace(""""glucose_trend": null,""", "")
        assertFailsWith<SerializationException> { ContractJson.Strict.decodeFromString<SummaryDto>(raw) }
    }

    @Test
    fun strictEncodesNullsAndDefaultsExplicitly() {
        val encoded = ContractJson.Strict.encodeToString(
            SummaryUploadResponse.serializer(), SummaryUploadResponse(applied = true, serverTime = "2026-11-19T23:12:01Z"),
        )
        assertTrue(""""reason":null""" in encoded, encoded)
        assertTrue(""""received_at":null""" in encoded, encoded)
    }

    @Test
    fun lenientIgnoresNewFields() {
        val raw = """
            {"paired":true,"phone_name":"폰","server_time":"2026-11-19T23:13:05Z","new_top":1,
             "summary":{"generated_at":"2026-11-20T08:12:00+09:00","valid_until":"2026-11-20T08:26:30+09:00",
             "sleep_minutes_last_night":290,"sleep_short":true,"sleep_valid_until":"2026-11-21T04:00:00+09:00",
             "glucose_mmol":5.4,"glucose_trend":"falling","glucose_source":"simulated",
             "glucose_measured_at":"2026-11-20T08:11:30+09:00","new_inner":{"a":[1]}}}
        """
        val decoded = ContractJson.Lenient.decodeFromString<LatestSummaryResponse>(raw)
        assertEquals(5.4, decoded.summary?.glucoseMmol)
        assertEquals(290, decoded.summary?.sleepMinutesLastNight)
        assertFailsWith<SerializationException> { ContractJson.Strict.decodeFromString<LatestSummaryResponse>(raw) }
    }

    private fun latestWith(trend: String, source: String) = """
        {"paired":true,"server_time":"2026-11-19T23:13:05Z",
         "summary":{"generated_at":"2026-11-20T08:12:00+09:00","valid_until":"2026-11-20T08:26:30+09:00",
         "sleep_minutes_last_night":290,"sleep_short":true,"sleep_valid_until":"2026-11-21T04:00:00+09:00",
         "glucose_mmol":3.5,"glucose_trend":$trend,"glucose_source":$source,
         "glucose_measured_at":"2026-11-20T08:11:30+09:00"}}
    """

    /** S20, D13. 혈당만 무효가 되고 수면은 산다. 예외로 전체를 잃지 않는다 */
    @Test
    fun lenientUnknownEnumInvalidatesWholeGlucoseButKeepsSleep() {
        val cases = listOf(
            latestWith("\"rapidly_falling\"", "\"simulated\""),
            latestWith("\"falling\"", "\"health_connect\""),
            latestWith("7", "\"simulated\""),
        )
        for (raw in cases) {
            val summary = ContractJson.Lenient.decodeFromString<LatestSummaryResponse>(raw).summary!!
            assertNull(summary.glucoseMmol, raw)
            assertNull(summary.glucoseTrend, raw)
            assertNull(summary.glucoseSource, raw)
            assertNull(summary.glucoseMeasuredAt, raw)
            assertEquals(290, summary.sleepMinutesLastNight, raw)
            assertEquals(emptyList(), SummaryValidator.validateReceived(summary, ContractFixtures.ms("2026-11-20T08:13:05+09:00"), allowPartner = false))
        }
    }

    @Test
    fun lenientKeepsNullTrendWithValue() {
        val summary = ContractJson.Lenient.decodeFromString<LatestSummaryResponse>(latestWith("null", "\"simulated\"")).summary!!
        assertEquals(3.5, summary.glucoseMmol)
        assertNull(summary.glucoseTrend)
    }

    @Test
    fun latestWithoutSummaryDecodes() {
        val decoded = ContractJson.Lenient.decodeFromString<LatestSummaryResponse>("""{"paired":false,"server_time":"2026-11-19T23:13:05Z"}""")
        assertEquals(false, decoded.paired)
        assertNull(decoded.summary)
        assertNull(ContractJson.Lenient.decodeFromString<LatestSummaryResponse>("""{"paired":true,"server_time":"t","summary":null,"received_at":null}""").summary)
    }

    /** 설계서 12장 예시 JSON 전부. 이름이 하나라도 틀리면 Strict 디코딩이나 왕복에서 걸린다 */
    @Test
    fun section12ExamplesRoundTrip() {
        assertStrictRoundTrip<HealthResponse>("""{ "status": "ok", "version": "0.1.0", "db": "ok", "server_time": "2026-11-19T23:12:05Z" }""")
        assertStrictRoundTrip<VehicleRegisterRequest>("""{ "app_version": "0.1.0" }""")
        assertStrictRoundTrip<VehicleRegisterResponse>("""{"account_id":"acc_9f3c1b7e","device_id":"dev_a81d44c0","vehicle_token":"cdv_3Jq7","server_time":"2026-11-19T23:12:05Z"}""")
        assertStrictRoundTrip<PairingCodeResponse>("""{"code":"K7M2QX9T","qr_payload":"conditiondrive://pair?v=1&code=K7M2QX9T","expires_at":"2026-11-19T23:17:05Z","ttl_sec":300,"replaces_phone":false,"server_time":"2026-11-19T23:12:05Z"}""")
        assertStrictRoundTrip<PairingClaimRequest>("""{"code":"K7M2QX9T","device_name":"Galaxy S25","app_version":"0.1.0","consents":[{"type":"health_summary","version":"2026-11-v1"}]}""")
        assertStrictRoundTrip<PairingClaimResponse>("""{"account_id":"acc_9f3c1b7e","device_id":"dev_77c20e1a","phone_token":"cdp_Xk2L","server_time":"2026-11-19T23:13:10Z"}""")
        assertStrictRoundTrip<PairingStatusResponse>("""{"paired":true,"phone":{"device_id":"dev_77c20e1a","device_name":"Galaxy S25","paired_at":"2026-11-19T23:13:10Z","last_seen_at":"2026-11-19T23:15:00Z"},"vehicle_last_seen_at":"2026-11-19T23:15:02Z","pending_code_expires_at":null,"server_time":"2026-11-19T23:15:04Z"}""")
        assertStrictRoundTrip<PairingStatusResponse>("""{"paired":false,"phone":null,"vehicle_last_seen_at":null,"pending_code_expires_at":"2026-11-19T23:17:05Z","server_time":"2026-11-19T23:15:04Z"}""")
        assertStrictRoundTrip<UnpairResponse>("""{ "unpaired": true, "summary_deleted": true, "server_time": "2026-11-19T23:15:04Z" }""")
        assertStrictRoundTrip<SummaryUploadResponse>("""{"applied":true,"reason":null,"received_at":"2026-11-19T23:12:01Z","vehicle_last_seen_at":"2026-11-19T23:11:50Z","server_time":"2026-11-19T23:12:01Z"}""")
        assertStrictRoundTrip<SummaryUploadResponse>("""{"applied":false,"reason":"not_newer","received_at":null,"vehicle_last_seen_at":null,"server_time":"2026-11-19T23:12:01Z"}""")
        assertStrictRoundTrip<SummaryDeleteResponse>("""{ "deleted": true, "server_time": "2026-11-19T23:12:01Z" }""")
        assertStrictRoundTrip<ConsentPutRequest>("""{ "version": "2026-11-v1" }""")
        assertStrictRoundTrip<ConsentPutResponse>("""{ "type": "health_summary", "version": "2026-11-v1", "granted_at": "2026-11-19T23:12:01Z" }""")
        assertStrictRoundTrip<ConsentWithdrawResponse>("""{ "withdrawn": true, "data_deleted": true }""")
        assertStrictRoundTrip<TripUploadResponse>("""{ "applied": true, "trip_id": "6f1e6a9c", "rev": 3, "server_time": "2026-11-19T23:12:01Z" }""")
        assertStrictRoundTrip<TripDeleteResponse>("""{ "deleted_count": 12, "trips_deleted_before": "2026-11-19T23:12:01Z", "server_time": "2026-11-19T23:12:01Z" }""")
        assertStrictRoundTrip<AccountDeleteResponse>("""{ "deleted": true }""")
        assertStrictRoundTrip<GuardianRegisterRequest>("""{"phone_e164":"+821000000000","guardian_label":"가족","driver_display_name":"길동","driver_consent":{"version":"2026-11-v1","agreed":true}}""")
        assertStrictRoundTrip<GuardianRegisterResponse>("""{"guardian_id":"grd_41ac","status":"pending_guardian_consent","phone_masked":"+82******0000","consent_expires_at":"2026-11-19T23:22:05Z","delivery":"logged","server_time":"2026-11-19T23:12:05Z"}""")
        assertStrictRoundTrip<GuardianConfirmRequest>("""{ "code": "482913" }""")
        assertStrictRoundTrip<GuardianConfirmResponse>("""{ "guardian_id": "grd_41ac", "status": "active", "guardian_consented_at": "2026-11-19T23:22:05Z", "server_time": "2026-11-19T23:22:05Z" }""")
        assertStrictRoundTrip<GuardianOptOutRequest>("""{ "opt_out_code": "9XK2M4" }""")
        assertStrictRoundTrip<GuardianOptOutResponse>("""{ "ok": true }""")
        assertStrictRoundTrip<GuardianAlertRequest>("""{"alert_id":"2f8d5b1e-3c9a-4e77-8a10-5b6f1c2d3e4f","reason":"glucose_stop_ignored","occurred_at":"2026-11-20T09:02:00+09:00","ignored_for_sec":620,"glucose_source":"simulated"}""")
        assertStrictRoundTrip<GuardianAlertResponse>("""{"alert_id":"2f8d5b1e-3c9a-4e77-8a10-5b6f1c2d3e4f","delivery":"suppressed_simulated","server_time":"2026-11-20T00:02:03Z"}""")
    }

    @Test
    fun apiPathsMatchSection12() {
        assertEquals("/healthz", ApiPaths.HEALTHZ)
        assertEquals("/v1/summary/latest", ApiPaths.SUMMARY_LATEST)
        assertEquals("/v1/guardian-opt-out", ApiPaths.GUARDIAN_OPT_OUT)
        val doc = com.conditiondrive.core.voice.RepoFiles.text("docs/DESIGN.md")
        val paths = listOf(
            ApiPaths.HEALTHZ, ApiPaths.DEVICES_VEHICLE, ApiPaths.PAIRING_CODES, ApiPaths.PAIRING_CLAIM, ApiPaths.PAIRING,
            ApiPaths.SUMMARY, ApiPaths.SUMMARY_LATEST, ApiPaths.CONSENTS, ApiPaths.TRIPS, ApiPaths.ACCOUNT,
            ApiPaths.GUARDIANS, ApiPaths.GUARDIAN_OPT_OUT, ApiPaths.GUARDIAN_ALERTS,
        )
        assertEquals(emptyList(), paths.filter { it !in doc })
    }
}
