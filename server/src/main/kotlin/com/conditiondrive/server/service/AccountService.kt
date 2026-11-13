package com.conditiondrive.server.service

import com.conditiondrive.core.contract.AccountDeleteResponse
import com.conditiondrive.core.contract.ConsentItem
import com.conditiondrive.core.contract.PairingClaimRequest
import com.conditiondrive.core.contract.PairingClaimResponse
import com.conditiondrive.core.contract.PairingCodeResponse
import com.conditiondrive.core.contract.PairingStatusResponse
import com.conditiondrive.core.contract.PhoneInfo
import com.conditiondrive.core.contract.UnpairResponse
import com.conditiondrive.core.contract.VehicleRegisterResponse
import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.ApiTime
import com.conditiondrive.server.Limits
import com.conditiondrive.server.auth.Codes
import com.conditiondrive.server.auth.DeviceAuth
import com.conditiondrive.server.auth.Role
import com.conditiondrive.server.auth.Secrets
import com.conditiondrive.server.auth.Tokens
import com.conditiondrive.server.db.Database
import com.conditiondrive.server.db.exec
import com.conditiondrive.server.db.longOrNull
import com.conditiondrive.server.db.query
import com.conditiondrive.server.db.queryOne
import java.sql.Connection

sealed interface ClaimResult {
    data class Claimed(val response: PairingClaimResponse) : ClaimResult
    data object Invalid : ClaimResult
    data object Expired : ClaimResult
}

class AccountService(private val db: Database, private val clock: Clock, private val secrets: Secrets) {

    suspend fun registerVehicle(): VehicleRegisterResponse {
        val now = clock.nowMs()
        val accountId = Tokens.newId("acc")
        val deviceId = Tokens.newId("dev")
        val token = Tokens.newVehicleToken()
        db.tx {
            exec("INSERT INTO accounts (id, created_at, last_activity_at) VALUES (?, ?, ?)", accountId, now, now)
            exec(
                "INSERT INTO devices (id, account_id, role, token_hash, created_at, last_seen_at) VALUES (?, ?, 'vehicle', ?, ?, ?)",
                deviceId, accountId, Tokens.sha256(token), now, now,
            )
        }
        return VehicleRegisterResponse(accountId, deviceId, token, ApiTime.format(now))
    }

    suspend fun createPairingCode(auth: DeviceAuth): PairingCodeResponse {
        val now = clock.nowMs()
        val code = Codes.pairing()
        val expiresAt = now + Limits.PAIRING_TTL_SEC * 1000L
        val replacesPhone = db.tx {
            // 차량당 활성 코드는 1개다. 소비된 코드 행은 스윕까지 남겨 재사용을 만료가 아닌 무효로 응답한다
            exec("DELETE FROM pairing_codes WHERE account_id = ? AND consumed_at IS NULL", auth.accountId)
            exec(
                "INSERT INTO pairing_codes (code_hash, account_id, created_at, expires_at) VALUES (?, ?, ?, ?)",
                secrets.pairingCodeHash(code), auth.accountId, now, expiresAt,
            )
            activePhone(auth.accountId) != null
        }
        return PairingCodeResponse(
            code = code,
            qrPayload = "conditiondrive://pair?v=1&code=$code",
            expiresAt = ApiTime.format(expiresAt),
            ttlSec = Limits.PAIRING_TTL_SEC,
            replacesPhone = replacesPhone,
            serverTime = ApiTime.format(now),
        )
    }

