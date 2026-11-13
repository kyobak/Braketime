package com.conditiondrive.server.service

import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.TripDeleteResponse
import com.conditiondrive.core.contract.TripUploadRequest
import com.conditiondrive.core.contract.TripUploadResponse
import com.conditiondrive.core.contract.Violation
import com.conditiondrive.core.contract.ViolationCodes
import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.ApiTime
import com.conditiondrive.server.Limits
import com.conditiondrive.server.db.Database
import com.conditiondrive.server.db.exec
import com.conditiondrive.server.db.longOrNull
import com.conditiondrive.server.db.queryOne

data class TripUploadResult(val created: Boolean, val response: TripUploadResponse)

class TripService(private val db: Database, private val clock: Clock) {
    private val uuid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val segmentTypes = setOf("drive", "rest")

    /** 12.11 검증. 시각 파싱과 경과 계산이 함께 있어 라우트가 아니라 여기에 둔다. */
    fun validate(trip: TripUploadRequest, nowMs: Long): List<Violation> = buildList {
        fun fail(field: String, rule: String) = add(Violation(field, rule, ViolationCodes.VALIDATION_FAILED))
        if (!uuid.matches(trip.tripId)) fail("trip_id", "format")
        if (trip.rev < 1) fail("rev", "range")
        if (trip.driveSec < 0) fail("drive_sec", "range")
        if (trip.restSec < 0) fail("rest_sec", "range")
        val counts = mapOf(
            "stop_advised_count" to trip.stopAdvisedCount,
            "declined_count" to trip.declinedCount,
            "rest_completed_count" to trip.restCompletedCount,
        )
        counts.forEach { (name, value) -> if (value !in 0..Limits.TRIP_MAX_COUNT) fail(name, "range") }

        val started = ApiTime.parseMs(trip.startedAt)
        val ended = trip.endedAt?.let(ApiTime::parseMs)
        if (started == null) fail("started_at", "format")
        if (trip.endedAt != null && ended == null) fail("ended_at", "format")
        if (started != null && ended != null && ended < started) fail("ended_at", "time_order")
        if (started != null) {
            val elapsedSec = Math.floorDiv((ended ?: nowMs) - started, 1000L)
            // 큰 값을 더해도 넘치지 않도록 addExact 대신 뺄셈으로 비교한다
            val budget = elapsedSec + Limits.TRIP_ELAPSED_SLACK_SEC
            if (trip.driveSec > budget || trip.restSec > budget - trip.driveSec) fail("drive_sec", "range")
        }

        if (trip.segments.size > Limits.TRIP_MAX_SEGMENTS) fail("segments", "range")
        var previousEnd: Long? = null
        var open = false
        for (segment in trip.segments) {
            if (segment.type !in segmentTypes) fail("segments.type", "enum")
            val start = ApiTime.parseMs(segment.start)
            val end = segment.end?.let(ApiTime::parseMs)
            if (start == null || (segment.end != null && end == null)) {
                fail("segments.start", "format")
                continue
            }
            // 끝나지 않은 구간은 마지막에만 올 수 있다. 뒤에 구간이 있으면 겹침이다
            if (open) fail("segments.start", "time_order")
            if (end != null && end < start) fail("segments.end", "time_order")
            if (previousEnd != null && start < previousEnd) fail("segments.start", "time_order")
            previousEnd = end ?: start
            open = end == null
        }
    }

    suspend fun upload(accountId: String, trip: TripUploadRequest): TripUploadResult {
        val now = clock.nowMs()
        val started = requireNotNull(ApiTime.parseMs(trip.startedAt))
        val ended = trip.endedAt?.let { requireNotNull(ApiTime.parseMs(it)) }
        val body = ContractJson.Strict.encodeToString(TripUploadRequest.serializer(), trip)
        return db.tx {
            val deletedBefore = queryOne("SELECT trips_deleted_before FROM accounts WHERE id = ?", accountId) { it.longOrNull("trips_deleted_before") }
            val storedRev = queryOne("SELECT rev FROM trips WHERE account_id = ? AND trip_id = ?", accountId, trip.tripId) { it.getInt(1) }
            val response = { applied: Boolean, rev: Int -> TripUploadResponse(applied, trip.tripId, rev, ApiTime.format(now)) }
            // 지운 뒤 큐에 남아 있던 기록이 올라와도 되살아나지 않게 한다(S15)
            if (deletedBefore != null && started < deletedBefore) return@tx TripUploadResult(false, response(false, trip.rev))
            if (storedRev != null && trip.rev <= storedRev) return@tx TripUploadResult(false, response(false, storedRev))
            exec(
                """INSERT INTO trips (account_id, trip_id, rev, started_at, ended_at, body_json, received_at, purge_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(account_id, trip_id) DO UPDATE SET
                     rev = excluded.rev, started_at = excluded.started_at, ended_at = excluded.ended_at,
                     body_json = excluded.body_json, received_at = excluded.received_at, purge_at = excluded.purge_at""",
                accountId, trip.tripId, trip.rev, started, ended, body, now, now + Limits.RETENTION_MS,
            )
            TripUploadResult(storedRev == null, response(true, trip.rev))
        }
    }

    /** before가 없으면 지금까지 전부. 미래 값은 앞으로 시작할 기록까지 막으므로 현재로 줄인다. */
    suspend fun delete(accountId: String, beforeMs: Long?): TripDeleteResponse {
        val now = clock.nowMs()
        val cutoff = minOf(beforeMs ?: now, now)
        return db.tx {
            val deleted = exec("DELETE FROM trips WHERE account_id = ? AND started_at < ?", accountId, cutoff)
            val current = queryOne("SELECT trips_deleted_before FROM accounts WHERE id = ?", accountId) { it.longOrNull("trips_deleted_before") }
            val effective = maxOf(current ?: Long.MIN_VALUE, cutoff)
            exec("UPDATE accounts SET trips_deleted_before = ? WHERE id = ?", effective, accountId)
            TripDeleteResponse(deleted, ApiTime.format(effective), ApiTime.format(now))
        }
    }
}
