package com.conditiondrive.server.auth

import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.db.Database
import com.conditiondrive.server.db.exec
import com.conditiondrive.server.db.longOrNull
import com.conditiondrive.server.db.queryOne
import com.conditiondrive.server.routes.ApiCodes
import com.conditiondrive.server.routes.ApiException
import com.conditiondrive.server.routes.markCaller
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

enum class Role(val db: String) {
    VEHICLE("vehicle"), PHONE("phone");
    companion object { fun of(db: String) = entries.first { it.db == db } }
}

data class DeviceAuth(val deviceId: String, val accountId: String, val role: Role)

class Authenticator(private val db: Database, private val clock: Clock) {
    private val bearer = Regex("""^[Bb]earer +(\S+)$""")

    suspend fun require(call: ApplicationCall, vararg roles: Role): DeviceAuth {
        val token = call.request.headers[HttpHeaders.Authorization]?.let { bearer.find(it)?.groupValues?.get(1) }
            ?: throw ApiException(HttpStatusCode.Unauthorized, ApiCodes.INVALID_TOKEN)
        val now = clock.nowMs()
        val found = db.tx {
            val row = queryOne(
                "SELECT id, account_id, role, revoked_at FROM devices WHERE token_hash = ?",
                Tokens.sha256(token),
            ) { Found(it.getString("id"), it.getString("account_id"), Role.of(it.getString("role")), it.longOrNull("revoked_at")) }
            if (row != null && row.revokedAt == null) {
                // 12.16 비활성 계정 판단의 근거다. 폐기된 토큰의 호출은 활동으로 치지 않는다
                exec("UPDATE devices SET last_seen_at = ? WHERE id = ?", now, row.id)
                exec("UPDATE accounts SET last_activity_at = ? WHERE id = ?", now, row.accountId)
            }
            row
        } ?: throw ApiException(HttpStatusCode.Unauthorized, ApiCodes.INVALID_TOKEN)
        if (found.revokedAt != null) throw ApiException(HttpStatusCode.Unauthorized, ApiCodes.DEVICE_REVOKED)
        call.markCaller(found.accountId, found.role)
        if (found.role !in roles) throw ApiException(HttpStatusCode.Forbidden, ApiCodes.WRONG_DEVICE_ROLE)
        return DeviceAuth(found.id, found.accountId, found.role)
    }

    private data class Found(val id: String, val accountId: String, val role: Role, val revokedAt: Long?)
}
