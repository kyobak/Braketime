package com.conditiondrive.vehicle.port

import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.engine.TripEvent
import kotlinx.coroutines.flow.Flow

interface TransitionLogPort {
    fun append(entry: TransitionEntry)
    suspend fun export(): java.io.File
}

data class TripRecord(val tripId: String, val startedAtMs: Long, val endedAtMs: Long?, val segments: List<TripSegment>, val stopAdvised: Int, val declined: Int, val restCompleted: Int, val conditionStops: Int)
data class TripSegment(val type: String, val startMs: Long, val endMs: Long?)   // "drive" | "rest"
interface TripLogPort {
    suspend fun record(event: TripEvent, atMs: Long)
    fun today(): Flow<List<TripRecord>>
    /** S4 "최근 N일" 원본. 창 경계는 화면 모델(RecentSummary)이 자르므로 여기서는 그보다 넉넉히 접어 준다 */
    fun recent(days: Int): Flow<List<TripRecord>>
    suspend fun deleteAll()
}
