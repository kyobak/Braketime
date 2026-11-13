package com.conditiondrive.vehicle.runtime

import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.poi.Geo
import com.conditiondrive.core.poi.Place
import com.conditiondrive.core.poi.PlaceCandidate
import com.conditiondrive.core.poi.StopPicker
import com.conditiondrive.core.time.Clock
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.LocationPort
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.PlaceProvider
import com.conditiondrive.vehicle.port.PlaceQuery
import com.conditiondrive.vehicle.port.VehicleSignalsPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/** F3. 6.1 장소 해석과 7장 선택 알고리즘의 런타임 쪽(측정, 재검색, 경유지 추가). */
class StopGuidanceController(
    private val providers: List<PlaceProvider>,
    private val location: LocationPort,
    private val navi: NaviPort,
    private val signals: VehicleSignalsPort,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val policy: Policy = Policy.DEFAULT,
    // 음성과 같은 고속 판정을 쓴다(V-07). 조립(RuntimeGraph)이 VoiceQueue와 같은 것을 넘긴다
    private val highwayNow: () -> Boolean = { highwayLikely(signals.speedKmh.value) },
) {
    private val _current = MutableStateFlow<PlaceCandidate?>(null)
    /** 지금 안내하려는 장소. S1 Landscape도 이 값을 읽는다 */
    val current: StateFlow<PlaceCandidate?> = _current

    private var retryJob: Job? = null
    private var guidedPlaceId: String? = null
    // 멀어지는지 보려고 지금까지 가장 가까웠던 직선 거리를 둔다
    private var closestM: Int? = null

    fun start() = location.start()

    fun stop() {
        cancelRetry()
        location.stop()
    }

    /** measureRoute=false는 경로 측정을 건너뛴다. 혈당 정차 음성이 측정을 기다리느라 늦어지면 안 된다 */
    suspend fun resolve(measureRoute: Boolean = true): PlaceCandidate? {
        val found = search(measureRoute)
        setCurrent(found)
        return found
    }

    /** V09. 방향을 모르면 맞은편 시설을 이름으로 말할 수 있어 장소 없는 문구로 말한다(P8) */
    suspend fun resolveForGlucose(): PlaceCandidate? {
        if (location.fix.value?.headingDeg == null) {
            setCurrent(null)
            return null
        }
        return resolve(measureRoute = false)
    }

    /**
     * V10. 말하려는 장소가 아직 앞에 있는지 본다. 지나쳤거나 멀어지면 뒤쪽 장소를 "안내 중"이라고 계속 말하게 되므로 다시 찾는다.
     * 이미 넣은 경유지는 지우지 않는다.
     */
    suspend fun reconfirm(): PlaceCandidate? {
        val candidate = _current.value ?: return resolveForGlucose()
        val fix = location.fix.value ?: return candidate
        val place = candidate.place
        val meters = Geo.distanceM(fix.latitude, fix.longitude, place.latitude, place.longitude)
        val heading = fix.headingDeg
        val ahead = heading == null || bearingDiff(heading, Geo.bearingDeg(fix.latitude, fix.longitude, place.latitude, place.longitude)) <= STILL_AHEAD_DEG
        val closing = meters <= (closestM ?: meters) + MOVING_AWAY_MARGIN_M
        if (ahead && closing) {
            updateDistance(candidate, meters)
            return _current.value
        }
        if (candidate.place.id == guidedPlaceId) guidedPlaceId = null
        setCurrent(null)
        return resolveForGlucose()
    }

    /** 지금 말하는 장소가 실제 경유지에 들어갔는지. 아니면 "안내 중"이라고 말하지 않는다 */
    fun isGuided(place: PlaceCandidate?): Boolean {
        val route = navi.route.value
        if (route != null && !route.isRouting && route.waypointCount == 0) guidedPlaceId = null
        return place != null && place.place.id == guidedPlaceId
    }

    private fun setCurrent(candidate: PlaceCandidate?) {
        _current.value = candidate
        closestM = candidate?.straightDistanceM
    }

    private fun updateDistance(candidate: PlaceCandidate, meters: Int) {
        closestM = minOf(closestM ?: meters, meters)
        // 경로 거리는 다시 재지 않고 줄어든 직선 거리만큼 뺀다. 지금 값으로 다시 재면 음성이 늦어진다
        val route = candidate.routeDistanceM?.let { (it - (candidate.straightDistanceM - meters)).coerceAtLeast(meters) }
        _current.value = candidate.copy(straightDistanceM = meters, routeDistanceM = route)
    }

    private fun bearingDiff(a: Double, b: Double): Double = abs(((b - a) % 360 + 540) % 360 - 180)

    private suspend fun search(measureRoute: Boolean): PlaceCandidate? {
        val fix = location.fix.value ?: return null
        val speed = signals.speedKmh.value
        val highway = highwayNow()
        // 반경을 바깥 고리로 둔다. 멀리 있는 내장 장소보다 가까운 외부 결과가 낫고, 공급자 순서는 같은 반경 안에서만 뜻이 있다
        for (radiusKm in policy.POI_RADII_KM) {
            for (provider in providers) {
                val places = nearby(provider, PlaceQuery(fix, radiusKm, highway))
                val candidates = StopPicker.pick(fix, speed, places, radiusKm)
                if (candidates.isEmpty()) continue
                val chosen = if (measureRoute) {
                    StopPicker.firstWithinDetour(candidates) { measureRouteM(it, MEASURE_TIMEOUT_MS) }
                } else {
                    candidates.first()
                }
                if (chosen != null) return chosen
            }
        }
        return null
    }

    /** 외부 검색 실패는 내장 결과로 이어 간다(13장). 공급자 하나의 오류가 안내를 막으면 안 된다 */
    private suspend fun nearby(provider: PlaceProvider, query: PlaceQuery): List<Place> = try {
        provider.nearby(query)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun measureRouteM(place: Place, timeoutMs: Long): Int? {
        if (!navi.available.value) return null
        return try {
            withTimeoutOrNull(timeoutMs) { navi.measure(place) }?.distanceM
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            null
        }
    }

    /** F3-7~F3-10. 찾아 둔 장소가 있으면 그곳으로 간다. 들은 문장과 다른 곳으로 안내하면 안 된다 */
    suspend fun startGuidance(auto: Boolean): GuideOutcome {
        val found = _current.value ?: resolve(measureRoute = !auto) ?: return GuideOutcome.FAILED
        val route = navi.route.value
        if (route != null && !route.isRouting && route.waypointCount == 0) guidedPlaceId = null
        if (found.place.id == guidedPlaceId) return GuideOutcome.ALREADY_WAYPOINT
        if (!navi.available.value) return GuideOutcome.UNSUPPORTED
        val candidate = if (auto) verifiedForAuto(found) ?: return GuideOutcome.NOT_VERIFIED else found
        val outcome = try {
            withTimeoutOrNull(GUIDE_TIMEOUT_MS) { navi.guideTo(candidate.place) } ?: GuideOutcome.FAILED
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            GuideOutcome.FAILED
        }
        if (outcome in GUIDED) guidedPlaceId = candidate.place.id
        return outcome
    }

    /**
     * 혈당 자동 안내는 묻지 않고 경유지를 넣는다. 맞은편 차로 시설이면 혈당 기준 아래 운전자가 다음 나들목까지 더 달린다.
     * 경로로 재서 우회면 다음 후보로 넘어가고, 재지 못하면 방향 표기로 확인될 때만 넣는다. 판별이 안 되면 넣지 않는다(P19, P20).
     */
    private suspend fun verifiedForAuto(first: PlaceCandidate): PlaceCandidate? {
        var candidate = first
        repeat(AUTO_VERIFY_ATTEMPTS) {
            val fix = location.fix.value
            if (fix == null) return unverified()
            val routeM = measureRouteM(candidate.place, AUTO_MEASURE_TIMEOUT_MS)
            if (routeM != null && routeM <= candidate.straightDistanceM * policy.POI_MAX_DETOUR_RATIO) {
                return candidate.copy(routeDistanceM = routeM).also { setCurrent(it) }
            }
            if (routeM == null) {
                return candidate.takeIf { StopPicker.directionMatches(fix, it.place) == true } ?: unverified()
            }
            // 우회다. 측정하며 다음 후보를 찾는다
            candidate = resolve(measureRoute = true) ?: return unverified()
        }
        return unverified()
    }

    /** 방향을 모르면 V10U가 맞은편 시설을 이름으로 말할 수 있다. 방향을 알면 후보는 남겨 다음 V10 때 다시 시도한다 */
    private fun unverified(): PlaceCandidate? {
        if (location.fix.value?.headingDeg == null) setCurrent(null)
        return null
    }

    /** 6.1, P11, P12. 위치가 없으면 복귀 즉시, 있으면 30초마다 다시 찾는다 */
    fun startRetry(onFound: (PlaceCandidate) -> Unit) {
        cancelRetry()
        retryJob = scope.launch {
            while (true) {
                if (location.fix.value == null) {
                    location.fix.first { it != null }
                } else {
                    delay(policy.POI_RETRY_SEC * 1000)
                }
                val found = resolve()
                if (found != null) {
                    onFound(found)
                    return@launch
                }
            }
        }
    }

    fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
    }

    fun clearPlace() {
        setCurrent(null)
    }

    /** 표시하는 거리가 차가 움직인 만큼 줄어야 한다. 경로 측정 없이 직선으로만 다시 잰다 */
    fun refreshDistance() {
        val candidate = _current.value ?: return
        val fix = location.fix.value ?: return
        val place = candidate.place
        updateDistance(candidate, Geo.distanceM(fix.latitude, fix.longitude, place.latitude, place.longitude))
    }

    private companion object {
        const val MEASURE_TIMEOUT_MS = 5000L    // 10.1 measure
        const val AUTO_MEASURE_TIMEOUT_MS = 3000L  // 혈당 경로는 응답 없이 바로 넣으므로 오래 기다리지 않는다
        const val AUTO_VERIFY_ATTEMPTS = 2
        const val STILL_AHEAD_DEG = 90.0        // C-46보다 넓다. 옆으로 굽은 길에서 후보를 버리지 않게 한다
        const val MOVING_AWAY_MARGIN_M = 500
        const val GUIDE_TIMEOUT_MS = 30_000L    // F3-9. 실측 완료까지 9~25초라 5초면 V28 뒤에 경유지가 들어간다
        val GUIDED = setOf(GuideOutcome.WAYPOINT_ADDED, GuideOutcome.ROUTE_STARTED, GuideOutcome.ALREADY_WAYPOINT)
    }
}
