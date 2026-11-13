package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiErrorEnvelope
import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.HealthResponse
import com.conditiondrive.server.db.count
import com.conditiondrive.server.routes.ApiCodes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CommonApiTest {
    @Test fun healthzOk() = serverTest {
        val res = get(ApiPaths.HEALTHZ)
        res.assertOk()
        val body = ContractJson.Strict.decodeFromString(HealthResponse.serializer(), res.text)
        assertEquals(HealthResponse("ok", "0.1.0", "ok", ApiTime.format(BASE_MS)), body)
    }

    @Test fun healthzDegradedWhenDbFails() = serverTest {
        app.db.close()
        val res = get(ApiPaths.HEALTHZ)
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("degraded", res.json.str("status"))
        assertEquals("error", res.json.str("db"))
    }

    @Test fun dbFailureOnAuthenticatedRouteIsUnavailable() = serverTest {
        val vehicle = vehicleToken()
        app.db.close()
        get(ApiPaths.SUMMARY_LATEST, vehicle).assertError(HttpStatusCode.ServiceUnavailable, ApiCodes.UNAVAILABLE)
    }

    @Test fun everyResponseCarriesCommonHeaders() = serverTest {
        val vehicle = vehicleToken()
        val responses = listOf(
            get(ApiPaths.HEALTHZ),
            registerVehicle(),
            get(ApiPaths.SUMMARY_LATEST, vehicle),
            get(ApiPaths.SUMMARY_LATEST),
            get("/no/such/route"),
            call(HttpMethod.Put, ApiPaths.HEALTHZ, "{}"),
            post(ApiPaths.DEVICES_VEHICLE, "not json"),
            post(ApiPaths.DEVICES_VEHICLE, " ".repeat(Limits.BODY_DEFAULT + 1)),
            call(HttpMethod.Post, ApiPaths.DEVICES_VEHICLE, "{}", type = ContentType.Text.Plain),
        )
        val ids = mutableSetOf<String>()
        responses.forEach {
            assertEquals("no-store", it.headers[HttpHeaders.CacheControl], it.text)
            val id = assertNotNull(it.headers["X-Request-Id"], it.text)
            assertTrue(Regex("req_[0-9A-Z]{8}").matches(id), id)
            ids += id
        }
        assertEquals(responses.size, ids.size)
    }

    @Test fun errorEnvelopeMatchesContract() = serverTest {
        val res = post(ApiPaths.DEVICES_VEHICLE, """{"app_version":""}""")
        res.assertError(HttpStatusCode.UnprocessableEntity, "validation_failed")
        val envelope = ContractJson.Strict.decodeFromString(ApiErrorEnvelope.serializer(), res.text)
        assertEquals(res.headers["X-Request-Id"], envelope.error.requestId)
        assertEquals(ApiTime.format(BASE_MS), envelope.error.serverTime)
        // 골든 픽스처와 키 집합이 같아야 차량과 폰 파서가 그대로 읽는다
        val golden = ContractJson.Strict.parseToJsonElement(fixture("error_validation.json"))
        assertEquals((golden as kotlinx.serialization.json.JsonObject).getValue("error").let { (it as kotlinx.serialization.json.JsonObject).keys }, res.error.keys)
    }

    @Test fun unknownRouteAndMethodUseEnvelope() = serverTest {
        get("/v2/whatever").assertError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
        call(HttpMethod.Put, ApiPaths.SUMMARY, "{}").assertError(HttpStatusCode.MethodNotAllowed, ApiCodes.METHOD_NOT_ALLOWED)
    }

    @Test fun domain404KeepsItsOwnCode() = serverTest {
        // StatusPages의 404 처리기가 라우트의 코드를 덮어쓰지 않아야 한다
        claim("AAAAAAAA").assertError(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID)
    }

    @Test fun malformedBodies() = serverTest {
        listOf("", "not json", "[1,2]", "42", "null", "{\"a\":", "{}}").forEach {
            post(ApiPaths.DEVICES_VEHICLE, it).assertError(HttpStatusCode.BadRequest, ApiCodes.MALFORMED_JSON)
        }
    }

    @Test fun deepNestingDoesNotCrash() = serverTest {
        val res = post(ApiPaths.DEVICES_VEHICLE, "[".repeat(Limits.BODY_DEFAULT))
        res.assertError(HttpStatusCode.BadRequest, ApiCodes.MALFORMED_JSON)
    }

    @Test fun unsupportedMediaType() = serverTest {
        call(HttpMethod.Post, ApiPaths.DEVICES_VEHICLE, """{"app_version":"1"}""", type = ContentType.Text.Plain)
            .assertError(HttpStatusCode.UnsupportedMediaType, ApiCodes.UNSUPPORTED_MEDIA_TYPE)
        call(HttpMethod.Post, ApiPaths.DEVICES_VEHICLE, """{"app_version":"1"}""", type = null)
            .assertError(HttpStatusCode.UnsupportedMediaType, ApiCodes.UNSUPPORTED_MEDIA_TYPE)
    }

    @Test fun defaultBodyLimitBoundary() = serverTest {
        val json = """{"app_version":"0.1.0"}"""
        registerVehicleWithPadding(json, Limits.BODY_DEFAULT).assertOk(HttpStatusCode.Created)
        registerVehicleWithPadding(json, Limits.BODY_DEFAULT + 1).assertError(HttpStatusCode.PayloadTooLarge, ApiCodes.PAYLOAD_TOO_LARGE)
    }

    private suspend fun TestEnv.registerVehicleWithPadding(json: String, size: Int) =
        post(ApiPaths.DEVICES_VEHICLE, json + " ".repeat(size - json.length))

    @Test fun vehicleRegisterShapeAndStorage() = serverTest {
        val json = registerVehicle().also { it.assertOk(HttpStatusCode.Created) }.json
        assertTrue(json.str("account_id").startsWith("acc_"))
        assertTrue(json.str("device_id").startsWith("dev_"))
        val token = json.str("vehicle_token")
        assertTrue(Regex("cdv_[A-Za-z0-9_-]{43}").matches(token), "32바이트 base64url")
        assertEquals(ApiTime.format(BASE_MS), json.str("server_time"))
        // 서버에는 SHA-256만 저장한다
        val hash = app.db.tx {
            prepareStatement("SELECT token_hash FROM devices").use { st ->
                st.executeQuery().use { it.next(); it.getBytes(1) }
            }
        }
        assertEquals(32, hash.size)
        assertFalse(String(hash, Charsets.ISO_8859_1).contains(token))
    }

    @Test fun vehicleRegisterValidation() = serverTest {
        post(ApiPaths.DEVICES_VEHICLE, "{}").assertError(HttpStatusCode.UnprocessableEntity, "validation_failed")
        post(ApiPaths.DEVICES_VEHICLE, """{"app_version":1}""").assertError(HttpStatusCode.UnprocessableEntity, "validation_failed")
        post(ApiPaths.DEVICES_VEHICLE, """{"app_version":"1","x":1}""").assertError(HttpStatusCode.UnprocessableEntity, "unknown_field")
        post(ApiPaths.DEVICES_VEHICLE, """{"app_version":"${"9".repeat(41)}"}""").assertError(HttpStatusCode.UnprocessableEntity, "validation_failed")
    }

    @Test fun authFailures() = serverTest {
        get(ApiPaths.SUMMARY_LATEST).assertError(HttpStatusCode.Unauthorized, ApiCodes.INVALID_TOKEN)
        get(ApiPaths.SUMMARY_LATEST, "cdv_wrong").assertError(HttpStatusCode.Unauthorized, ApiCodes.INVALID_TOKEN)
        call(HttpMethod.Get, ApiPaths.SUMMARY_LATEST, token = null)
            .assertError(HttpStatusCode.Unauthorized, ApiCodes.INVALID_TOKEN)
    }

    /** S11. 폰은 요약을 읽을 수 없고 차량은 요약을 쓸 수 없다. */
    @Test fun rolesAreSeparated() = serverTest {
        val p = pair()
        val forbidden = HttpStatusCode.Forbidden
        get(ApiPaths.SUMMARY_LATEST, p.phone).assertError(forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        post(ApiPaths.SUMMARY, summary(), p.vehicle).assertError(forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        delete(ApiPaths.SUMMARY, p.vehicle).assertError(forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        post(ApiPaths.PAIRING_CODES, token = p.phone).assertError(forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        post(ApiPaths.TRIPS, trip(), p.phone).assertError(forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        delete(ApiPaths.ACCOUNT, p.phone).assertError(forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        post(ApiPaths.GUARDIAN_ALERTS, alertBody("2f8d5b1e-3c9a-4e77-8a10-5b6f1c2d3e4f"), p.phone).assertError(forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        put("${ApiPaths.CONSENTS}/health_summary", """{"version":"v"}""", p.vehicle).assertError(forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        // 역할 검사가 값 검증보다 먼저라 잘못된 기기로 내용이 처리되지 않는다
        assertEquals(0, rows("condition_summaries"))
    }

    @Test fun accountsAreIsolated() = serverTest {
        val a = pair()
        val b = pair()
        upload(a.phone).assertOk()
        assertTrue(get(ApiPaths.SUMMARY_LATEST, b.vehicle).json.isNull("summary"))
        assertFalse(get(ApiPaths.SUMMARY_LATEST, a.vehicle).json.isNull("summary"))
    }

    /** S16 */
    @Test fun accountDeleteRemovesEveryRow() = serverTest {
        val p = pair()
        val other = pair()
        upload(p.phone).assertOk()
        upload(other.phone).assertOk()
        post(ApiPaths.TRIPS, trip(), p.vehicle).assertOk(HttpStatusCode.Created)
        activeGuardian(p.vehicle)
        newCode(p.vehicle)

        val res = delete(ApiPaths.ACCOUNT, p.vehicle)
        res.assertOk()
        assertEquals("""{"deleted":true}""", res.text)

        // 재호출은 401이다. 토큰이 행과 함께 사라져 구분할 수 없다
        delete(ApiPaths.ACCOUNT, p.vehicle).assertError(HttpStatusCode.Unauthorized, ApiCodes.INVALID_TOKEN)
        upload(p.phone).assertError(HttpStatusCode.Unauthorized, ApiCodes.INVALID_TOKEN)

        // 다른 계정 1개의 행만 남는다
        assertEquals(1, rows("accounts"))
        assertEquals(2, rows("devices"))
        assertEquals(1, rows("consents"))
        assertEquals(1, rows("condition_summaries"))
        assertEquals(0, rows("trips"))
        assertEquals(0, rows("guardians"))
        assertEquals(0, rows("guardian_messages"))
        assertEquals(1, rows("pairing_codes")) // other의 소비된 코드
        assertFalse(get(ApiPaths.SUMMARY_LATEST, other.vehicle).json.isNull("summary"))
    }

    /** WAL에도 요약이 들어가므로 본 파일만 잠그면 부족하다. */
    @Test fun databaseFilesAreOwnerOnly() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        val dir = app.config.dbPath.parent
        val names = listOf("test.db", "test.db-wal", "test.db-shm")
        names.forEach {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir.resolve(it))), it)
        }
    }

    /** BEGIN IMMEDIATE와 단일 커넥션이 지켜지는지 실제 동시 요청으로 본다. */
    @Test fun concurrentClaimsConsumeCodeOnce() = serverTest {
        val vehicle = vehicleToken()
        val code = newCode(vehicle)
        val results = coroutineScope { List(10) { async(Dispatchers.Default) { claim(code) } }.awaitAll() }
        assertEquals(1, results.count { it.status == HttpStatusCode.Created }, results.map { it.status }.toString())
        results.filter { it.status != HttpStatusCode.Created }.forEach { it.assertError(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID) }
        assertEquals(1, app.db.tx { count("SELECT COUNT(*) FROM devices WHERE role = 'phone' AND revoked_at IS NULL") })
    }

    @Test fun concurrentUploadsKeepNewest() = serverTest {
        val p = pair()
        val base = clock.nowMs()
        val results = coroutineScope {
            List(10) { i -> async(Dispatchers.Default) { upload(p.phone, summary(base + i * SEC)) } }.awaitAll()
        }
        results.forEach { it.assertOk() }
        assertEquals(1, rows("condition_summaries"))
        val latest = get(ApiPaths.SUMMARY_LATEST, p.vehicle).json.getValue("summary") as kotlinx.serialization.json.JsonObject
        assertEquals(ApiTime.format(base + 9 * SEC), latest.str("generated_at"))
    }

    @Test fun migrationKeepsDataAcrossRestart() {
        val dir = newDbDir()
        var token = ""
        serverTest(dir) { token = vehicleToken() }
        serverTest(dir) { get(ApiPaths.SUMMARY_LATEST, token).assertOk() }
    }
}
