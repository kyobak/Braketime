package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.PairingStatusResponse
import com.conditiondrive.server.routes.ApiCodes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingApiTest {
    @Test fun codeShape() = serverTest {
        val vehicle = vehicleToken()
        val json = post(ApiPaths.PAIRING_CODES, token = vehicle).also { it.assertOk(HttpStatusCode.Created) }.json
        val code = json.str("code")
        assertTrue(Regex("[0-9A-HJKMNP-TV-Z]{8}").matches(code), "Crockford Base32")
        // 악성 QR이 요약을 다른 서버로 보내지 못하게 URL을 넣지 않는다
        assertEquals("conditiondrive://pair?v=1&code=$code", json.str("qr_payload"))
        assertEquals(300, json.getValue("ttl_sec").toString().toInt())
        assertEquals(ApiTime.format(BASE_MS + 300 * SEC), json.str("expires_at"))
        assertFalse(json.bool("replaces_phone"))
    }

    @Test fun codeIsStoredOnlyAsHash() = serverTest {
        val code = newCode(vehicleToken())
        app.db.checkpoint()
        val bytes = Files.readAllBytes(app.config.dbPath)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(code))
    }

    @Test fun claimShapeAndStatus() = serverTest {
        val vehicle = vehicleToken()
        val code = newCode(vehicle)
        clock.advance(10 * SEC)
        val json = claim(code).also { it.assertOk(HttpStatusCode.Created) }.json
        assertTrue(json.str("phone_token").startsWith("cdp_"))
        assertTrue(json.str("device_id").startsWith("dev_"))
        assertTrue(json.str("account_id").startsWith("acc_"))

        clock.advance(5 * SEC)
        val status = ContractJson.Strict.decodeFromString(PairingStatusResponse.serializer(), get(ApiPaths.PAIRING, vehicle).text)
        assertTrue(status.paired)
        assertEquals("Galaxy S25", status.phone?.deviceName)
        assertEquals(ApiTime.format(BASE_MS + 10 * SEC), status.phone?.pairedAt)
        assertEquals(ApiTime.format(BASE_MS + 15 * SEC), status.vehicleLastSeenAt)
        assertNull(status.pendingCodeExpiresAt)
    }

    @Test fun statusShowsPendingCodeAndUnpaired() = serverTest {
        val vehicle = vehicleToken()
        get(ApiPaths.PAIRING, vehicle).json.let {
            assertFalse(it.bool("paired"))
            assertTrue(it.isNull("phone"))
            assertTrue(it.isNull("pending_code_expires_at"))
        }
        newCode(vehicle)
        assertEquals(ApiTime.format(BASE_MS + 300 * SEC), get(ApiPaths.PAIRING, vehicle).json.str("pending_code_expires_at"))
        clock.advance(300 * SEC)
        assertTrue(get(ApiPaths.PAIRING, vehicle).json.isNull("pending_code_expires_at"))
    }

    @Test fun phoneCanReadStatusButNotOtherRoutes() = serverTest {
        val p = pair()
        val json = get(ApiPaths.PAIRING, p.phone).also { it.assertOk() }.json
        assertTrue(json.bool("paired"))
    }

    /** S9 수명 */
    @Test fun codeLifetimeBoundary() = serverTest {
        val vehicle = vehicleToken()
        val code = newCode(vehicle)
        clock.advance(300 * SEC - 1)
        claim(code).assertOk(HttpStatusCode.Created)

        val second = newCode(vehicle)
        clock.advance(300 * SEC)
        claim(second).assertError(HttpStatusCode.Gone, ApiCodes.PAIRING_CODE_EXPIRED)
    }

    /** S9 1회용 */
    @Test fun codeIsSingleUse() = serverTest {
        val code = newCode(vehicleToken())
        claim(code).assertOk(HttpStatusCode.Created)
        claim(code).assertError(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID)
    }

    /** S9 재발급 */
    @Test fun reissueInvalidatesPreviousCode() = serverTest {
        val vehicle = vehicleToken()
        val first = newCode(vehicle)
        val second = newCode(vehicle)
        claim(first).assertError(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID)
        claim(second).assertOk(HttpStatusCode.Created)
    }

    @Test fun codeIsNormalized() = serverTest {
        val code = newCode(vehicleToken())
        val messy = code.lowercase().substring(0, 4) + "- " + code.lowercase().substring(4)
        claim(messy).assertOk(HttpStatusCode.Created)
    }

    @Test fun unknownCodeIsInvalid() = serverTest {
        listOf("AAAAAAAA", "too-short", "", "한글코드한글코드").forEach {
            claim(it).assertError(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID)
        }
    }

    /** S9 전역 실패 제한: 30건까지는 원래 오류, 넘으면 60초 잠금. */
    @Test fun globalFailureLimit() = serverTest {
        val goodCode = newCode(vehicleToken())
        repeat(Limits.CLAIM_FAILURES) {
            claim("AAAAAAAA").assertError(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID)
        }
        val tripped = claim("AAAAAAAA")
        tripped.assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
        assertEquals("60", tripped.headers[HttpHeaders.RetryAfter])

        // 잠금 중에는 맞는 코드도 막힌다. 코드는 소비되지 않는다
        clock.advance(59 * SEC)
        claim(goodCode).assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
        clock.advance(1 * SEC)
        claim(goodCode).assertOk(HttpStatusCode.Created)
    }

    @Test fun failureWindowSlides() = serverTest {
        repeat(Limits.CLAIM_FAILURES) { claim("AAAAAAAA") }
        clock.advance(10 * MIN)
        claim("AAAAAAAA").assertError(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID)
    }

    @Test fun expiredCodeCountsAsFailure() = serverTest {
        val vehicle = vehicleToken()
        val code = newCode(vehicle)
        clock.advance(300 * SEC)
        repeat(Limits.CLAIM_FAILURES) { claim(code).assertError(HttpStatusCode.Gone, ApiCodes.PAIRING_CODE_EXPIRED) }
        claim(code).assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
    }

    @Test fun successDoesNotCountAsFailure() = serverTest {
        repeat(Limits.CLAIM_FAILURES) { claim("AAAAAAAA") }
        // 실패 한도 직전에 성공이 있어도 카운트가 늘지 않아 다음 실패까지 잠기지 않는다
        pair()
        claim("BBBBBBBB").assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
    }

    /** S12 */
    @Test fun claimRequiresHealthConsentAndKeepsCode() = serverTest {
        val code = newCode(vehicleToken())
        claim(code, withConsent = false).assertError(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
        post(
            ApiPaths.PAIRING_CLAIM,
            """{"code":"$code","device_name":"폰","app_version":"1","consents":[{"type":"guardian_alert","version":"v"}]}""",
        ).assertError(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
        assertEquals(0, rows("consents"))
        claim(code).assertOk(HttpStatusCode.Created)
        assertEquals(1, rows("consents"))
    }

    @Test fun claimValidation() = serverTest {
        val code = newCode(vehicleToken())
        val unprocessable = HttpStatusCode.UnprocessableEntity
        claim(code, name = "").assertError(unprocessable, "validation_failed")
        claim(code, name = "   ").assertError(unprocessable, "validation_failed")
        claim(code, name = "가".repeat(41)).assertError(unprocessable, "validation_failed")
        claim(code, name = "a\\u0000b").assertError(unprocessable, "validation_failed")
        post(ApiPaths.PAIRING_CLAIM, """{"code":"$code"}""").assertError(unprocessable, "validation_failed")
        post(ApiPaths.PAIRING_CLAIM, """{"code":"$code","device_name":"폰","app_version":"1","consents":"yes"}""").assertError(unprocessable, "validation_failed")
        post(ApiPaths.PAIRING_CLAIM, """{"code":"$code","device_name":"폰","app_version":"1","consents":["x"]}""").assertError(unprocessable, "validation_failed")
        post(ApiPaths.PAIRING_CLAIM, """{"code":"$code","device_name":"폰","app_version":"1","consents":[{"type":"marketing","version":"v"}]}""").assertError(unprocessable, "validation_failed")
        post(ApiPaths.PAIRING_CLAIM, """{"code":"$code","device_name":"폰","app_version":"1","consents":[{"type":"health_summary","version":""}]}""").assertError(unprocessable, "validation_failed")
        post(ApiPaths.PAIRING_CLAIM, """{"code":"$code","device_name":"폰","app_version":"1","consents":[],"lat":1}""").assertError(unprocessable, "raw_health_data_rejected")
        // 40자는 허용하고 코드는 위 실패로 소비되지 않았다
        claim(code, name = "가".repeat(40)).assertOk(HttpStatusCode.Created)
    }

    /** S10 */
    @Test fun repairingRevokesOldPhoneAndDeletesSummary() = serverTest {
        val first = pair()
        upload(first.phone).assertOk()
        assertFalse(get(ApiPaths.SUMMARY_LATEST, first.vehicle).json.isNull("summary"))

        val reissue = post(ApiPaths.PAIRING_CODES, token = first.vehicle).json
        assertTrue(reissue.bool("replaces_phone"))
        // 코드만 발급한 상태에서는 기존 연결이 그대로다
        assertFalse(get(ApiPaths.SUMMARY_LATEST, first.vehicle).json.isNull("summary"))

        val second = claim(reissue.str("code"), name = "새 폰").also { it.assertOk(HttpStatusCode.Created) }.json
        assertEquals(first.accountId, second.str("account_id"))

        upload(first.phone).assertError(HttpStatusCode.Unauthorized, ApiCodes.DEVICE_REVOKED)
        val latest = get(ApiPaths.SUMMARY_LATEST, first.vehicle).json
        assertTrue(latest.isNull("summary"))
        assertEquals("새 폰", latest.str("phone_name"))
        upload(second.str("phone_token")).assertOk()
    }

    @Test fun unpairByVehicleIsIdempotent() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        delete(ApiPaths.PAIRING, p.vehicle).also { it.assertOk() }.json.let {
            assertTrue(it.bool("unpaired"))
            assertTrue(it.bool("summary_deleted"))
        }
        assertEquals(0, rows("condition_summaries"))
        upload(p.phone).assertError(HttpStatusCode.Unauthorized, ApiCodes.DEVICE_REVOKED)
        delete(ApiPaths.PAIRING, p.vehicle).also { it.assertOk() }.json.let { assertFalse(it.bool("unpaired")) }
    }

    @Test fun unpairByPhone() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        delete(ApiPaths.PAIRING, p.phone).also { it.assertOk() }.json.let { assertTrue(it.bool("unpaired")) }
        assertEquals(0, rows("condition_summaries"))
        val latest = get(ApiPaths.SUMMARY_LATEST, p.vehicle).also { it.assertOk() }.json
        assertFalse(latest.bool("paired"))
        assertTrue(latest.isNull("summary"))
        delete(ApiPaths.PAIRING, p.phone).assertError(HttpStatusCode.Unauthorized, ApiCodes.DEVICE_REVOKED)
    }
}
