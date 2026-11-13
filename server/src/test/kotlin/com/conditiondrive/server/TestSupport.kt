package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.time.ManualClock
import com.conditiondrive.server.db.count
import com.conditiondrive.server.sms.Delivery
import com.conditiondrive.server.sms.GuardianMessageSender
import com.conditiondrive.server.sms.OutboundSms
import com.conditiondrive.server.sms.SendResult
import com.conditiondrive.server.sms.SmsRenderer
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/** 골든 픽스처의 generated_at(2026-11-20T08:12:00+09:00) 바로 뒤라 픽스처를 그대로 보낼 수 있다. */
val BASE_MS: Long = ms("2026-11-19T23:12:01Z")
const val SEC = 1000L
const val MIN = 60 * SEC
const val HOUR = 60 * MIN
const val DAY = 24 * HOUR
const val TEST_PHONE = "+821000000001"
const val CONSENT_VERSION = "2026-11-v1"

fun ms(rfc3339: String): Long = OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli()

fun fixture(name: String): String =
    checkNotNull(TestEnv::class.java.getResourceAsStream("/contract/$name")) { "픽스처 없음: $name" }
        .bufferedReader().use { it.readText() }

class RecordingSender : GuardianMessageSender {
    val sent = mutableListOf<OutboundSms>()
    var failNext = 0
    var throwNext: Exception? = null

    override suspend fun send(sms: OutboundSms): SendResult {
        throwNext?.let { throwNext = null; throw it }
        if (failNext > 0) {
            failNext--
            return SendResult.Failed(retryable = true, code = "down")
        }
        sent += sms
        return SendResult.Accepted(Delivery.LOGGED, null)
    }

    /** 문자 전문에서 코드를 꺼낸다. 실제 보호자가 읽는 경로와 같다. */
    fun lastConsentCode(): String = Regex("""확인 코드 (\d{6})""").find(SmsRenderer.render(sent.last()))!!.groupValues[1]
    fun lastOptOutCode(): String = Regex("""수신 거부 코드 (\w{6})""").find(SmsRenderer.render(sent.last()))!!.groupValues[1]
}

class Res(val status: HttpStatusCode, val text: String, val headers: Headers) {
    val json: JsonObject get() = ContractJson.Strict.parseToJsonElement(text).jsonObject
    val error: JsonObject get() = json.getValue("error").jsonObject
    val code: String get() = error.str("code")
    val fields: List<Pair<String, String>>
        get() = error.getValue("fields").jsonArray.map { it.jsonObject.str("field") to it.jsonObject.str("rule") }

    fun assertError(expected: HttpStatusCode, expectedCode: String) {
        assertEquals(expected, status, text)
        assertEquals(expectedCode, code, text)
    }

    fun assertOk(expected: HttpStatusCode = HttpStatusCode.OK) = assertEquals(expected, status, text)
}

fun JsonObject.str(key: String): String = getValue(key).jsonPrimitive.content
fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.content.toBoolean()
fun JsonObject.isNull(key: String): Boolean = getValue(key) is JsonNull

data class Pairing(val vehicle: String, val phone: String, val accountId: String)

