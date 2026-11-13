package com.conditiondrive.vehicle.port

interface StateStore {
    suspend fun load(): EngineStateRecord?
    suspend fun save(record: EngineStateRecord)
    suspend fun clear()
}
data class EngineStateRecord(
    val engineStateJson: String, val bootCount: Int, val odometerKm: Double?,
    val lastSummaryJson: String?, val lastSummaryReceivedAtMs: Long?,   // 9장 last_summary
    val tripLogCursor: Long,
)
