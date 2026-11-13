package com.conditiondrive.server.service

import com.conditiondrive.core.contract.DriverConsent
import com.conditiondrive.core.contract.GlucoseSourceDto
import com.conditiondrive.core.contract.GuardianAlertRequest
import com.conditiondrive.core.contract.GuardianAlertResponse
import com.conditiondrive.core.contract.GuardianConfirmResponse
import com.conditiondrive.core.contract.GuardianRegisterRequest
import com.conditiondrive.core.contract.GuardianRegisterResponse
import com.conditiondrive.core.contract.ViolationCodes
import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.ApiTime
import com.conditiondrive.server.Limits
import com.conditiondrive.server.auth.Codes
import com.conditiondrive.server.auth.Secrets
import com.conditiondrive.server.auth.Tokens
import com.conditiondrive.server.db.Database
import com.conditiondrive.server.db.count
import com.conditiondrive.server.db.exec
import com.conditiondrive.server.db.longOrNull
import com.conditiondrive.server.db.query
import com.conditiondrive.server.db.queryOne
import com.conditiondrive.server.routes.ApiCodes
import com.conditiondrive.server.routes.ApiException
import com.conditiondrive.server.routes.rateLimited
import com.conditiondrive.server.routes.validationError
import com.conditiondrive.server.sms.Delivery
import com.conditiondrive.server.sms.GuardianMessageSender
import com.conditiondrive.server.sms.OutboundSms
import com.conditiondrive.server.sms.PhoneMask
import com.conditiondrive.server.sms.SendResult
import com.conditiondrive.server.sms.SmsTemplate
import com.conditiondrive.server.sms.SmsVars
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.sql.Connection

@Serializable data class GuardianInfo(
    @SerialName("guardian_id") val guardianId: String,
    val status: String,
    @SerialName("phone_masked") val phoneMasked: String?,
    @SerialName("guardian_label") val guardianLabel: String?,
    @SerialName("consent_expires_at") val consentExpiresAt: String?,
    @SerialName("guardian_consented_at") val guardianConsentedAt: String?,
)
@Serializable data class GuardianListResponse(val guardian: GuardianInfo?, @SerialName("server_time") val serverTime: String)
@Serializable data class GuardianDeleteResponse(val deleted: Boolean, @SerialName("server_time") val serverTime: String)

data class AlertResult(val created: Boolean, val response: GuardianAlertResponse)

private object GuardianStatus {
    const val PENDING = "pending_guardian_consent"
    const val ACTIVE = "active"
    const val OPTED_OUT = "opted_out"
}

