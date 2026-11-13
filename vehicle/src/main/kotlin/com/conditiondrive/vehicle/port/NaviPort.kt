package com.conditiondrive.vehicle.port

import com.conditiondrive.core.poi.Place
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

data class RouteSnapshot(val isRouting: Boolean, val remainingSec: Int?, val remainingM: Int?, val waypointCount: Int)
data class TripEstimate(val distanceM: Int, val etaSec: Int)
/** NOT_VERIFIED는 혈당 자동 안내가 진행 방향 쪽 장소인지 확인하지 못해 넣지 않은 경우다 */
enum class GuideOutcome { WAYPOINT_ADDED, ROUTE_STARTED, ALREADY_WAYPOINT, SLOTS_FULL, UNSUPPORTED, FAILED, NOT_VERIFIED }
interface NaviPort {
    val available: StateFlow<Boolean>
    val route: StateFlow<RouteSnapshot?>
    val waypointArrived: SharedFlow<Unit>
    suspend fun measure(place: Place): TripEstimate?   // requestTrip → onTripInfo. 직렬, 5초 제한
    suspend fun guideTo(place: Place): GuideOutcome    // 7장 F3 순서
    fun openSearch(keyword: String): Boolean           // Parked 사용자 요청 전용
}
