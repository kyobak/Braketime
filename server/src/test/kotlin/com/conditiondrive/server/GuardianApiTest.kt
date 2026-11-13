package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.voice.ForbiddenWords
import com.conditiondrive.server.routes.ApiCodes
import com.conditiondrive.server.sms.LogOnlySender
import com.conditiondrive.server.sms.OutboundSms
import com.conditiondrive.server.sms.OutboxFileSender
import com.conditiondrive.server.sms.SendResult
import com.conditiondrive.server.sms.SmsRenderer
import com.conditiondrive.server.sms.SmsTemplate
import com.conditiondrive.server.sms.SmsVars
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** S17, S18, 12.13, 12.14 */
class GuardianApiTest {
    private val unprocessable = HttpStatusCode.UnprocessableEntity
    private fun alertId() = UUID.randomUUID().toString()
    private val guardians = ApiPaths.GUARDIANS
    private val alerts = ApiPaths.GUARDIAN_ALERTS

    private suspend fun TestEnv.register(token: String, phone: String = TEST_PHONE) =
        post(guardians, guardianBody(phone), token)

    @Test fun registerShape() = serverTest {
        val vehicle = vehicleToken()
        val res = register(vehicle)
        res.assertOk(HttpStatusCode.Created)
        val json = res.json
        assertTrue(json.str("guardian_id").startsWith("grd_"))
        assertEquals("pending_guardian_consent", json.str("status"))
        assertEquals("+82******0001", json.str("phone_masked"))
        assertEquals(ApiTime.format(BASE_MS + 10 * MIN), json.str("consent_expires_at"))
        assertEquals("logged", json.str("delivery"))
        assertFalse(TEST_PHONE in res.text)
        assertEquals(1, sender.sent.size)
        assertEquals(SmsTemplate.GUARDIAN_CONSENT_V1, sender.sent[0].template)
    }

    @Test fun phoneCanRegisterToo() = serverTest {
        val p = pair()
        register(p.phone).assertOk(HttpStatusCode.Created)
    }

    @Test fun registerValidation() = serverTest {
        val vehicle = vehicleToken()
        listOf("+82100000000", "+8210000000011", "01000000000", "+821100000000", "+1210000000", "").forEach {
            post(guardians, guardianBody(phone = it), vehicle).assertError(unprocessable, "validation_failed")
        }
        listOf("길", "길동길동길동길동길동길", "길동1", "길 동", "<b>", "").forEach {
            post(guardians, guardianBody(name = it), vehicle).assertError(unprocessable, "validation_failed")
        }
        listOf("", "가".repeat(21)).forEach {
            post(guardians, guardianBody(label = it), vehicle).assertError(unprocessable, "validation_failed")
        }
        post(guardians, """{"phone_e164":"$TEST_PHONE"}""", vehicle).assertError(unprocessable, "validation_failed")
        val extra = guardianBody().dropLast(1) + ""","note":"x"}"""
        post(guardians, extra, vehicle).assertError(unprocessable, "unknown_field")
        val badConsent = guardianBody().replace("true", "\"true\"")
        post(guardians, badConsent, vehicle).assertError(unprocessable, "validation_failed")
        assertEquals(0, sender.sent.size)
        assertEquals(0, rows("guardians"))
    }

