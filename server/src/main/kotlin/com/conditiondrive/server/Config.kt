package com.conditiondrive.server

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64

class Config(
    val dbPath: Path,
    val hmacKey: ByteArray,
    val port: Int = DEFAULT_PORT,
    val allowPartnerRealtime: Boolean = false,
    val smsOutboxPath: Path? = null,
) {
    companion object {
        // 12.1: 평문 HTTP로 건강 요약이 오가므로 바꿀 수 있는 설정으로 두지 않는다(결정 B-09)
        const val HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8080
        const val VERSION = "0.1.0"
        private const val HMAC_KEY_BYTES = 32

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val dbPath = Path.of(env["CD_DB_PATH"] ?: "conditiondrive.db").toAbsolutePath()
            return Config(
                dbPath = dbPath,
                hmacKey = loadHmacKey(env["CD_HMAC_KEY"], dbPath),
                port = env["CD_PORT"]?.toIntOrNull() ?: DEFAULT_PORT,
                allowPartnerRealtime = env["CD_ALLOW_PARTNER_REALTIME"] == "true",
                smsOutboxPath = env["CD_SMS_OUTBOX"]?.let { Path.of(it).toAbsolutePath() },
            )
        }

        /** 번호 HMAC이 재시작 뒤에도 같아야 번호별 한도와 수신 거부가 유지된다. 키를 DB 안에 두면 DB 유출 때 HMAC이 무의미해지므로 파일을 나눈다 */
        private fun loadHmacKey(fromEnv: String?, dbPath: Path): ByteArray {
            if (!fromEnv.isNullOrBlank()) {
                val key = Base64.getUrlDecoder().decode(fromEnv)
                require(key.size >= HMAC_KEY_BYTES) { "CD_HMAC_KEY 길이 부족" }
                return key
            }
            val file = dbPath.resolveSibling("${dbPath.fileName}.hmac.key")
            if (Files.exists(file)) return Base64.getUrlDecoder().decode(Files.readString(file).trim())
            Files.createDirectories(file.parent)
            val key = ByteArray(HMAC_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            Files.writeString(file, Base64.getUrlEncoder().withoutPadding().encodeToString(key))
            ownerOnly(file)
            return key
        }

        fun ownerOnly(file: Path) {
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
            } catch (_: UnsupportedOperationException) {
                // POSIX 권한이 없는 파일 시스템에서는 시연 환경의 기본 권한을 따른다
            }
        }
    }
}

/** 12.1, 12.5~12.14의 호출 제한 값. */
object Limits {
    const val BODY_SUMMARY = 2048
    const val BODY_TRIP = 32 * 1024
    const val BODY_DEFAULT = 4 * 1024
    const val RETRY_AFTER_MAX_SEC = 300L

    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS

    const val VEHICLE_REGISTER_PER_HOUR = 20
    const val PAIRING_CODES_PER_HOUR = 12
    const val PAIRING_STATUS_PER_MIN = 30
    const val SUMMARY_POST_PER_MIN = 20
    const val SUMMARY_LATEST_PER_MIN = 12
    const val TRIPS_PER_MIN = 20
    const val OPT_OUT_PER_MIN = 30

    const val CLAIM_FAILURES = 30
    const val CLAIM_FAILURE_WINDOW_MS = 10 * MINUTE_MS
    const val CLAIM_LOCK_MS = MINUTE_MS

    const val PAIRING_TTL_SEC = 300

    const val CONSENT_CODE_TTL_MS = 10 * MINUTE_MS
    const val CONSENT_CODE_MAX_FAILURES = 5
    const val CONSENT_RESEND_MS = MINUTE_MS
    const val CONSENT_SMS_ACCOUNT_PER_DAY = 3
    const val CONSENT_SMS_NUMBER_PER_DAY = 3
    const val CONSENT_SMS_GLOBAL_PER_DAY = 50

    const val ALERT_COOLDOWN_MS = 30 * MINUTE_MS
    const val ALERT_ACCOUNT_PER_DAY = 3
    const val ALERT_NUMBER_PER_DAY = 5
    const val ALERT_SIMULATED_INTERVAL_MS = MINUTE_MS
    const val ALERT_MIN_IGNORED_SEC = 600
    const val ALERT_MAX_AGE_MS = 300_000L
    const val ALERT_MAX_AHEAD_MS = 60_000L

    const val TRIP_MAX_SEGMENTS = 200
    const val TRIP_MAX_COUNT = 1000
    const val TRIP_ELAPSED_SLACK_SEC = 60L

    const val RETENTION_MS = 30 * DAY_MS
    const val PAIRING_CODE_RETENTION_MS = HOUR_MS
    const val GUARDIAN_PENDING_RETENTION_MS = DAY_MS
    const val SWEEP_INTERVAL_MS = 5 * MINUTE_MS
    const val CHECKPOINT_INTERVAL_MS = HOUR_MS
}
