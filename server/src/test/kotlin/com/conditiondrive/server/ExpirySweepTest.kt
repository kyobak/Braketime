package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.server.db.count
import com.conditiondrive.server.routes.ApiCodes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S13, 12.16. Clock 주입으로 실제 시간 없이 확인한다. */
class ExpirySweepTest {
    private suspend fun TestEnv.payload(): String = app.db.tx {
        prepareStatement("SELECT payload_json FROM condition_summaries").use { st -> st.executeQuery().use { it.next(); it.getString(1) } }
    }

    @Test fun summaryDeletedOnlyAfterBothExpire() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_full.json")).assertOk()
        val validUntil = ms("2026-11-20T08:26:30+09:00")
        val sleepUntil = ms("2026-11-21T04:00:00+09:00")

        clock.set(validUntil - 1)
        app.sweeper.sweepOnce()
        assertTrue("\"glucose_mmol\":5.4" in payload())

        // 혈당 만료: 행은 남고 값은 저장소에서도 지워진다
        clock.set(validUntil)
        app.sweeper.sweepOnce()
        val masked = payload()
        assertFalse("5.4" in masked, masked)
        assertTrue("\"glucose_mmol\":null" in masked, masked)
        assertTrue("\"sleep_minutes_last_night\":290" in masked, masked)