    /** S17: 운전자 동의 없으면 발송 0회 */
    @Test fun driverConsentIsRequired() = serverTest {
        val vehicle = vehicleToken()
        post(guardians, guardianBody(agreed = false), vehicle).assertError(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
        assertEquals(0, sender.sent.size)
        assertEquals(0, rows("guardians"))
    }

    @Test fun onlyOneGuardianPerAccount() = serverTest {
        val vehicle = vehicleToken()
        register(vehicle).assertOk(HttpStatusCode.Created)
        register(vehicle, "+821000000002").assertError(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_EXISTS)
        assertEquals(1, sender.sent.size)
    }

    @Test fun confirmActivates() = serverTest {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        clock.advance(MIN)
        val json = post("$guardians/$id/confirm", """{"code":"${sender.lastConsentCode()}"}""", vehicle).also { it.assertOk() }.json
        assertEquals("active", json.str("status"))
        assertEquals(id, json.str("guardian_id"))
        assertEquals(ApiTime.format(BASE_MS + MIN), json.str("guardian_consented_at"))
        // 재호출은 같은 결과다
        post("$guardians/$id/confirm", """{"code":"000000"}""", vehicle).assertOk()
    }

    @Test fun confirmLifetimeBoundary() = serverTest {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        val code = sender.lastConsentCode()
        clock.advance(10 * MIN)
        post("$guardians/$id/confirm", """{"code":"$code"}""", vehicle).assertError(HttpStatusCode.Gone, ApiCodes.CONSENT_CODE_EXPIRED)
    }

    @Test fun confirmFiveFailuresInvalidateCode() = serverTest {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        val code = sender.lastConsentCode()
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(Limits.CONSENT_CODE_MAX_FAILURES) {
            post("$guardians/$id/confirm", """{"code":"$wrong"}""", vehicle).assertError(unprocessable, ApiCodes.CONSENT_CODE_INVALID)
        }
        post("$guardians/$id/confirm", """{"code":"$code"}""", vehicle).assertError(HttpStatusCode.Gone, ApiCodes.CONSENT_CODE_EXPIRED)
    }

    @Test fun fourFailuresStillAllowCorrectCode() = serverTest {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        val code = sender.lastConsentCode()
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(Limits.CONSENT_CODE_MAX_FAILURES - 1) {
            post("$guardians/$id/confirm", """{"code":"$wrong"}""", vehicle).assertError(unprocessable, ApiCodes.CONSENT_CODE_INVALID)
        }
        post("$guardians/$id/confirm", """{"code":"$code"}""", vehicle).assertOk()
    }

    @Test fun otherAccountCannotTouchGuardian() = serverTest {
        val a = vehicleToken()
        val b = vehicleToken()
        val id = register(a).json.str("guardian_id")
        val code = sender.lastConsentCode()
        post("$guardians/$id/confirm", """{"code":"$code"}""", b).assertError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
        post("$guardians/$id/resend", token = b).assertError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
        delete("$guardians/$id", b).assertError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
        assertTrue(get(guardians, b).json.isNull("guardian"))
    }

    @Test fun resendBoundaries() = serverTest {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        val firstCode = sender.lastConsentCode()
        val tooSoon = post("$guardians/$id/resend", token = vehicle)
        tooSoon.assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
        assertEquals("60", tooSoon.headers[HttpHeaders.RetryAfter])
        clock.advance(59 * SEC)
        post("$guardians/$id/resend", token = vehicle).assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
        clock.advance(1 * SEC)
        val res = post("$guardians/$id/resend", token = vehicle)
        res.assertOk()
        assertEquals("pending_guardian_consent", res.json.str("status"))
        assertEquals(2, sender.sent.size)

        // 재발송하면 이전 코드는 무효다. 우연히 같으면 건너뛴다
        val newCode = sender.lastConsentCode()
        if (newCode != firstCode) {
            post("$guardians/$id/confirm", """{"code":"$firstCode"}""", vehicle).assertError(unprocessable, ApiCodes.CONSENT_CODE_INVALID)
        }
        post("$guardians/$id/confirm", """{"code":"$newCode"}""", vehicle).assertOk()
        clock.advance(MIN)
        post("$guardians/$id/resend", token = vehicle).assertError(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_NOT_ACTIVE)
    }

    /** 12.14: 계정 3건/24시간 */
    @Test fun consentSmsAccountLimit() = serverTest {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        clock.advance(MIN)
        post("$guardians/$id/resend", token = vehicle).assertOk()
        clock.advance(MIN)
        post("$guardians/$id/resend", token = vehicle).assertOk()
        clock.advance(MIN)
        val res = post("$guardians/$id/resend", token = vehicle)
        res.assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
        assertEquals("300", res.headers[HttpHeaders.RetryAfter], "클라이언트 상한 5분에 맞춘다")
        assertEquals(3, sender.sent.size)
        // 가장 오래된 건이 24시간을 벗어나면 다시 된다
        clock.set(BASE_MS + DAY)
        post("$guardians/$id/resend", token = vehicle).assertOk()
    }

    /** 12.14: 번호 3건/24시간, 다른 계정이어도 합산한다 */
    @Test fun consentSmsNumberLimit() = serverTest {
        repeat(3) {
            register(vehicleToken()).assertOk(HttpStatusCode.Created)
        }
        register(vehicleToken()).assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
        register(vehicleToken(), "+821000000002").assertOk(HttpStatusCode.Created)
        assertEquals(4, sender.sent.size)
    }

    /** 12.14: 전역 50건/일 */
    @Test fun consentSmsGlobalLimit() = serverTest {
        repeat(Limits.CONSENT_SMS_GLOBAL_PER_DAY) { i ->
            if (i > 0 && i % Limits.VEHICLE_REGISTER_PER_HOUR == 0) clock.advance(HOUR + SEC)
            register(vehicleToken(), "+8210%08d".format(i)).assertOk(HttpStatusCode.Created)
        }
        clock.advance(HOUR + SEC)
        register(vehicleToken(), "+821099999999").assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
        assertEquals(Limits.CONSENT_SMS_GLOBAL_PER_DAY, sender.sent.size)
    }

    /** S17: 호출 제한은 재시작 후에도 유지된다 */
    @Test fun consentLimitSurvivesRestart() {
        val dir = newDbDir()
        var vehicle = ""
        var id = ""
        serverTest(dir) {
            vehicle = vehicleToken()
            id = register(vehicle).json.str("guardian_id")
        }
        serverTest(dir) {
            post("$guardians/$id/resend", token = vehicle).assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
            assertEquals(0, sender.sent.size)
        }
    }

    @Test fun senderFailureOnRegisterReturnsSlotAndLeavesNoGuardian() = serverTest {
        val vehicle = vehicleToken()
        sender.failNext = 1
        register(vehicle).assertError(HttpStatusCode.BadGateway, ApiCodes.SENDER_FAILED)
        assertEquals(0, rows("guardians"))
        // 슬롯이 환원돼 재발송 간격에 걸리지 않는다
        register(vehicle).assertOk(HttpStatusCode.Created)
    }

    @Test fun senderExceptionIsTreatedAsFailure() = serverTest {
        val vehicle = vehicleToken()
        sender.throwNext = IllegalStateException("boom")
        register(vehicle).assertError(HttpStatusCode.BadGateway, ApiCodes.SENDER_FAILED)
    }

    @Test fun senderFailureOnResendReturnsSlot() = serverTest {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        clock.advance(MIN)
        sender.failNext = 1
        post("$guardians/$id/resend", token = vehicle).assertError(HttpStatusCode.BadGateway, ApiCodes.SENDER_FAILED)
        post("$guardians/$id/resend", token = vehicle).assertOk()
    }

    @Test fun getIsMaskedAndDeleteDestroysNumber() = serverTest {
        val p = pair()
        assertTrue(get(guardians, p.vehicle).json.isNull("guardian"))
        val id = activeGuardian(p.vehicle)
        val res = get(guardians, p.phone)
        res.assertOk()
        val guardian = res.json.getValue("guardian") as JsonObject
        assertEquals(id, guardian.str("guardian_id"))
        assertEquals("active", guardian.str("status"))
        assertEquals("+82******0001", guardian.str("phone_masked"))
        assertFalse(TEST_PHONE in res.text)

        delete("$guardians/$id", p.vehicle).also { it.assertOk() }.json.let { assertTrue(it.bool("deleted")) }
        delete("$guardians/$id", p.vehicle).assertOk()
        assertTrue(get(guardians, p.vehicle).json.isNull("guardian"))
        val stored = app.db.tx {
            prepareStatement("SELECT phone_e164, guardian_label, driver_display_name, status FROM guardians").use { st ->
                st.executeQuery().use { it.next(); listOf(it.getString(1), it.getString(2), it.getString(3), it.getString(4)) }
            }
        }
        assertEquals(listOf(null, null, "", "revoked"), stored)
        // 삭제 후 다시 등록할 수 있다
        clock.advance(MIN)
        register(p.vehicle, "+821000000002").assertOk(HttpStatusCode.Created)
    }

    @Test fun optOutAlwaysOkAndBlocksFutureRegistration() = serverTest {
        val vehicle = vehicleToken()
        register(vehicle).assertOk(HttpStatusCode.Created)
        val code = sender.lastOptOutCode()

        // 없는 코드도 같은 응답이다. 존재 여부를 알려 주지 않는다
        val unknown = post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"ZZZZZZ"}""")
        unknown.assertOk()
        assertEquals("""{"ok":true}""", unknown.text)

        val res = post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"$code"}""")
        assertEquals(unknown.text, res.text)
        assertEquals(1, rows("sms_suppressions"))

