package com.conditiondrive.server.service

import com.conditiondrive.core.contract.ConsentPutResponse
import com.conditiondrive.core.contract.ConsentWithdrawResponse
import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.ApiTime
import com.conditiondrive.server.db.Database
import com.conditiondrive.server.db.exec
import com.conditiondrive.server.db.queryOne
import java.sql.Connection

object ConsentTypes {
    const val HEALTH_SUMMARY = "health_summary"
    const val GUARDIAN_ALERT = "guardian_alert"
    val ALL = setOf(HEALTH_SUMMARY, GUARDIAN_ALERT)
}

internal fun Connection.hasConsent(accountId: String, type: String): Boolean = queryOne(
    "SELECT 1 FROM consents WHERE account_id = ? AND type = ? AND withdrawn_at IS NULL",
    accountId, type,
) { true } == true

/** 번호와 라벨은 삭제 즉시 파기한다(14장). 거부 코드 해시와 번호 HMAC은 남겨 이미 받은 문자로도 수신 거부가 되게 한다. */
internal const val GUARDIAN_WIPE_COLUMNS =
    "phone_e164 = NULL, guardian_label = NULL, driver_display_name = '', consent_code_hash = NULL, consent_expires_at = NULL, ended_at = ?"

internal fun Connection.revokeGuardians(accountId: String, now: Long) {
    exec(
        "UPDATE guardians SET status = 'revoked', $GUARDIAN_WIPE_COLUMNS WHERE account_id = ? AND status IN ('pending_guardian_consent', 'active')",
        now, accountId,
    )
}

class ConsentService(private val db: Database, private val clock: Clock) {

    suspend fun isActive(accountId: String, type: String): Boolean = db.tx { hasConsent(accountId, type) }

    suspend fun grant(accountId: String, type: String, version: String): ConsentPutResponse {
        val now = clock.nowMs()
        db.tx { upsertConsent(accountId, type, version, now) }
        return ConsentPutResponse(type, version, ApiTime.format(now))
    }

    /** 철회 효과는 같은 트랜잭션에서 지운다. 철회 후 값이 잠깐이라도 남으면 안 된다(14장). */
    suspend fun withdraw(accountId: String, type: String): ConsentWithdrawResponse {
        val now = clock.nowMs()
        db.tx {
            exec("UPDATE consents SET withdrawn_at = ? WHERE account_id = ? AND type = ? AND withdrawn_at IS NULL", now, accountId, type)
            when (type) {
                ConsentTypes.HEALTH_SUMMARY -> exec("DELETE FROM condition_summaries WHERE account_id = ?", accountId)
                ConsentTypes.GUARDIAN_ALERT -> revokeGuardians(accountId, now)
            }
        }
        return ConsentWithdrawResponse(withdrawn = true, dataDeleted = true)
    }
}