        clock.set(sleepUntil - 1)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("condition_summaries"))

        clock.set(sleepUntil)
        app.sweeper.sweepOnce()
        assertEquals(0, rows("condition_summaries"))
    }

    @Test fun sleepExpiredFirstIsErasedFromStorage() = serverTest {
        val p = pair()
        val now = clock.nowMs()
        upload(p.phone, summary(now) { this["sleep_valid_until"] = JsonPrimitive(ApiTime.format(now + 10 * SEC)) }).assertOk()
        clock.advance(10 * SEC)
        app.sweeper.sweepOnce()
        val stored = payload()
        assertTrue("\"sleep_minutes_last_night\":null" in stored, stored)
        assertTrue("\"glucose_mmol\":5.4" in stored, stored)
    }

    @Test fun glucoseOnlySummaryDeletedAtValidUntil() = serverTest {
        val p = pair()
        upload(p.phone, fixture("summary_glucose_only.json")).assertOk()
        clock.set(ms("2026-11-20T08:26:30+09:00"))
        app.sweeper.sweepOnce()
        assertEquals(0, rows("condition_summaries"))
    }

    /** 14장 "이력 없음", 12.15 secure_delete: 지운 값이 파일에 남지 않는다. */
    @Test fun deletedValuesLeaveNoBytesInDatabaseFiles() = serverTest {
        val p = pair()
        val now = clock.nowMs()
        upload(p.phone, summary(now) { this["glucose_mmol"] = JsonPrimitive(3.14) }).assertOk()
        clock.advance(30 * SEC)
        // 교체된 이전 값
        upload(p.phone, summary { this["glucose_mmol"] = JsonPrimitive(2.71) }).assertOk()
        app.db.checkpoint()
        assertFalse(databaseBytesContain("\"glucose_mmol\":3.14"), "교체된 값이 남았다")
        assertTrue(databaseBytesContain("\"glucose_mmol\":2.71"), "검사 방법 확인: 현재 값은 보여야 한다")

        delete(ApiPaths.SUMMARY, p.phone).assertOk()
        app.db.checkpoint()
        assertFalse(databaseBytesContain("\"glucose_mmol\":2.71"), "삭제된 값이 남았다")
    }

    private fun TestEnv.databaseBytesContain(text: String): Boolean {
        val parent = app.config.dbPath.parent
        return Files.list(parent).use { files ->
            files.toList().any { String(Files.readAllBytes(it), Charsets.ISO_8859_1).contains(text) }
        }
    }

    @Test fun pairingCodesPurgedOneHourAfterExpiry() = serverTest {
        val vehicle = vehicleToken()
        val code = newCode(vehicle)
        clock.advance(300 * SEC + HOUR - 1)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("pairing_codes"))
        // 지워지기 전까지는 만료로 안내한다
        claim(code).assertError(HttpStatusCode.Gone, ApiCodes.PAIRING_CODE_EXPIRED)
        clock.advance(1)
        app.sweeper.sweepOnce()
        assertEquals(0, rows("pairing_codes"))
        claim(code).assertError(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID)
    }

    @Test fun pendingGuardianPurgedAfter24Hours() = serverTest {
        val vehicle = vehicleToken()
        post(ApiPaths.GUARDIANS, guardianBody(), vehicle).assertOk(HttpStatusCode.Created)
        keepAlive(vehicle, DAY - 1)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("guardians"))
        clock.advance(1)
        app.sweeper.sweepOnce()
        assertEquals(0, rows("guardians"))
    }

    @Test fun activeGuardianIsNotPurged() = serverTest {
        val vehicle = vehicleToken()
        activeGuardian(vehicle)
        keepAlive(vehicle, 2 * DAY)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("guardians"))
    }

    @Test fun endedGuardianRowPurgedAfterRetention() = serverTest {
        val vehicle = vehicleToken()
        val id = post(ApiPaths.GUARDIANS, guardianBody(), vehicle).json.str("guardian_id")
        delete("${ApiPaths.GUARDIANS}/$id", vehicle).assertOk()
        keepAlive(vehicle, 30 * DAY - 1)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("guardians"))
        clock.advance(1)
        get(ApiPaths.SUMMARY_LATEST, vehicle).assertOk()
        app.sweeper.sweepOnce()
        assertEquals(0, rows("guardians"))
    }

    @Test fun tripsPurgedAfter30Days() = serverTest {
        val vehicle = vehicleToken()
        post(ApiPaths.TRIPS, trip(), vehicle).assertOk(HttpStatusCode.Created)
        keepAlive(vehicle, 30 * DAY - 1)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("trips"))
        clock.advance(1)
        get(ApiPaths.SUMMARY_LATEST, vehicle).assertOk()
        app.sweeper.sweepOnce()
        assertEquals(0, rows("trips"))
    }

    @Test fun messagesPurgedAfter30Days() = serverTest {
        val vehicle = vehicleToken()
        post(ApiPaths.GUARDIANS, guardianBody(), vehicle).assertOk(HttpStatusCode.Created)
        keepAlive(vehicle, 30 * DAY - 1)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("guardian_messages"))
        clock.advance(1)
        get(ApiPaths.SUMMARY_LATEST, vehicle).assertOk()
        app.sweeper.sweepOnce()
        assertEquals(0, rows("guardian_messages"))
    }

    @Test fun inactiveAccountDeletedKeepingSuppressions() = serverTest {
        val p = pair()
        post(ApiPaths.GUARDIANS, guardianBody(), p.vehicle).assertOk(HttpStatusCode.Created)
        post(ApiPaths.GUARDIAN_OPT_OUT, """{"opt_out_code":"${sender.lastOptOutCode()}"}""").assertOk()
        clock.advance(30 * DAY - 1)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("accounts"))
        clock.advance(1)
        app.sweeper.sweepOnce()
        assertEquals(0, rows("accounts"))
        assertEquals(0, rows("devices"))
        assertEquals(1, rows("sms_suppressions"))
        upload(p.phone).assertError(HttpStatusCode.Unauthorized, ApiCodes.INVALID_TOKEN)
    }

    /** "두 기기 모두 30일 미접속". 한쪽만 접속해도 지우지 않는다. */
    @Test fun eitherDeviceActivityKeepsAccount() = serverTest {
        val p = pair()
        clock.advance(29 * DAY)
        delete(ApiPaths.SUMMARY, p.phone).assertOk()
        clock.advance(29 * DAY)
        app.sweeper.sweepOnce()
        assertEquals(1, rows("accounts"))
    }

    @Test fun revokedTokenDoesNotCountAsActivity() = serverTest {
        val p = pair()
        delete(ApiPaths.PAIRING, p.vehicle).assertOk()
        clock.advance(30 * DAY)
        upload(p.phone).assertError(HttpStatusCode.Unauthorized, ApiCodes.DEVICE_REVOKED)
        app.sweeper.sweepOnce()
        assertEquals(0, rows("accounts"))
    }

    @Test fun sweepAfterRestartRemovesExpiredRows() {
        val dir = newDbDir()
        serverTest(dir) {
            val p = pair()
            upload(p.phone, fixture("summary_full.json")).assertOk()
        }
        serverTest(dir, now = ms("2026-11-21T04:00:00+09:00")) {
            app.sweeper.sweepOnce()
            assertEquals(0, rows("condition_summaries"))
            assertEquals(1, app.db.tx { count("SELECT COUNT(*) FROM accounts") })
        }
    }

    /** 활성 계정 삭제 규칙을 건드리지 않고 시간을 건너뛴다. */
    private suspend fun TestEnv.keepAlive(token: String, totalMs: Long) {
        var left = totalMs
        while (left > 0) {
            val step = minOf(left, 20 * DAY)
            clock.advance(step)
            left -= step
            get(ApiPaths.SUMMARY_LATEST, token).assertOk()
        }
    }
}
