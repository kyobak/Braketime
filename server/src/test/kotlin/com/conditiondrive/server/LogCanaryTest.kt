package com.conditiondrive.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.conditiondrive.core.contract.ApiPaths
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S14, 12.17. 운영 설정(logback.xml) 그대로 루트 Appender에 걸리는 모든 줄을 본다. */
class LogCanaryTest {
    private val glucose = "3.1415"
    private val sleep = "271"
    private val phone = "+821099990000"
    private val textCanary = "CANARY-TEXT-7f3a"

    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeTest fun attach() {
        appender.start()
        root.addAppender(appender)
    }

    @AfterTest fun detach() {
        root.detachAppender(appender)
        appender.stop()
    }

    private fun logged(): String = synchronized(appender) {
        appender.list.toList().joinToString("\n") { e ->
            buildString {
                append(e.loggerName).append(' ').append(e.formattedMessage)
                e.throwableProxy?.let { append(' ').append(it.className).append(' ').append(it.message) }
            }
        }
    }

    private fun assertClean(vararg secrets: String, extra: List<String> = emptyList()) {
        // 무작위 요청 ID(Crockford 8자)와 계정 줄임값(16진 8자)에 숫자 카나리아가 우연히 들어가면("req_KS271SQH") 깨끗한 로그도 실패한다
        val text = logged().replace(Regex("""req_[0-9A-Z]{8}"""), "req_ID").replace(Regex("""acct=[0-9a-f]{8}\b"""), "acct=ID")
        assertTrue(text.isNotBlank(), "로그를 하나도 잡지 못하면 검사가 무의미하다")
        (secrets.toList() + extra).forEach { assertFalse(it in text, "로그에 금지 값: $it\n$text") }
    }

    @Test fun normalPaths() = serverTest(allowPartner = true) {
        val vehicleJson = registerVehicle().json
        val vehicle = vehicleJson.str("vehicle_token")
        val code = newCode(vehicle)
        val claimed = claim(code, name = textCanary).json
        val phoneToken = claimed.str("phone_token")

        clock.advance(4 * SEC)
        val body = summary {
            // 3.1415는 소수 둘째 자리 규칙에 걸려 정상 경로에 쓸 수 없다. 실패 경로 테스트가 그 값을 쓴다
            this["glucose_mmol"] = JsonPrimitive(3.14)
            this["sleep_minutes_last_night"] = JsonPrimitive(sleep.toInt())
        }
        upload(phoneToken, body).assertOk()
        get(ApiPaths.SUMMARY_LATEST, vehicle).assertOk()
        post(ApiPaths.TRIPS, trip(), vehicle).assertOk(HttpStatusCode.Created)

        val register = post(ApiPaths.GUARDIANS, guardianBody(phone), vehicle)
        register.assertOk(HttpStatusCode.Created)
        val guardianId = register.json.str("guardian_id")
        val consentCode = sender.lastConsentCode()
        post("${ApiPaths.GUARDIANS}/$guardianId/confirm", """{"code":"$consentCode"}""", vehicle).assertOk()
        val optOut = sender.lastOptOutCode()
        post(ApiPaths.GUARDIAN_ALERTS, alertBody(UUID.randomUUID().toString(), "partner_realtime"), vehicle).assertOk(HttpStatusCode.Accepted)

        val cut = java.net.URLEncoder.encode(ApiTime.format(clock.nowMs() - DAY), "UTF-8")
        delete("${ApiPaths.TRIPS}?before=$cut", vehicle).assertOk()

        assertClean(
            vehicle, phoneToken, code, consentCode, optOut, phone, textCanary, guardianId, cut,
            vehicleJson.str("account_id"), claimed.str("device_id"), "\"glucose_mmol\"", "3.14", "\"sleep_minutes",
        )
    }

