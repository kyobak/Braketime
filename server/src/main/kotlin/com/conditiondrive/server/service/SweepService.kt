package com.conditiondrive.server.service

import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.Limits
import com.conditiondrive.server.db.Database
import com.conditiondrive.server.db.exec
import com.conditiondrive.server.db.longOrNull
import com.conditiondrive.server.db.query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** 12.16. 읽을 때의 마스킹과 별개로 저장소에서도 실제로 지운다. */
class SweepService(private val db: Database, private val clock: Clock, private val limiter: RateLimiter) {
    private val logger = LoggerFactory.getLogger("cd.server")

    suspend fun sweepOnce() {
        val now = clock.nowMs()
        db.tx {
            exec(
                "DELETE FROM condition_summaries WHERE MAX(valid_until, COALESCE(sleep_valid_until, 0)) <= ?",
                now,
            )
            val partly = query(
                """SELECT account_id, payload_json, valid_until, sleep_valid_until FROM condition_summaries
                   WHERE valid_until <= ? OR sleep_valid_until <= ?""",
                now, now,
            ) { PartlyExpired(it.getString("account_id"), it.getString("payload_json"), it.getLong("valid_until"), it.longOrNull("sleep_valid_until")) }
            for (row in partly) {
                val dto = ContractJson.Strict.decodeFromString(SummaryDto.serializer(), row.payload)
                val masked = SummaryMask.apply(dto, row.validUntil, row.sleepValidUntil, now) ?: continue
                if (masked != dto) {
                    exec(
                        "UPDATE condition_summaries SET payload_json = ? WHERE account_id = ?",
                        ContractJson.Strict.encodeToString(SummaryDto.serializer(), masked), row.accountId,
                    )
                }
            }
            exec("DELETE FROM pairing_codes WHERE expires_at + ? <= ?", Limits.PAIRING_CODE_RETENTION_MS, now)
            exec(
                "DELETE FROM guardians WHERE status = 'pending_guardian_consent' AND created_at + ? <= ?",
                Limits.GUARDIAN_PENDING_RETENTION_MS, now,
            )
            exec(
                "DELETE FROM guardians WHERE status IN ('revoked', 'opted_out') AND ended_at + ? <= ?",
                Limits.RETENTION_MS, now,
            )
            exec("DELETE FROM trips WHERE purge_at <= ?", now)
            exec("DELETE FROM guardian_messages WHERE purge_at <= ?", now)
            // 활동 시각은 두 기기의 인증 호출마다 갱신되므로 이 값이 오래됐으면 두 기기 모두 미접속이다
            exec("DELETE FROM accounts WHERE last_activity_at + ? <= ?", Limits.RETENTION_MS, now)
        }
        limiter.prune()
    }

    /** 시작 시 1회, 이후 주기 실행. 한 번 실패해도 다음 주기에 다시 지워야 하므로 루프를 끝내지 않는다. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            var sinceCheckpointMs = 0L
            while (isActive) {
                guarded("sweep") { sweepOnce() }
                delay(Limits.SWEEP_INTERVAL_MS)
                sinceCheckpointMs += Limits.SWEEP_INTERVAL_MS
                if (sinceCheckpointMs >= Limits.CHECKPOINT_INTERVAL_MS) {
                    guarded("checkpoint") { db.checkpoint() }
                    sinceCheckpointMs = 0
                }
            }
        }
    }

    private suspend fun guarded(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("${name}_failed type=${e::class.simpleName}")
        }
    }

    private class PartlyExpired(val accountId: String, val payload: String, val validUntil: Long, val sleepValidUntil: Long?)
}