class GuardianService(
    private val db: Database,
    private val clock: Clock,
    private val secrets: Secrets,
    private val sender: GuardianMessageSender,
    private val allowPartnerRealtime: Boolean,
) {
    private val logger = LoggerFactory.getLogger("cd.server")
    private val phoneFormat = Regex("""\+8210\d{8}""")
    private val nameFormat = Regex("""[가-힣A-Za-z]{2,10}""")
    private val uuid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    // 슬롯 예약과 발송 결과 기록 사이에 같은 계정 요청이 끼면 대기 슬롯을 두고 경쟁한다. 발송기가 하나뿐이라 전역으로 묶는다
    private val sendLock = Mutex()

    suspend fun register(accountId: String, request: GuardianRegisterRequest): GuardianRegisterResponse {
        validateRegister(request)
        requireDriverConsent(request.driverConsent)
        val phone = request.phoneE164
        val phoneHmac = secrets.phoneHmac(phone)
        val guardianId = Tokens.newId("grd")
        val code = Codes.consent()
        val optOut = secrets.optOutCode(guardianId)
        val messageId = Tokens.newId("gmsg")
        return sendLock.withLock {
            val now = clock.nowMs()
            val expiresAt = now + Limits.CONSENT_CODE_TTL_MS
            db.tx {
                if (count("SELECT COUNT(*) FROM sms_suppressions WHERE phone_hmac = ?", phoneHmac) > 0) {
                    throw ApiException(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_OPTED_OUT)
                }
                val existing = count(
                    "SELECT COUNT(*) FROM guardians WHERE account_id = ? AND status IN ('pending_guardian_consent', 'active')",
                    accountId,
                )
                if (existing > 0) throw ApiException(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_EXISTS)
                checkConsentSmsLimits(accountId, phoneHmac, now)
                upsertConsent(accountId, ConsentTypes.GUARDIAN_ALERT, request.driverConsent.version, now)
                exec(
                    """INSERT INTO guardians (id, account_id, phone_e164, phone_hmac, guardian_label, driver_display_name, status,
                         consent_code_hash, consent_expires_at, opt_out_code_hash, driver_consent_version, driver_consented_at, created_at)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    guardianId, accountId, phone, phoneHmac, request.guardianLabel, request.driverDisplayName, GuardianStatus.PENDING,
                    secrets.consentCodeHash(guardianId, code), expiresAt, secrets.optOutCodeHash(optOut),
                    request.driverConsent.version, now, now,
                )
                reserveMessage(messageId, accountId, phoneHmac, "consent", simulated = false, now)
            }
            val sms = OutboundSms(phone, SmsTemplate.GUARDIAN_CONSENT_V1, SmsVars(request.driverDisplayName, code, optOut), messageId)
            when (val result = deliver(sms)) {
                is SendResult.Accepted -> {
                    db.tx { exec("UPDATE guardian_messages SET status = ? WHERE id = ?", result.delivery, messageId) }
                    GuardianRegisterResponse(guardianId, GuardianStatus.PENDING, PhoneMask.mask(phone), ApiTime.format(expiresAt), result.delivery, ApiTime.format(now))
                }
                is SendResult.Failed -> {
                    // 문자가 가지 않은 등록을 남기면 guardian_exists에 막혀 다시 등록할 수 없다
                    db.tx {
                        exec("UPDATE guardian_messages SET status = 'failed' WHERE id = ?", messageId)
                        exec("DELETE FROM guardians WHERE id = ?", guardianId)
                    }
                    throw ApiException(HttpStatusCode.BadGateway, ApiCodes.SENDER_FAILED)
                }
            }
        }
    }

    suspend fun confirm(accountId: String, guardianId: String, code: String): GuardianConfirmResponse {
        val now = clock.nowMs()
        val outcome = db.tx {
            val row = guardianRow(accountId, guardianId) ?: return@tx ConfirmOutcome.NotFound
            when (row.status) {
                GuardianStatus.ACTIVE -> return@tx ConfirmOutcome.Active(row.guardianConsentedAt ?: now)
                GuardianStatus.PENDING -> Unit
                else -> return@tx ConfirmOutcome.NotActive
            }
            val hash = row.consentCodeHash
            if (hash == null || row.consentExpiresAt == null || now >= row.consentExpiresAt) return@tx ConfirmOutcome.Expired
            if (MessageDigest.isEqual(hash, secrets.consentCodeHash(guardianId, code))) {
                exec(
                    """UPDATE guardians SET status = 'active', guardian_consented_at = ?, consent_code_hash = NULL,
                         consent_expires_at = NULL, consent_attempts = 0 WHERE id = ?""",
                    now, guardianId,
                )
                ConfirmOutcome.Active(now)
            } else {
                // 실패 횟수를 커밋해야 하므로 예외가 아니라 결과로 돌려준다
                val attempts = row.consentAttempts + 1
                if (attempts >= Limits.CONSENT_CODE_MAX_FAILURES) {
                    exec("UPDATE guardians SET consent_attempts = ?, consent_code_hash = NULL, consent_expires_at = NULL WHERE id = ?", attempts, guardianId)
                } else {
                    exec("UPDATE guardians SET consent_attempts = ? WHERE id = ?", attempts, guardianId)
                }
                ConfirmOutcome.Wrong
            }
        }
        return when (outcome) {
            is ConfirmOutcome.Active ->
                GuardianConfirmResponse(guardianId, GuardianStatus.ACTIVE, ApiTime.format(outcome.consentedAt), ApiTime.format(now))
            ConfirmOutcome.NotFound -> throw ApiException(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
            ConfirmOutcome.NotActive -> throw ApiException(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_NOT_ACTIVE)
            ConfirmOutcome.Expired -> throw ApiException(HttpStatusCode.Gone, ApiCodes.CONSENT_CODE_EXPIRED)
            ConfirmOutcome.Wrong -> throw ApiException(HttpStatusCode.UnprocessableEntity, ApiCodes.CONSENT_CODE_INVALID)
        }
    }

    suspend fun resend(accountId: String, guardianId: String): GuardianRegisterResponse = sendLock.withLock {
        val now = clock.nowMs()
        val code = Codes.consent()
        val messageId = Tokens.newId("gmsg")
        val expiresAt = now + Limits.CONSENT_CODE_TTL_MS
        val row = db.tx {
            val row = guardianRow(accountId, guardianId) ?: throw ApiException(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
            if (row.status != GuardianStatus.PENDING || row.phoneE164 == null) {
                throw ApiException(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_NOT_ACTIVE)
            }
            checkConsentSmsLimits(accountId, row.phoneHmac, now)
            exec(
                "UPDATE guardians SET consent_code_hash = ?, consent_expires_at = ?, consent_attempts = 0 WHERE id = ?",
                secrets.consentCodeHash(guardianId, code), expiresAt, guardianId,
            )
            reserveMessage(messageId, accountId, row.phoneHmac, "consent", simulated = false, now)
            row
        }
        val phone = requireNotNull(row.phoneE164)
        val sms = OutboundSms(phone, SmsTemplate.GUARDIAN_CONSENT_V1, SmsVars(row.driverName, code, secrets.optOutCode(guardianId)), messageId)
        when (val result = deliver(sms)) {
            is SendResult.Accepted -> {
                db.tx { exec("UPDATE guardian_messages SET status = ? WHERE id = ?", result.delivery, messageId) }
                GuardianRegisterResponse(guardianId, GuardianStatus.PENDING, PhoneMask.mask(phone), ApiTime.format(expiresAt), result.delivery, ApiTime.format(now))
            }
            is SendResult.Failed -> {
                db.tx { exec("UPDATE guardian_messages SET status = 'failed' WHERE id = ?", messageId) }
                throw ApiException(HttpStatusCode.BadGateway, ApiCodes.SENDER_FAILED)
            }
        }
    }

    suspend fun current(accountId: String): GuardianListResponse {
        val now = clock.nowMs()
        val info = db.tx {
            // 수신 거부는 운전자가 알아야 다시 등록을 시도하지 않는다. 삭제한 보호자는 보여 줄 이유가 없다
            queryOne(
                """SELECT id, status, phone_e164, guardian_label, consent_expires_at, guardian_consented_at FROM guardians
                   WHERE account_id = ? AND status IN ('pending_guardian_consent', 'active', 'opted_out')
                   ORDER BY created_at DESC LIMIT 1""",
                accountId,
            ) {
                GuardianInfo(
                    guardianId = it.getString("id"),
                    status = it.getString("status"),
                    phoneMasked = it.getString("phone_e164")?.let(PhoneMask::mask),
                    guardianLabel = it.getString("guardian_label"),
                    consentExpiresAt = it.longOrNull("consent_expires_at")?.let(ApiTime::format),
                    guardianConsentedAt = it.longOrNull("guardian_consented_at")?.let(ApiTime::format),
                )
            }
        }
        return GuardianListResponse(info, ApiTime.format(now))
    }

    suspend fun delete(accountId: String, guardianId: String): GuardianDeleteResponse {
        val now = clock.nowMs()
        db.tx {
            guardianRow(accountId, guardianId) ?: throw ApiException(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
            exec(
                "UPDATE guardians SET status = 'revoked', $GUARDIAN_WIPE_COLUMNS WHERE id = ? AND status IN ('pending_guardian_consent', 'active')",
                now, guardianId,
            )
        }
        return GuardianDeleteResponse(deleted = true, serverTime = ApiTime.format(now))
    }

    /** 코드가 맞는지 응답으로 알려 주지 않는다. 존재 여부를 추측하는 데 쓰이기 때문이다(12.13). */
    suspend fun optOut(rawCode: String) {
        val hash = secrets.optOutCodeHash(rawCode.trim().uppercase())
        val now = clock.nowMs()
        db.tx {
            val phones = query("SELECT DISTINCT phone_hmac FROM guardians WHERE opt_out_code_hash = ?", hash) { it.getBytes(1) }
            for (phoneHmac in phones) {
                exec("INSERT OR IGNORE INTO sms_suppressions (phone_hmac, created_at) VALUES (?, ?)", phoneHmac, now)
                // 같은 번호를 등록한 다른 계정도 함께 끊는다. 수신 거부는 계정이 아니라 사람의 결정이다
                exec(
                    "UPDATE guardians SET status = 'opted_out', $GUARDIAN_WIPE_COLUMNS WHERE phone_hmac = ? AND status IN ('pending_guardian_consent', 'active')",
                    now, phoneHmac,
                )
            }
        }
    }

    suspend fun alert(accountId: String, request: GuardianAlertRequest): AlertResult {
        validateAlert(request)
        return sendLock.withLock {
            val now = clock.nowMs()
            val messageId = alertMessageId(accountId, request.alertId)
            val previous = db.tx { queryOne("SELECT status FROM guardian_messages WHERE id = ?", messageId) { it.getString(1) } }
            // 재전송은 오래된 occurred_at으로도 오므로 신선도 검사보다 먼저 본다
            if (previous != null && previous != "pending" && previous != "failed") {
                return@withLock AlertResult(false, GuardianAlertResponse(request.alertId, previous, ApiTime.format(now)))
            }
            val occurred = ApiTime.parseMs(request.occurredAt) ?: throw validationError("occurred_at", "format")
            if (occurred < now - Limits.ALERT_MAX_AGE_MS || occurred > now + Limits.ALERT_MAX_AHEAD_MS) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, ApiCodes.STALE_ALERT)
            }
            val simulated = request.glucoseSource == GlucoseSourceDto.SIMULATED
            val guardian = db.tx {
                if (!hasConsent(accountId, ConsentTypes.GUARDIAN_ALERT)) {
                    throw ApiException(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
                }
                val row = queryOne(
                    "SELECT id, phone_e164, phone_hmac, driver_display_name FROM guardians WHERE account_id = ? AND status = 'active' AND phone_e164 IS NOT NULL",
                    accountId,
                ) { ActiveGuardian(it.getString("id"), it.getString("phone_e164"), it.getBytes("phone_hmac"), it.getString("driver_display_name")) }
                    ?: throw ApiException(HttpStatusCode.Conflict, ApiCodes.GUARDIAN_NOT_ACTIVE)
                if (simulated) {
                    checkSimulatedLimit(accountId, now)
                    upsertAlertMessage(messageId, accountId, row.phoneHmac, simulated = true, Delivery.SUPPRESSED_SIMULATED, now)
                } else {
                    checkAlertLimits(accountId, row.phoneHmac, messageId, now)
                    upsertAlertMessage(messageId, accountId, row.phoneHmac, simulated = false, "pending", now)
                }
                row
            }
            if (simulated) {
                return@withLock AlertResult(true, GuardianAlertResponse(request.alertId, Delivery.SUPPRESSED_SIMULATED, ApiTime.format(now)))
            }
            val sms = OutboundSms(
                guardian.phone, SmsTemplate.GUARDIAN_ALERT_V1,
                SmsVars(guardian.driverName, optOutCode = secrets.optOutCode(guardian.id)), messageId,
            )
            when (val result = deliver(sms)) {
                is SendResult.Accepted -> {
                    db.tx { exec("UPDATE guardian_messages SET status = ? WHERE id = ?", result.delivery, messageId) }
                    AlertResult(true, GuardianAlertResponse(request.alertId, result.delivery, ApiTime.format(now)))
                }
                is SendResult.Failed -> {
                    // 'failed'는 한도 집계에서 빠지므로 슬롯이 환원된다
                    db.tx { exec("UPDATE guardian_messages SET status = 'failed' WHERE id = ?", messageId) }
                    throw ApiException(HttpStatusCode.BadGateway, ApiCodes.SENDER_FAILED)
                }
            }
        }
    }

    private suspend fun deliver(sms: OutboundSms): SendResult = try {
        sender.send(sms)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 발송기 예외 메시지에 번호가 들어 있을 수 있다(12.17)
        logger.error("sms_failed template=${sms.template.name} type=${e::class.simpleName}")
        SendResult.Failed(retryable = true, code = "exception")
    }

    private fun validateRegister(request: GuardianRegisterRequest) {
        if (!phoneFormat.matches(request.phoneE164)) throw validationError("phone_e164", "format")
        if (!nameFormat.matches(request.driverDisplayName)) throw validationError("driver_display_name", "format")
        val label = request.guardianLabel
        if (label.isEmpty() || label.length > GUARDIAN_LABEL_MAX || label.any(Char::isISOControl)) {
            throw validationError("guardian_label", "range")
        }
        val version = request.driverConsent.version
        if (version.isEmpty() || version.length > CONSENT_VERSION_MAX) throw validationError("driver_consent.version", "range")
    }

    private fun requireDriverConsent(consent: DriverConsent) {
        if (!consent.agreed) throw ApiException(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
    }

    private fun validateAlert(request: GuardianAlertRequest) {
        if (!uuid.matches(request.alertId)) throw validationError("alert_id", "format")
        if (request.reason != ALERT_REASON) throw validationError("reason", "enum")
        if (request.ignoredForSec < Limits.ALERT_MIN_IGNORED_SEC) throw validationError("ignored_for_sec", "range")
        if (request.glucoseSource == GlucoseSourceDto.PARTNER_REALTIME && !allowPartnerRealtime) {
            // 요약이 시뮬레이션만 받는 동안 실제 출처를 주장하는 알림은 문자를 보내는 경로가 되므로 막는다
            throw ApiException(HttpStatusCode.UnprocessableEntity, ViolationCodes.GLUCOSE_SOURCE_NOT_ALLOWED)
        }
    }

    /** 다른 계정이 같은 alert_id를 보내도 서로의 멱등 응답을 보지 못하게 계정을 섞는다. */
    private fun alertMessageId(accountId: String, alertId: String): String =
        "gal_" + Tokens.hex(Tokens.sha256("$accountId|$alertId")).take(32)

    private fun Connection.guardianRow(accountId: String, guardianId: String): GuardianRow? = queryOne(
        """SELECT status, phone_e164, phone_hmac, driver_display_name, consent_code_hash, consent_expires_at,
             consent_attempts, guardian_consented_at FROM guardians WHERE id = ? AND account_id = ?""",
        guardianId, accountId,
    ) {
        GuardianRow(
            status = it.getString("status"),
            phoneE164 = it.getString("phone_e164"),
            phoneHmac = it.getBytes("phone_hmac"),
            driverName = it.getString("driver_display_name"),
            consentCodeHash = it.getBytes("consent_code_hash"),
            consentExpiresAt = it.longOrNull("consent_expires_at"),
            consentAttempts = it.getInt("consent_attempts"),
            guardianConsentedAt = it.longOrNull("guardian_consented_at"),
        )
    }

    private fun Connection.reserveMessage(id: String, accountId: String, phoneHmac: ByteArray, kind: String, simulated: Boolean, now: Long) {
        exec(
            """INSERT INTO guardian_messages (id, account_id, phone_hmac, kind, is_simulated, status, created_at, purge_at)
               VALUES (?, ?, ?, ?, ?, 'pending', ?, ?)""",
            id, accountId, phoneHmac, kind, if (simulated) 1 else 0, now, now + Limits.RETENTION_MS,
        )
    }

    private fun Connection.upsertAlertMessage(id: String, accountId: String, phoneHmac: ByteArray, simulated: Boolean, status: String, now: Long) {
        exec(
            """INSERT INTO guardian_messages (id, account_id, phone_hmac, kind, is_simulated, status, created_at, purge_at)
               VALUES (?, ?, ?, 'alert', ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET status = excluded.status, created_at = excluded.created_at, purge_at = excluded.purge_at""",
            id, accountId, phoneHmac, if (simulated) 1 else 0, status, now, now + Limits.RETENTION_MS,
        )
    }

    private fun Connection.timesSince(sql: String, vararg args: Any?): List<Long> = query(sql, *args) { it.getLong(1) }

    /** 12.14 동의 문자: 재발송 60초, 계정 3건, 번호 3건, 전역 50건. 'failed'는 슬롯 환원이라 세지 않는다. */
    private fun Connection.checkConsentSmsLimits(accountId: String, phoneHmac: ByteArray, now: Long) {
        val since = now - Limits.DAY_MS
        val byAccount = timesSince(
            "SELECT created_at FROM guardian_messages WHERE account_id = ? AND kind = 'consent' AND status <> 'failed' AND created_at > ? ORDER BY created_at",
            accountId, since,
        )
        byAccount.lastOrNull()?.let { last ->
            val wait = last + Limits.CONSENT_RESEND_MS - now
            if (wait > 0) throw rateLimited(wait)
        }
        enforceDaily(byAccount, Limits.CONSENT_SMS_ACCOUNT_PER_DAY, now, ApiCodes.RATE_LIMITED)
        enforceDaily(
            timesSince(
                "SELECT created_at FROM guardian_messages WHERE phone_hmac = ? AND kind = 'consent' AND status <> 'failed' AND created_at > ? ORDER BY created_at",
                phoneHmac, since,
            ),
            Limits.CONSENT_SMS_NUMBER_PER_DAY, now, ApiCodes.RATE_LIMITED,
        )
        enforceDaily(
            timesSince(
                "SELECT created_at FROM guardian_messages WHERE kind = 'consent' AND status <> 'failed' AND created_at > ? ORDER BY created_at",
                since,
            ),
            Limits.CONSENT_SMS_GLOBAL_PER_DAY, now, ApiCodes.RATE_LIMITED,
        )
    }

    /** 12.14 알림: 쿨다운 30분, 계정 3건, 번호 5건. 시뮬레이션은 세지 않는다. 자기 행(발송 중 끊긴 재전송)은 뺀다. */
    private fun Connection.checkAlertLimits(accountId: String, phoneHmac: ByteArray, messageId: String, now: Long) {
        val since = now - Limits.DAY_MS
        val byAccount = timesSince(
            """SELECT created_at FROM guardian_messages WHERE account_id = ? AND kind = 'alert' AND is_simulated = 0
               AND status <> 'failed' AND id <> ? AND created_at > ? ORDER BY created_at""",
            accountId, messageId, since,
        )
        byAccount.lastOrNull()?.let { last ->
            val wait = last + Limits.ALERT_COOLDOWN_MS - now
            if (wait > 0) throw rateLimited(wait, ApiCodes.ALERT_RATE_LIMITED)
        }
        enforceDaily(byAccount, Limits.ALERT_ACCOUNT_PER_DAY, now, ApiCodes.ALERT_RATE_LIMITED)
        enforceDaily(
            timesSince(
                """SELECT created_at FROM guardian_messages WHERE phone_hmac = ? AND kind = 'alert' AND is_simulated = 0
                   AND status <> 'failed' AND id <> ? AND created_at > ? ORDER BY created_at""",
                phoneHmac, messageId, since,
            ),
            Limits.ALERT_NUMBER_PER_DAY, now, ApiCodes.ALERT_RATE_LIMITED,
        )
    }

    private fun Connection.checkSimulatedLimit(accountId: String, now: Long) {
        val last = timesSince(
            "SELECT MAX(created_at) FROM guardian_messages WHERE account_id = ? AND kind = 'alert' AND is_simulated = 1",
            accountId,
        ).firstOrNull()?.takeIf { it > 0 } ?: return
        val wait = last + Limits.ALERT_SIMULATED_INTERVAL_MS - now
        if (wait > 0) throw rateLimited(wait, ApiCodes.ALERT_RATE_LIMITED)
    }

    private fun enforceDaily(timesAscending: List<Long>, limit: Int, now: Long, code: String) {
        if (timesAscending.size >= limit) {
            throw rateLimited(timesAscending[timesAscending.size - limit] + Limits.DAY_MS - now, code)
        }
    }

    private sealed interface ConfirmOutcome {
        data class Active(val consentedAt: Long) : ConfirmOutcome
        data object NotFound : ConfirmOutcome
        data object NotActive : ConfirmOutcome
        data object Expired : ConfirmOutcome
        data object Wrong : ConfirmOutcome
    }

    private class GuardianRow(
        val status: String,
        val phoneE164: String?,
        val phoneHmac: ByteArray,
        val driverName: String,
        val consentCodeHash: ByteArray?,
        val consentExpiresAt: Long?,
        val consentAttempts: Int,
        val guardianConsentedAt: Long?,
    )

    private class ActiveGuardian(val id: String, val phone: String, val phoneHmac: ByteArray, val driverName: String)

    private companion object {
        const val ALERT_REASON = "glucose_stop_ignored"
        const val GUARDIAN_LABEL_MAX = 20
        const val CONSENT_VERSION_MAX = 40
    }
}