    @Test fun failurePathsNeverLogOrEchoInput() = serverTest {
        val p = pair()
        val responses = mutableListOf<Res>()
        // 값 위반, 거부 키, 원본 배열, 깨진 JSON, 크기 초과
        responses += upload(p.phone, summary { this["glucose_mmol"] = JsonPrimitive(glucose.toDouble()) })
        responses += upload(p.phone, summary { this["sleep_minutes_last_night"] = JsonPrimitive(1441 + sleep.toInt()) })
        responses += upload(p.phone, summary { this["samples"] = JsonPrimitive(glucose) })
        responses += upload(p.phone, """{"glucose_mmol": $glucose, "note": "$textCanary" oops""")
        responses += upload(p.phone, """{"glucose_mmol": "$glucose", "$textCanary": "$phone"}""")
        responses += upload(p.phone, "\"$textCanary\"" + " ".repeat(Limits.BODY_SUMMARY))
        // 잘못된 토큰, 쿼리스트링, 경로
        responses += get(ApiPaths.SUMMARY_LATEST, "cdv_$textCanary")
        responses += get("/v1/$textCanary?phone=$textCanary")
        responses += delete("${ApiPaths.TRIPS}?before=$textCanary", p.vehicle)
        responses += post("${ApiPaths.GUARDIANS}/$textCanary/confirm", """{"code":"$textCanary"}""", p.vehicle)
        responses += post(ApiPaths.GUARDIANS, guardianBody(phone = "${phone}0"), p.vehicle)
        responses += post(ApiPaths.GUARDIANS, guardianBody(phone = phone, name = textCanary), p.vehicle)
        responses += claim(textCanary, name = textCanary)
        responses += call(HttpMethod.Post, ApiPaths.PAIRING_CLAIM, "not json $textCanary")

        responses.forEach { res ->
            assertTrue(res.status.value >= 400, res.text)
            listOf(textCanary, phone, glucose).forEach { assertFalse(it in res.text, "응답에 입력 값: $it\n${res.text}") }
        }
        assertClean(textCanary, phone, glucose, p.vehicle, p.phone, "271", "1712")
    }

    @Test fun exceptionPathsLogOnlyClassName() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        // 발송기 예외 메시지에 값이 들어 있는 경우
        sender.throwNext = IllegalStateException("send failed $phone $textCanary")
        post(ApiPaths.GUARDIANS, guardianBody(phone), vehicle).assertError(HttpStatusCode.BadGateway, "sender_failed")
        assertTrue("sms_failed" in logged() && "IllegalStateException" in logged())

        // DB 장애는 SQLException 메시지와 무관하게 클래스 이름만 남는다
        val phoneToken = pair().phone
        app.db.close()
        val res = upload(phoneToken, summary { this["glucose_mmol"] = JsonPrimitive(3.14) })
        res.assertError(HttpStatusCode.ServiceUnavailable, "unavailable")
        assertTrue("db_error" in logged())
        assertClean(phone, textCanary, phoneToken, "\"glucose_mmol\"", "SELECT", "stmt")
    }

    @Test fun accessLineHasOnlyAllowedFields() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        get("/v1/secret/path?x=1")
        get(ApiPaths.SUMMARY_LATEST, "bad")
        val access = synchronized(appender) { appender.list.filter { it.loggerName == "cd.access" }.map { it.formattedMessage } }
        assertTrue(access.size >= 5, "$access")
        val allowed = Regex("""req_[0-9A-Z]{8} (GET|POST|PUT|DELETE) (/[\w/{}-]*|unmatched) \d{3} \d+ acct=([0-9a-f]{8}|-) role=(vehicle|phone|-)""")
        access.forEach { assertTrue(allowed.matches(it), it) }
        assertTrue(access.any { "POST /v1/summary 200" in it && "role=phone" in it }, "$access")
        assertTrue(access.any { "GET unmatched 404" in it }, "$access")
        assertFalse(access.any { "secret" in it })
    }

    @Test fun routeTemplateNotRealPathValue() = serverTest {
        val vehicle = vehicleToken()
        val id = post(ApiPaths.GUARDIANS, guardianBody(), vehicle).json.str("guardian_id")
        delete("${ApiPaths.GUARDIANS}/$id", vehicle).assertOk()
        val text = logged()
        assertFalse(id in text, text)
        assertTrue("DELETE /v1/guardians/{id} 200" in text, text)
    }

    @Test fun logOnlySenderLogsMaskOnly() = serverTest {
        val sender = com.conditiondrive.server.sms.LogOnlySender()
        sender.send(
            com.conditiondrive.server.sms.OutboundSms(
                phone,
                com.conditiondrive.server.sms.SmsTemplate.GUARDIAN_CONSENT_V1,
                com.conditiondrive.server.sms.SmsVars(textCanary, "482913", "9XK2M4"),
                "key",
            ),
        )
        val text = logged()
        assertTrue("sms_logged to=+82******0000 template=GUARDIAN_CONSENT_V1" in text, text)
        assertClean(phone, textCanary, "482913", "9XK2M4")
    }
}