class TestEnv(val app: ServerApp, val clock: ManualClock, val sender: RecordingSender, private val client: HttpClient) {
    suspend fun call(
        method: HttpMethod,
        path: String,
        body: String? = null,
        token: String? = null,
        type: ContentType? = ContentType.Application.Json,
    ): Res {
        val response = client.request(path) {
            this.method = method
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) {
                type?.let { contentType(it) }
                setBody(body)
            }
        }
        return Res(response.status, response.bodyAsText(), response.headers)
    }

    suspend fun get(path: String, token: String? = null) = call(HttpMethod.Get, path, token = token)
    suspend fun post(path: String, body: String? = null, token: String? = null) = call(HttpMethod.Post, path, body, token)
    suspend fun delete(path: String, token: String? = null) = call(HttpMethod.Delete, path, token = token)
    suspend fun put(path: String, body: String, token: String? = null) = call(HttpMethod.Put, path, body, token)

    suspend fun registerVehicle(): Res = post(ApiPaths.DEVICES_VEHICLE, """{"app_version":"0.1.0"}""")

    suspend fun vehicleToken(): String = registerVehicle().also { it.assertOk(HttpStatusCode.Created) }.json.str("vehicle_token")

    suspend fun newCode(vehicle: String): String =
        post(ApiPaths.PAIRING_CODES, token = vehicle).also { it.assertOk(HttpStatusCode.Created) }.json.str("code")

    fun claimBody(code: String, name: String = "Galaxy S25", withConsent: Boolean = true): String {
        val consents = if (withConsent) """[{"type":"health_summary","version":"$CONSENT_VERSION"}]""" else "[]"
        return """{"code":"$code","device_name":"$name","app_version":"0.1.0","consents":$consents}"""
    }

    suspend fun claim(code: String, name: String = "Galaxy S25", withConsent: Boolean = true) =
        post(ApiPaths.PAIRING_CLAIM, claimBody(code, name, withConsent))

    suspend fun pair(vehicle: String? = null): Pairing {
        val vehicleToken = vehicle ?: vehicleToken()
        val claimed = claim(newCode(vehicleToken)).also { it.assertOk(HttpStatusCode.Created) }.json
        return Pairing(vehicleToken, claimed.str("phone_token"), claimed.str("account_id"))
    }

    /** 현재 시각 기준으로 12.7 표를 모두 통과하는 요약. */
    fun summary(now: Long = clock.nowMs(), change: MutableMap<String, JsonElement>.() -> Unit = {}): String {
        val fields = linkedMapOf<String, JsonElement>(
            "generated_at" to JsonPrimitive(ApiTime.format(now)),
            "valid_until" to JsonPrimitive(ApiTime.format(now + 870 * SEC)),
            "sleep_minutes_last_night" to JsonPrimitive(290),
            "sleep_short" to JsonPrimitive(true),
            "sleep_valid_until" to JsonPrimitive(ApiTime.format(now + 20 * HOUR)),
            "glucose_mmol" to JsonPrimitive(5.4),
            "glucose_trend" to JsonPrimitive("falling"),
            "glucose_source" to JsonPrimitive("simulated"),
            "glucose_measured_at" to JsonPrimitive(ApiTime.format(now - 30 * SEC)),
        )
        fields.change()
        return JsonObject(fields).toString()
    }

    suspend fun upload(phone: String, body: String = summary()) = post(ApiPaths.SUMMARY, body, phone)

    fun trip(tripId: String = "6f1e6a9c-52f7-4f3e-9a54-0f0c2c9c1a11", rev: Int = 1, start: Long = clock.nowMs() - HOUR, change: MutableMap<String, JsonElement>.() -> Unit = {}): String {
        val fields = linkedMapOf<String, JsonElement>(
            "trip_id" to JsonPrimitive(tripId),
            "rev" to JsonPrimitive(rev),
            "started_at" to JsonPrimitive(ApiTime.format(start)),
            "ended_at" to JsonNull,
            "drive_sec" to JsonPrimitive(1800),
            "rest_sec" to JsonPrimitive(600),
            "segments" to ContractJson.Strict.parseToJsonElement(
                """[{"type":"drive","start":"${ApiTime.format(start)}","end":"${ApiTime.format(start + 30 * MIN)}"},
                    {"type":"rest","start":"${ApiTime.format(start + 30 * MIN)}","end":"${ApiTime.format(start + 40 * MIN)}"}]""",
            ),
            "stop_advised_count" to JsonPrimitive(1),
            "declined_count" to JsonPrimitive(0),
            "rest_completed_count" to JsonPrimitive(1),
        )
        fields.change()
        return JsonObject(fields).toString()
    }

    fun guardianBody(phone: String = TEST_PHONE, agreed: Boolean = true, name: String = "길동", label: String = "가족"): String = buildJsonObject {
        put("phone_e164", phone)
        put("guardian_label", label)
        put("driver_display_name", name)
        put("driver_consent", buildJsonObject { put("version", CONSENT_VERSION); put("agreed", agreed) })
    }.toString()

    /** 양쪽 동의까지 끝낸 활성 보호자. */
    suspend fun activeGuardian(vehicle: String, phone: String = TEST_PHONE): String {
        val id = post(ApiPaths.GUARDIANS, guardianBody(phone), vehicle).also { it.assertOk(HttpStatusCode.Created) }.json.str("guardian_id")
        post("${ApiPaths.GUARDIANS}/$id/confirm", """{"code":"${sender.lastConsentCode()}"}""", vehicle).assertOk()
        return id
    }

    fun alertBody(alertId: String, source: String = "simulated", occurredAt: Long = clock.nowMs(), ignored: Int = 620, reason: String = "glucose_stop_ignored"): String = buildJsonObject {
        put("alert_id", alertId)
        put("reason", reason)
        put("occurred_at", ApiTime.format(occurredAt))
        put("ignored_for_sec", ignored)
        put("glucose_source", source)
    }.toString()

    suspend fun rows(table: String): Int = app.db.tx { count("SELECT COUNT(*) FROM $table") }
}

fun newDbDir(): Path = Files.createTempDirectory("cd-server-test")

/** 실제 SQLite 파일과 실제 라우팅을 쓰고 시계와 발송기만 바꾼다. dir을 넘기면 재시작을 흉내 낼 수 있다. */
fun serverTest(
    dir: Path = newDbDir(),
    now: Long = BASE_MS,
    allowPartner: Boolean = false,
    sender: RecordingSender = RecordingSender(),
    block: suspend TestEnv.() -> Unit,
) = testApplication {
    val clock = ManualClock(now)
    val config = Config(
        dbPath = dir.resolve("test.db"),
        hmacKey = ByteArray(32) { 7 },
        allowPartnerRealtime = allowPartner,
    )
    val app = ServerApp(config, clock, sender)
    application { conditionDriveModule(app) }
    try {
        TestEnv(app, clock, sender, client).block()
    } finally {
        app.close()
    }
}