        val guardian = get(guardians, vehicle).json.getValue("guardian") as JsonObject
        assertEquals("opted_out", guardian.str("status"))
        assertTrue(guardian.isNull("phone_masked"))

        val other = vehicleToken()
        register(other).assertError(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_OPTED_OUT)
        // 코드를 다시 보내도 멱등이다
        post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"$code"}""").assertOk()
        assertEquals(1, rows("sms_suppressions"))
    }

    @Test fun optOutSendsNothingAnymore() = serverTest {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val sentBefore = sender.sent.size
        post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"${sender.lastOptOutCode()}"}""").assertOk()
        post(alerts, alertBody(alertId()), vehicle).assertError(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_NOT_ACTIVE)
        assertEquals(sentBefore, sender.sent.size)
    }

    /** 운전자가 삭제한 뒤에도 이미 받은 문자의 거부 코드는 효력이 있어야 한다. */
    @Test fun optOutCodeWorksAfterDriverDeleted() = serverTest {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        val code = sender.lastOptOutCode()
        delete("$guardians/$id", vehicle).assertOk()
        post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"$code"}""").assertOk()
        assertEquals(1, rows("sms_suppressions"))
        clock.advance(MIN)
        register(vehicle).assertError(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_OPTED_OUT)
    }

    /** 문자마다 다른 코드를 주면 먼저 받은 문자의 코드가 조용히 무효가 된다. */
    @Test fun optOutCodeIsStableAcrossMessages() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        val id = register(vehicle).json.str("guardian_id")
        val first = sender.lastOptOutCode()
        clock.advance(MIN)
        post("$guardians/$id/resend", token = vehicle).assertOk()
        assertEquals(first, sender.lastOptOutCode())
        post("$guardians/$id/confirm", """{"code":"${sender.lastConsentCode()}"}""", vehicle).assertOk()
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertOk(HttpStatusCode.Accepted)
        assertEquals(first, sender.lastOptOutCode())
    }

    @Test fun optOutCoversSameNumberOnOtherAccounts() = serverTest {
        val a = vehicleToken()
        val b = vehicleToken()
        register(a).assertOk(HttpStatusCode.Created)
        activeGuardian(b)
        post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"${sender.lastOptOutCode()}"}""").assertOk()
        listOf(a, b).forEach {
            assertEquals("opted_out", (get(guardians, it).json.getValue("guardian") as JsonObject).str("status"))
        }
    }

    @Test fun suppressionSurvivesAccountDeletion() = serverTest {
        val vehicle = vehicleToken()
        register(vehicle).assertOk(HttpStatusCode.Created)
        post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"${sender.lastOptOutCode()}"}""").assertOk()
        delete(ApiPaths.ACCOUNT, vehicle).assertOk()
        assertEquals(1, rows("sms_suppressions"))
        register(vehicleToken()).assertError(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_OPTED_OUT)
    }

    @Test fun optOutRateLimitStillRespondsNotSilently() = serverTest {
        repeat(Limits.OPT_OUT_PER_MIN) { post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"ZZZZZZ"}""").assertOk() }
        val res = post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"ZZZZZZ"}""")
        res.assertError(HttpStatusCode.TooManyRequests, ApiCodes.RATE_LIMITED)
        clock.advance(MIN)
        post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"ZZZZZZ"}""").assertOk()
    }

    // --- 12.14 알림 ---

    /** S17: simulated면 발송기 호출 0회 */
    @Test fun simulatedAlertNeverCallsSender() = serverTest {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val before = sender.sent.size
        val id = alertId()
        val res = post(alerts, alertBody(id), vehicle)
        res.assertOk(HttpStatusCode.Accepted)
        assertEquals("suppressed_simulated", res.json.str("delivery"))
        assertEquals(id, res.json.str("alert_id"))
        assertEquals(before, sender.sent.size)
    }

    @Test fun simulatedLimitOnePerMinuteAndNotCountedAgainstReal() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        post(alerts, alertBody(alertId()), vehicle).assertOk(HttpStatusCode.Accepted)
        clock.advance(59 * SEC)
        post(alerts, alertBody(alertId()), vehicle).assertError(HttpStatusCode.TooManyRequests, ApiCodes.ALERT_RATE_LIMITED)
        clock.advance(1 * SEC)
        post(alerts, alertBody(alertId()), vehicle).assertOk(HttpStatusCode.Accepted)
        // 시뮬레이션이 실제 쿨다운을 쓰지 않는다
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertOk(HttpStatusCode.Accepted)
    }

    @Test fun partnerAlertRejectedByDefault() = serverTest {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val before = sender.sent.size
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertError(unprocessable, "glucose_source_not_allowed")
        assertEquals(before, sender.sent.size)
    }

    @Test fun realAlertSendsTemplateOnly() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val res = post(alerts, alertBody(alertId(), "partner_realtime"), vehicle)
        res.assertOk(HttpStatusCode.Accepted)
        assertEquals("logged", res.json.str("delivery"))
        val sms = sender.sent.last()
        assertEquals(SmsTemplate.GUARDIAN_ALERT_V1, sms.template)
        assertEquals(TEST_PHONE, sms.toE164)
        assertEquals("길동", sms.vars.driverName)
    }

    @Test fun alertRetransmitIsIdempotent() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val id = alertId()
        val body = alertBody(id, "partner_realtime")
        val first = post(alerts, body, vehicle)
        first.assertOk(HttpStatusCode.Accepted)
        val sentAfterFirst = sender.sent.size

        // 재전송은 occurred_at이 오래돼도, 쿨다운 안이어도 같은 결과다
        clock.advance(10 * MIN)
        val second = post(alerts, body, vehicle)
        second.assertOk()
        assertEquals(first.json.str("alert_id"), second.json.str("alert_id"))
        assertEquals(first.json.str("delivery"), second.json.str("delivery"))
        assertEquals(sentAfterFirst, sender.sent.size)
    }

    @Test fun simulatedRetransmitIsIdempotent() = serverTest {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val body = alertBody(alertId())
        post(alerts, body, vehicle).assertOk(HttpStatusCode.Accepted)
        val again = post(alerts, body, vehicle)
        again.assertOk()
        assertEquals("suppressed_simulated", again.json.str("delivery"))
    }

    @Test fun sameAlertIdOnOtherAccountIsIndependent() = serverTest {
        val a = vehicleToken()
        val b = vehicleToken()
        activeGuardian(a)
        clock.advance(MIN)
        activeGuardian(b, "+821000000002")
        val id = alertId()
        post(alerts, alertBody(id), a).assertOk(HttpStatusCode.Accepted)
        post(alerts, alertBody(id), b).assertOk(HttpStatusCode.Accepted)
    }

    @Test fun alertValidation() = serverTest {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        post(alerts, alertBody(alertId(), reason = "fatigue"), vehicle).assertError(unprocessable, "validation_failed")
        post(alerts, alertBody(alertId(), ignored = 599), vehicle).assertError(unprocessable, "validation_failed")
        post(alerts, alertBody("not-uuid"), vehicle).assertError(unprocessable, "validation_failed")
        post(alerts, alertBody(alertId(), source = "health_connect"), vehicle).assertError(unprocessable, "validation_failed")
        post(alerts, alertBody(alertId()).replace(ApiTime.format(clock.nowMs()), "soon"), vehicle).assertError(unprocessable, "validation_failed")
        // 문자에 들어갈 수 없는 값은 요청에도 들어올 수 없다
        val withValue = alertBody(alertId()).dropLast(1) + ""","glucose_mmol":3.1}"""
        post(alerts, withValue, vehicle).assertError(unprocessable, "unknown_field")
    }

    @Test fun staleAlertBoundaries() = serverTest {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val now = clock.nowMs()
        post(alerts, alertBody(alertId(), occurredAt = now - 301 * SEC), vehicle).assertError(unprocessable, ApiCodes.STALE_ALERT)
        post(alerts, alertBody(alertId(), occurredAt = now + 61 * SEC), vehicle).assertError(unprocessable, ApiCodes.STALE_ALERT)
        post(alerts, alertBody(alertId(), occurredAt = now - 300 * SEC), vehicle).assertOk(HttpStatusCode.Accepted)
        clock.advance(MIN)
        val t = clock.nowMs()
        post(alerts, alertBody(alertId(), occurredAt = t + 60 * SEC), vehicle).assertOk(HttpStatusCode.Accepted)
    }

    /** S17: 양쪽 동의 없으면 발송 0회 */
    @Test fun alertNeedsActiveGuardian() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        // 등록 전: 동의도 없다
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertError(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
        // 운전자만 동의, 보호자 미동의
        register(vehicle).assertOk(HttpStatusCode.Created)
        val before = sender.sent.size
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertError(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_NOT_ACTIVE)
        post(alerts, alertBody(alertId()), vehicle).assertError(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_NOT_ACTIVE)
        assertEquals(before, sender.sent.size)
    }

    @Test fun withdrawnDriverConsentBlocksAlert() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val before = sender.sent.size
        delete("${ApiPaths.CONSENTS}/guardian_alert", vehicle).assertOk()
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertError(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
        assertEquals(before, sender.sent.size)
    }

    /** 12.14: 쿨다운 30분, 계정 3건/24시간 */
    @Test fun alertCooldownAndAccountLimit() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertOk(HttpStatusCode.Accepted)
        clock.advance(30 * MIN - 1)
        val cooling = post(alerts, alertBody(alertId(), "partner_realtime"), vehicle)
        cooling.assertError(HttpStatusCode.TooManyRequests, ApiCodes.ALERT_RATE_LIMITED)
        assertEquals("1", cooling.headers[HttpHeaders.RetryAfter])
        clock.advance(1)
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertOk(HttpStatusCode.Accepted)
        clock.advance(30 * MIN)
        post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertOk(HttpStatusCode.Accepted)
        clock.advance(30 * MIN)
        val capped = post(alerts, alertBody(alertId(), "partner_realtime"), vehicle)
        capped.assertError(HttpStatusCode.TooManyRequests, ApiCodes.ALERT_RATE_LIMITED)
        assertEquals("300", capped.headers[HttpHeaders.RetryAfter])
    }

    /** 12.14: 번호 5건/24시간. 계정 3건 한도를 피해 두 계정으로 채운다 */
    @Test fun alertNumberLimit() = serverTest(allowPartner = true) {
        val a = vehicleToken()
        val b = vehicleToken()
        activeGuardian(a)
        clock.advance(MIN)
        activeGuardian(b)
        val before = sender.sent.size
        var sent = 0
        repeat(3) {
            post(alerts, alertBody(alertId(), "partner_realtime"), a).assertOk(HttpStatusCode.Accepted); sent++
            if (sent < Limits.ALERT_NUMBER_PER_DAY) {
                post(alerts, alertBody(alertId(), "partner_realtime"), b).assertOk(HttpStatusCode.Accepted); sent++
            }
            clock.advance(31 * MIN)
        }
        assertEquals(5, sent)
        post(alerts, alertBody(alertId(), "partner_realtime"), b).assertError(HttpStatusCode.TooManyRequests, ApiCodes.ALERT_RATE_LIMITED)
        assertEquals(before + 5, sender.sent.size)
    }

    /** S17: 재시작 후 유지 */
    @Test fun alertCooldownSurvivesRestart() {
        val dir = newDbDir()
        var vehicle = ""
        serverTest(dir, allowPartner = true) {
            vehicle = vehicleToken()
            activeGuardian(vehicle)
            post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertOk(HttpStatusCode.Accepted)
        }
        serverTest(dir, allowPartner = true) {
            clock.advance(29 * MIN)
            post(alerts, alertBody(alertId(), "partner_realtime"), vehicle).assertError(HttpStatusCode.TooManyRequests, ApiCodes.ALERT_RATE_LIMITED)
            assertEquals(0, sender.sent.size)
        }
    }

    /** 같은 alert_id가 동시에 와도 문자는 한 번만 나간다. */
    @Test fun concurrentSameAlertSendsOnce() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val before = sender.sent.size
        val body = alertBody(alertId(), "partner_realtime")
        val results = kotlinx.coroutines.coroutineScope {
            List(8) { async(kotlinx.coroutines.Dispatchers.Default) { post(alerts, body, vehicle) } }.awaitAll()
        }
        assertEquals(before + 1, sender.sent.size)
        assertEquals(1, results.count { it.status == HttpStatusCode.Accepted })
        assertEquals(7, results.count { it.status == HttpStatusCode.OK })
    }

    /** 12.14: 발송기 실패 → 502, 슬롯 환원, 같은 alert_id로 재시도 */
    @Test fun senderFailureReturnsSlotAndRetryWorks() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val before = sender.sent.size
        val id = alertId()
        sender.failNext = 1
        post(alerts, alertBody(id, "partner_realtime"), vehicle).assertError(HttpStatusCode.BadGateway, ApiCodes.SENDER_FAILED)
        assertEquals(before, sender.sent.size)
        val retry = post(alerts, alertBody(id, "partner_realtime"), vehicle)
        retry.assertOk(HttpStatusCode.Accepted)
        assertEquals(before + 1, sender.sent.size)
    }

    /** 발송 중 끊겨 pending으로 남은 알림을 같은 ID로 재전송하면 자기 슬롯에 막히지 않는다. */
    @Test fun pendingAlertCanBeRetransmitted() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        val id = alertId()
        sender.failNext = 1
        post(alerts, alertBody(id, "partner_realtime"), vehicle).assertError(HttpStatusCode.BadGateway, ApiCodes.SENDER_FAILED)
        app.db.tx { prepareStatement("UPDATE guardian_messages SET status = 'pending' WHERE status = 'failed'").use { it.executeUpdate() } }
        post(alerts, alertBody(id, "partner_realtime"), vehicle).assertOk(HttpStatusCode.Accepted)
    }

    // --- S18 문구 ---

    private val consentSms = OutboundSms(TEST_PHONE, SmsTemplate.GUARDIAN_CONSENT_V1, SmsVars("길동", "482913", "9XK2M4"), "k")
    private val alertSms = OutboundSms(TEST_PHONE, SmsTemplate.GUARDIAN_ALERT_V1, SmsVars("길동", optOutCode = "9XK2M4"), "k")

    @Test fun templatesMatchDesignText() {
        assertEquals(
            "[Braketime] 길동님이 운전 중 휴식 권고 알림 수신자로 등록하려 합니다. 동의하시면 확인 코드 482913를 길동님께 알려 주세요. 원치 않으면 무시하세요. 수신 거부 코드 9XK2M4",
            SmsRenderer.render(consentSms),
        )
        assertEquals(
            "[Braketime] 길동님의 차량 앱이 정차를 권고했지만 주행이 이어지고 있어요. 안전한 때에 연락해 확인해 주세요. 의료 판단이 아닙니다. 수신 거부 코드 9XK2M4",
            SmsRenderer.render(alertSms),
        )
    }

    @Test fun templatesHaveNoForbiddenWordsOrHealthValues() {
        listOf(consentSms, alertSms).forEach { sms ->
            val text = SmsRenderer.render(sms)
            assertEquals(emptyList(), ForbiddenWords.find(text), text)
            assertFalse("혈당" in text, text)
            assertFalse("mmol" in text, text)
            assertFalse("수면" in text, text)
        }
    }

    /** 라벨과 요청의 다른 값은 문자에 들어갈 경로가 없다. */
    @Test fun smsCarriesOnlyTemplateVars() = serverTest(allowPartner = true) {
        val vehicle = vehicleToken()
        val id = post(guardians, guardianBody(label = "라벨카나리"), vehicle).json.str("guardian_id")
        post("$guardians/$id/confirm", """{"code":"${sender.lastConsentCode()}"}""", vehicle).assertOk()
        post(alerts, alertBody(alertId(), "partner_realtime", ignored = 777), vehicle).assertOk(HttpStatusCode.Accepted)
        sender.sent.forEach {
            val text = SmsRenderer.render(it)
            assertFalse("라벨카나리" in text)
            assertFalse("777" in text)
        }
    }

    @Test fun logOnlySenderReportsLogged() = runBlocking {
        val result = LogOnlySender().send(alertSms)
        assertEquals(SendResult.Accepted("logged", null), result)
    }

    @Test fun outboxWritesOwnerOnlyFileWithMaskedNumber() = runBlocking {
        val file = newDbDir().resolve("outbox/sms.log")
        val result = OutboxFileSender(file).send(consentSms)
        assertIs<SendResult.Accepted>(result)
        val text = Files.readString(file)
        assertTrue("482913" in text)
        assertFalse(TEST_PHONE in text)
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
    }
}
