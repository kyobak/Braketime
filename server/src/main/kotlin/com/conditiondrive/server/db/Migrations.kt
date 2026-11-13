package com.conditiondrive.server.db

import java.sql.Connection

/** 12.15. 순서대로만 추가한다. 이미 배포한 항목을 고치면 기존 DB가 어긋난다. */
internal object Migrations {
    private val V1 = listOf(
        """CREATE TABLE accounts (
          id TEXT PRIMARY KEY, created_at INTEGER NOT NULL, last_activity_at INTEGER NOT NULL, trips_deleted_before INTEGER
        )""",
        """CREATE TABLE devices (
          id TEXT PRIMARY KEY,
          account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          role TEXT NOT NULL CHECK (role IN ('vehicle','phone')),
          token_hash BLOB NOT NULL UNIQUE,
          device_name TEXT, created_at INTEGER NOT NULL, last_seen_at INTEGER, revoked_at INTEGER
        )""",
        "CREATE UNIQUE INDEX ux_active_device_role ON devices(account_id, role) WHERE revoked_at IS NULL",
        """CREATE TABLE pairing_codes (
          code_hash BLOB PRIMARY KEY,
          account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, consumed_at INTEGER
        )""",
        """CREATE TABLE consents (
          account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          type TEXT NOT NULL CHECK (type IN ('health_summary','guardian_alert')),
          version TEXT NOT NULL, granted_at INTEGER NOT NULL, withdrawn_at INTEGER,
          PRIMARY KEY (account_id, type)
        )""",
        """CREATE TABLE condition_summaries (
          account_id TEXT PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
          phone_device_id TEXT NOT NULL,
          payload_json TEXT NOT NULL,
          generated_at INTEGER NOT NULL, valid_until INTEGER NOT NULL, sleep_valid_until INTEGER, received_at INTEGER NOT NULL
        )""",
        """CREATE TABLE trips (
          account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          trip_id TEXT NOT NULL, rev INTEGER NOT NULL, started_at INTEGER NOT NULL, ended_at INTEGER,
          body_json TEXT NOT NULL, received_at INTEGER NOT NULL, purge_at INTEGER NOT NULL,
          PRIMARY KEY (account_id, trip_id)
        )""",
        """CREATE TABLE guardians (
          id TEXT PRIMARY KEY,
          account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          phone_e164 TEXT, phone_hmac BLOB NOT NULL, guardian_label TEXT, driver_display_name TEXT NOT NULL,
          status TEXT NOT NULL CHECK (status IN ('pending_guardian_consent','active','revoked','opted_out')),
          consent_code_hash BLOB, consent_expires_at INTEGER, consent_attempts INTEGER NOT NULL DEFAULT 0,
          opt_out_code_hash BLOB, driver_consent_version TEXT NOT NULL, driver_consented_at INTEGER NOT NULL,
          guardian_consented_at INTEGER, created_at INTEGER NOT NULL, ended_at INTEGER
        )""",
        "CREATE UNIQUE INDEX ux_active_guardian ON guardians(account_id) WHERE status IN ('pending_guardian_consent','active')",
        "CREATE INDEX ix_guardian_opt_out ON guardians(opt_out_code_hash)",
        """CREATE TABLE guardian_messages (
          id TEXT PRIMARY KEY,
          account_id TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
          phone_hmac BLOB NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('consent','alert')), is_simulated INTEGER NOT NULL,
          status TEXT NOT NULL CHECK (status IN ('pending','logged','provider_accepted','suppressed_simulated','failed')),
          created_at INTEGER NOT NULL, purge_at INTEGER NOT NULL
        )""",
        "CREATE INDEX ix_gm_account_time ON guardian_messages(account_id, created_at)",
        "CREATE INDEX ix_gm_phone_time ON guardian_messages(phone_hmac, created_at)",
        "CREATE TABLE sms_suppressions (phone_hmac BLOB PRIMARY KEY, created_at INTEGER NOT NULL)",
    )

    private val ALL: List<List<String>> = listOf(V1)

    fun apply(conn: Connection) {
        val current = conn.query("PRAGMA user_version") { it.getInt(1) }.single()
        check(current <= ALL.size) { "DB가 이 서버보다 새 버전이다: $current" }
        ALL.drop(current).forEachIndexed { i, statements ->
            conn.inTransaction {
                statements.forEach { sql -> createStatement().use { it.execute(sql) } }
                createStatement().use { it.execute("PRAGMA user_version=${current + i + 1}") }
            }
        }
    }
}
