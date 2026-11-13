package com.conditiondrive.server.service

import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.LatestSummaryResponse
import com.conditiondrive.core.contract.SummaryDeleteResponse
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.SummaryUploadResponse
import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.ApiTime
import com.conditiondrive.server.auth.DeviceAuth
import com.conditiondrive.server.auth.Role
import com.conditiondrive.server.db.Database
import com.conditiondrive.server.db.exec
import com.conditiondrive.server.db.longOrNull
import com.conditiondrive.server.db.queryOne

private fun java.sql.Connection.isActivePhone(deviceId: String): Boolean = queryOne(
    "SELECT 1 FROM devices WHERE id = ? AND revoked_at IS NULL",
    deviceId,
) { true } == true

object SummaryMask {
    /** 12.8 표. 항목별로 자기 유효 시각까지만 내보낸다. 둘 다 지났으면 null이다. */
    fun apply(dto: SummaryDto, validUntilMs: Long, sleepValidUntilMs: Long?, nowMs: Long): SummaryDto? {
        val glucoseExpired = nowMs >= validUntilMs
        val sleepExpired = sleepValidUntilMs == null || nowMs >= sleepValidUntilMs
        if (glucoseExpired && sleepExpired) return null
        var masked = dto
        if (glucoseExpired) masked = masked.withoutGlucose()
        if (sleepExpired) masked = masked.withoutSleep()
        return masked
    }

    private fun SummaryDto.withoutGlucose() =
        copy(glucoseMmol = null, glucoseTrend = null, glucoseSource = null, glucoseMeasuredAt = null)

    private fun SummaryDto.withoutSleep() =
        copy(sleepMinutesLastNight = null, sleepShort = null, sleepValidUntil = null)
}

class SummaryService(private val db: Database, private val clock: Clock) {

    /** 검증을 통과한 DTO만 받는다. 저장은 원문이 아니라 DTO를 다시 인코딩한 값이라 허용 9개 외 내용이 들어올 수 없다. */
    suspend fun upload(auth: DeviceAuth, dto: SummaryDto): SummaryUploadResponse {
        val now = clock.nowMs()
        val generatedAt = requireNotNull(ApiTime.parseMs(dto.generatedAt))
        val validUntil = requireNotNull(ApiTime.parseMs(dto.validUntil))
        val sleepValidUntil = dto.sleepValidUntil?.let { requireNotNull(ApiTime.parseMs(it)) }
        val payload = ContractJson.Strict.encodeToString(SummaryDto.serializer(), dto)
        return db.tx {
            // 라우트의 동의 확인과 이 트랜잭션 사이에 철회나 해제가 커밋될 수 있다. 여기서 다시 봐야 지운 값이 되살아나지 않는다
            if (!isActivePhone(auth.deviceId) || !hasConsent(auth.accountId, ConsentTypes.HEALTH_SUMMARY)) {
                return@tx SummaryUploadResponse(
                    applied = false,
                    reason = "not_allowed",
                    receivedAt = ApiTime.format(now),
                    vehicleLastSeenAt = null,
                    serverTime = ApiTime.format(now),
                )
            }
            val vehicleLastSeen = lastSeen(auth.accountId, Role.VEHICLE)?.let(ApiTime::format)
            val stored = queryOne(
                "SELECT generated_at, received_at FROM condition_summaries WHERE account_id = ?",
                auth.accountId,
            ) { it.getLong("generated_at") to it.getLong("received_at") }
            if (stored != null && generatedAt <= stored.first) {
                return@tx SummaryUploadResponse(
                    applied = false,
                    reason = "not_newer",
                    receivedAt = ApiTime.format(stored.second),
                    vehicleLastSeenAt = vehicleLastSeen,
                    serverTime = ApiTime.format(now),
                )
            }
            // 계정당 1행 전체 교체라 이력이 생기지 않고, null도 그대로 덮여 끊김이 바로 전달된다(14장, 12.7)
            exec(
                """INSERT INTO condition_summaries
                     (account_id, phone_device_id, payload_json, generated_at, valid_until, sleep_valid_until, received_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(account_id) DO UPDATE SET
                     phone_device_id = excluded.phone_device_id, payload_json = excluded.payload_json,
                     generated_at = excluded.generated_at, valid_until = excluded.valid_until,
                     sleep_valid_until = excluded.sleep_valid_until, received_at = excluded.received_at""",
                auth.accountId, auth.deviceId, payload, generatedAt, validUntil, sleepValidUntil, now,
            )
            SummaryUploadResponse(
                applied = true,
                receivedAt = ApiTime.format(now),
                vehicleLastSeenAt = vehicleLastSeen,
                serverTime = ApiTime.format(now),
            )
        }
    }

    suspend fun latest(auth: DeviceAuth): LatestSummaryResponse {
        val now = clock.nowMs()
        return db.tx {
            val phone = activePhone(auth.accountId)
                ?: return@tx LatestSummaryResponse(paired = false, serverTime = ApiTime.format(now))
            val row = queryOne(
                "SELECT payload_json, valid_until, sleep_valid_until, received_at FROM condition_summaries WHERE account_id = ?",
                auth.accountId,
            ) {
                StoredSummary(it.getString("payload_json"), it.getLong("valid_until"), it.longOrNull("sleep_valid_until"), it.getLong("received_at"))
            }
            val masked = row?.let {
                SummaryMask.apply(ContractJson.Strict.decodeFromString(SummaryDto.serializer(), it.payload), it.validUntil, it.sleepValidUntil, now)
            }
            LatestSummaryResponse(
                paired = true,
                phoneName = phone.deviceName,
                serverTime = ApiTime.format(now),
                receivedAt = if (masked == null) null else row.receivedAt.let(ApiTime::format),
                summary = masked,
            )
        }
    }

    suspend fun delete(auth: DeviceAuth): SummaryDeleteResponse {
        db.tx { exec("DELETE FROM condition_summaries WHERE account_id = ?", auth.accountId) }
        return SummaryDeleteResponse(deleted = true, serverTime = ApiTime.format(clock.nowMs()))
    }

    private data class StoredSummary(val payload: String, val validUntil: Long, val sleepValidUntil: Long?, val receivedAt: Long)
}