    /** 12.5 트랜잭션. 중간에 실패하면 코드도 이전 폰도 그대로 남는다. */
    suspend fun claim(request: PairingClaimRequest, consents: List<ConsentItem>): ClaimResult {
        val now = clock.nowMs()
        val code = Codes.normalizePairing(request.code)
        if (!Codes.isPairingShape(code)) return ClaimResult.Invalid
        val deviceId = Tokens.newId("dev")
        val token = Tokens.newPhoneToken()
        return db.tx {
            val row = queryOne(
                "SELECT account_id, expires_at, consumed_at FROM pairing_codes WHERE code_hash = ?",
                secrets.pairingCodeHash(code),
            ) { Triple(it.getString("account_id"), it.getLong("expires_at"), it.longOrNull("consumed_at")) }
                ?: return@tx ClaimResult.Invalid
            val (accountId, expiresAt, consumedAt) = row
            if (consumedAt != null) return@tx ClaimResult.Invalid
            if (now >= expiresAt) return@tx ClaimResult.Expired

            exec("UPDATE pairing_codes SET consumed_at = ? WHERE code_hash = ?", now, secrets.pairingCodeHash(code))
            exec("UPDATE devices SET revoked_at = ? WHERE account_id = ? AND role = 'phone' AND revoked_at IS NULL", now, accountId)
            exec("DELETE FROM condition_summaries WHERE account_id = ?", accountId)
            exec(
                "INSERT INTO devices (id, account_id, role, token_hash, device_name, created_at, last_seen_at) VALUES (?, ?, 'phone', ?, ?, ?, ?)",
                deviceId, accountId, Tokens.sha256(token), request.deviceName, now, now,
            )
            consents.forEach { upsertConsent(accountId, it.type, it.version, now) }
            exec("UPDATE accounts SET last_activity_at = ? WHERE id = ?", now, accountId)
            ClaimResult.Claimed(PairingClaimResponse(accountId, deviceId, token, ApiTime.format(now)))
        }
    }

    suspend fun pairingStatus(auth: DeviceAuth): PairingStatusResponse {
        val now = clock.nowMs()
        return db.tx {
            val phone = activePhone(auth.accountId)
            val vehicleLastSeen = lastSeen(auth.accountId, Role.VEHICLE)
            val pending = queryOne(
                """SELECT expires_at FROM pairing_codes
                   WHERE account_id = ? AND consumed_at IS NULL AND expires_at > ? ORDER BY created_at DESC LIMIT 1""",
                auth.accountId, now,
            ) { it.getLong(1) }
            PairingStatusResponse(
                paired = phone != null,
                phone = phone,
                vehicleLastSeenAt = vehicleLastSeen?.let(ApiTime::format),
                pendingCodeExpiresAt = pending?.let(ApiTime::format),
                serverTime = ApiTime.format(now),
            )
        }
    }

    suspend fun unpair(auth: DeviceAuth): UnpairResponse {
        val now = clock.nowMs()
        val revoked = db.tx {
            val count = exec("UPDATE devices SET revoked_at = ? WHERE account_id = ? AND role = 'phone' AND revoked_at IS NULL", now, auth.accountId)
            exec("DELETE FROM condition_summaries WHERE account_id = ?", auth.accountId)
            count
        }
        return UnpairResponse(unpaired = revoked > 0, summaryDeleted = true, serverTime = ApiTime.format(now))
    }

    /** 외래 키 CASCADE로 계정의 모든 행이 지워지고 토큰도 함께 사라진다. 수신 거부 목록은 계정 소유가 아니라 남는다(12.16). */
    suspend fun deleteAccount(auth: DeviceAuth): AccountDeleteResponse {
        db.tx { exec("DELETE FROM accounts WHERE id = ?", auth.accountId) }
        return AccountDeleteResponse(deleted = true)
    }
}

internal fun Connection.upsertConsent(accountId: String, type: String, version: String, now: Long) {
    exec(
        """INSERT INTO consents (account_id, type, version, granted_at) VALUES (?, ?, ?, ?)
           ON CONFLICT(account_id, type) DO UPDATE SET version = excluded.version, granted_at = excluded.granted_at, withdrawn_at = NULL""",
        accountId, type, version, now,
    )
}

internal fun Connection.activePhone(accountId: String): PhoneInfo? = queryOne(
    "SELECT id, device_name, created_at, last_seen_at FROM devices WHERE account_id = ? AND role = 'phone' AND revoked_at IS NULL",
    accountId,
) {
    PhoneInfo(
        deviceId = it.getString("id"),
        deviceName = it.getString("device_name"),
        pairedAt = ApiTime.format(it.getLong("created_at")),
        lastSeenAt = it.longOrNull("last_seen_at")?.let(ApiTime::format),
    )
}

internal fun Connection.lastSeen(accountId: String, role: Role): Long? = query(
    "SELECT last_seen_at FROM devices WHERE account_id = ? AND role = ? AND revoked_at IS NULL",
    accountId, role.db,
) { it.longOrNull("last_seen_at") }.firstOrNull()
