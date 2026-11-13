package com.conditiondrive.server

import com.conditiondrive.server.db.queryOne
import com.conditiondrive.server.auth.Role
import com.conditiondrive.server.auth.DeviceAuth
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.server.routes.ApiCodes
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsentApiTest {
    private val health = "${ApiPaths.CONSENTS}/health_summary"
    private val alert = "${ApiPaths.CONSENTS}/guardian_alert"

    @Test fun grantResponse() = serverTest {
        val p = pair()
        val json = put(health, """{"version":"2026-12-v2"}""", p.phone).also { it.assertOk() }.json
        assertEquals("health_summary", json.str("type"))
        assertEquals("2026-12-v2", json.str("version"))
        assertEquals(ApiTime.format(BASE_MS), json.str("granted_at"))
    }

    @Test fun unknownTypeIsNotFound() = serverTest {
        val p = pair()
        put("${ApiPaths.CONSENTS}/marketing", """{"version":"v"}""", p.phone).assertError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
        delete("${ApiPaths.CONSENTS}/marketing", p.phone).assertError(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
    }

    @Test fun versionValidation() = serverTest {
        val p = pair()
        val unprocessable = HttpStatusCode.UnprocessableEntity
        put(health, """{"version":""}""", p.phone).assertError(unprocessable, "validation_failed")
        put(health, """{"version":1}""", p.phone).assertError(unprocessable, "validation_failed")
        put(health, """{}""", p.phone).assertError(unprocessable, "validation_failed")
        put(health, """{"version":"v","x":1}""", p.phone).assertError(unprocessable, "unknown_field")
    }

    @Test fun regrantRestoresUpload() = serverTest {
        val p = pair()
        delete(health, p.phone).assertOk()
        upload(p.phone).assertError(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
        put(health, """{"version":"v2"}""", p.phone).assertOk()
        upload(p.phone).assertOk()
    }

    /** 12.10: 차량 토큰도 철회할 수 있는 API 능력. 차량 화면에는 두지 않고 철회는 폰에서 한다(U-31). 철회 즉시 요약 삭제. */
    @Test fun vehicleCanWithdrawHealthConsent() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        val json = delete(health, p.vehicle).also { it.assertOk() }.json
        assertTrue(json.bool("withdrawn"))
        assertTrue(json.bool("data_deleted"))
        assertEquals(0, rows("condition_summaries"))
        upload(p.phone).assertError(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
    }

    @Test fun withdrawIsIdempotent() = serverTest {
        val p = pair()
        repeat(2) { delete(health, p.phone).assertOk() }
        repeat(2) { delete(alert, p.vehicle).assertOk() }
    }

    @Test fun guardianConsentWithdrawalDeletesGuardian() = serverTest {
        val p = pair()
        val id = activeGuardian(p.vehicle)
        delete(alert, p.phone).assertOk()
        assertTrue(get(ApiPaths.GUARDIANS, p.vehicle).json.isNull("guardian"))
        val phone = app.db.tx {
            prepareStatement("SELECT phone_e164, guardian_label FROM guardians WHERE id = ?").use { st ->
                st.setString(1, id)
                st.executeQuery().use { it.next(); it.getString(1) to it.getString(2) }
            }
        }
        assertEquals(null to null, phone)
        // 건강 요약에는 영향이 없다
        upload(p.phone).assertOk()
    }

    @Test fun healthConsentWithdrawalKeepsGuardianConsent() = serverTest {
        val p = pair()
        activeGuardian(p.vehicle)
        delete(health, p.phone).assertOk()
        assertEquals("active", (get(ApiPaths.GUARDIANS, p.vehicle).json.getValue("guardian") as kotlinx.serialization.json.JsonObject).str("status"))
    }

    @Test fun guardianConsentGrantFromEitherDevice() = serverTest {
        val p = pair()
        put(alert, """{"version":"v"}""", p.vehicle).assertOk()
        put(alert, """{"version":"v"}""", p.phone).assertOk()
    }

    /**
     * POST가 동의 확인을 지나 본문을 읽는 동안 철회가 커밋되는 경우다. 라우트를 건너 저장 단계만 부른다.
     * 트랜잭션 안에서 다시 보지 않으면 철회 뒤에 값이 유효 시각까지 되살아난다.
     */
    @Test fun uploadAfterWithdrawInFlightDoesNotRestoreValue() = serverTest {
        val p = pair()
        upload(p.phone).assertOk()
        val phoneId = app.db.tx { queryOne("SELECT id FROM devices WHERE role = 'phone' AND revoked_at IS NULL") { it.getString("id") } }!!
        val auth = DeviceAuth(phoneId, p.accountId, Role.PHONE)
        val inFlight = ContractJson.Strict.decodeFromString(SummaryDto.serializer(), summary())
        delete(health, p.phone).assertOk()
        val response = app.summaries.upload(auth, inFlight)
        assertEquals(false, response.applied)
        assertEquals("not_allowed", response.reason)
        assertEquals(0, rows("condition_summaries"))
    }

    @Test fun uploadAfterUnpairInFlightDoesNotRestoreValue() = serverTest {
        val p = pair()
        val phoneId = app.db.tx { queryOne("SELECT id FROM devices WHERE role = 'phone' AND revoked_at IS NULL") { it.getString("id") } }!!
        val auth = DeviceAuth(phoneId, p.accountId, Role.PHONE)
        val inFlight = ContractJson.Strict.decodeFromString(SummaryDto.serializer(), summary())
        delete(ApiPaths.PAIRING, p.vehicle).assertOk()
        assertEquals(false, app.summaries.upload(auth, inFlight).applied)
        assertEquals(0, rows("condition_summaries"))
    }
}
