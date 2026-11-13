package com.conditiondrive.vehicle.platform

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NaviMode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import com.conditiondrive.core.poi.GeoFix
import com.conditiondrive.core.poi.Place
import com.conditiondrive.vehicle.port.GuideOutcome
import com.conditiondrive.vehicle.port.NaviPort
import com.conditiondrive.vehicle.port.RouteSnapshot
import com.conditiondrive.vehicle.port.TripEstimate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** 내비 쪽 상태를 RouteSnapshot으로 줄인다. 재탐색 순간의 깜박임을 여기서 거른다 */
internal class RouteTracker(private val publish: (RouteSnapshot?) -> Unit) {
    private var guiding = false
    private var remainingSec: Int? = null
    private var remainingM: Int? = null
    private var waypoints = 0
    private var known = false

    @Synchronized
    fun guiding(value: Boolean) {
        known = true
        guiding = value
        if (!value) {
            remainingSec = null
            remainingM = null
            waypoints = 0
        }
        emit()
    }

    /** 경로가 없을 때 0이 온다(실측). 0을 "도착 직전"으로 읽으면 안 되므로 없음으로 둔다 */
    @Synchronized
    fun remaining(durationSec: Int, distanceM: Int) {
        known = true
        remainingSec = durationSec.takeIf { it > 0 }
        remainingM = distanceM.takeIf { it > 0 }
        emit()
    }

    @Synchronized
    fun waypoints(count: Int) {
        known = true
        waypoints = count
        emit()
    }

    private fun emit() {
        if (known) publish(RouteSnapshot(guiding, remainingSec, remainingM, waypoints))
    }
}

/**
 * 10.2 NaviPort. 실측(에뮬레이터 v2.1.0):
 * - RouteStateInfo.isRouting은 안내 중에도 false다. naviMode == ROUTE_GUIDING을 쓴다.
 * - addWaypoint 뒤 onRouteCancelled → SAFE_GUIDING → onWaypointChanged(ADD) 순으로 오고 8~25초 걸린다.
 *   취소 이벤트는 재탐색의 일부라 그대로 믿으면 경로가 끊긴 것으로 오판한다.
 * - WaypointIndex.FIRST로 두 번 넣으면 뒤에 붙는다. 슬롯이 차거나 같은 곳을 넣으면 아무 이벤트도 없다.
 * - WaypointInfo의 좌표는 0.0이다. 같은 곳 판정은 poiId로 한다.
 */
internal class PleosNavi(
    private val context: Context,
    private val scope: CoroutineScope,
    private val guideTimeoutMs: Long = GUIDE_TIMEOUT_MS,
) : NaviPort {
    private val _available = MutableStateFlow(false)
    private val _route = MutableStateFlow<RouteSnapshot?>(null)
    private val _arrived = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    private val _waypointSupported = MutableStateFlow<Boolean?>(null)
    private val tracker = RouteTracker { _route.value = it }

    override val available: StateFlow<Boolean> = _available.asStateFlow()
    override val route: StateFlow<RouteSnapshot?> = _route.asStateFlow()
    override val waypointArrived: SharedFlow<Unit> = _arrived.asSharedFlow()
    val waypointSupported: StateFlow<Boolean?> = _waypointSupported.asStateFlow()

    @Volatile var currentLocation: CurrentLocationInfo? = null
        private set
    @Volatile var onLocation: () -> Unit = {}

    private var helper: NaviHelper? = null
    private var refresher: Job? = null
    private var cancelDebounce: Job? = null
    private val measureMutex = Mutex()
    private val guideMutex = Mutex()
    @Volatile private var pendingTrip: CompletableDeferred<TripInfo?>? = null
    @Volatile private var pendingGuide: CompletableDeferred<GuideOutcome>? = null
    @Volatile private var pendingList: CompletableDeferred<List<WaypointInfo>>? = null
    @Volatile private var guideInFlight = false
    @Volatile private var pendingPoiId: String? = null
    @Volatile private var cachedWaypoints: List<WaypointInfo> = emptyList()

    private val events = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            cancelDebounce?.cancel()
            tracker.guiding(true)
            tracker.remaining(routeStartInfo.duration, routeStartInfo.distance)
            if (!pendingIsWaypoint()) pendingGuide?.complete(GuideOutcome.ROUTE_STARTED)
        }

        override fun onRouteCancelled() {
            if (guideInFlight) return
            cancelDebounce?.cancel()
            cancelDebounce = scope.launch {
                delay(CANCEL_DEBOUNCE_MS)
                tracker.guiding(false)
            }
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val guiding = routeStateInfo.isRouting || routeStateInfo.naviMode == NaviMode.ROUTE_GUIDING
            if (guiding) {
                cancelDebounce?.cancel()
                tracker.guiding(true)
            } else if (!guideInFlight && cancelDebounce?.isActive != true) {
                tracker.guiding(false)
            }
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            tracker.remaining(drivingInfo.destination.duration, drivingInfo.destination.distance)
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            tracker.remaining(destinationInfo.remainTime, destinationInfo.remainDistance)
        }

        override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) {
            cachedWaypoints = waypointInfoList
            // 재탐색 중에는 잠시 빈 목록이 온다(실측). 진행 중인 요청이 있으면 개수에 반영하지 않는다
            if (!guideInFlight || waypointInfoList.isNotEmpty()) tracker.waypoints(waypointInfoList.size)
            pendingList?.complete(waypointInfoList)
            val id = pendingPoiId
            if (id != null && waypointInfoList.any { it.poiId == id }) pendingGuide?.complete(GuideOutcome.WAYPOINT_ADDED)
        }

        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            // 도착이나 삭제에도 온다. 추가 완료는 목록으로 다시 확인한다
            guarded("waypoint refresh", Unit) { helper?.getWaypointInfo() }
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            _arrived.tryEmit(Unit)
            guarded("waypoint refresh", Unit) { helper?.getWaypointInfo() }
        }

        override fun onTripInfo(tripInfo: TripInfo) {
            pendingTrip?.complete(tripInfo)
        }

        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            currentLocation = currentLocationInfo
            onLocation()
        }

        override fun onError(errorCode: NaviErrorCode) {
            logInfo("navi error: ${errorCode.name}")
            pendingTrip?.complete(null)
            pendingGuide?.complete(GuideOutcome.FAILED)
        }
    }

    fun start() {
        if (helper != null) return
        guarded("navi init", Unit) {
            val api = NaviHelper(context)
            helper = api
            api.initialize(
                onConnected = {
                    guarded("navi connected", Unit) {
                        api.addListener(events)
                        _available.value = true
                        _waypointSupported.value = true
                        requestState(api)
                        startRefresher(api)
                    }
                },
                onFailed = { t ->
                    logFailure("navi connect", t)
                    _available.value = false
                    _waypointSupported.value = false
                },
            )
        }
    }

    /** onDrivingInfo는 주행 상태가 바뀔 때만 온다. 안내 중에만 잔여 시간을 가끔 다시 받는다 */
    private fun startRefresher(api: NaviHelper) {
        refresher?.cancel()
        refresher = scope.launch {
            while (true) {
                delay(REFRESH_MS)
                if (_route.value?.isRouting == true) guarded("navi refresh", Unit) { api.getDestinationInfo() }
            }
        }
    }

    private fun requestState(api: NaviHelper) {
        api.getRouteStateInfo()
        api.getWaypointInfo()
        api.getDestinationInfo()
    }

    fun stop() {
        refresher?.cancel()
        refresher = null
        cancelDebounce?.cancel()
        val api = helper
        helper = null
        _available.value = false
        _route.value = null
        if (api == null) return
        guarded("navi remove", Unit) { api.removeListener(events) }
        guarded("navi release", Unit) { api.release() }
    }

    fun requestCurrentLocation() {
        guarded("navi location", Unit) { helper?.getCurrentLocationInfo() }
    }

    override suspend fun measure(place: Place): TripEstimate? = measureMutex.withLock {
        val api = helper
        if (api == null || !_available.value) return@withLock null
        val waiting = CompletableDeferred<TripInfo?>()
        pendingTrip = waiting
        val sent = guarded("navi trip", false) {
            api.requestTrip(TripRequest(place.latitude, place.longitude, place.id, place.name, POI_SUB_ID))
            true
        }
        val info = if (sent) withTimeoutOrNull(MEASURE_TIMEOUT_MS) { waiting.await() } else null
        pendingTrip = null
        info?.let { TripEstimate(it.distance, it.eta) }
    }

    /** 7장 F3 순서 */
    override suspend fun guideTo(place: Place): GuideOutcome = guideMutex.withLock {
        val api = helper
        if (api == null || !_available.value) return@withLock GuideOutcome.UNSUPPORTED
        guideInFlight = true
        try {
            if (_route.value?.isRouting == true) addWaypoint(api, place) else startRoute(api, place)
        } finally {
            guideInFlight = false
            pendingPoiId = null
            pendingGuide = null
        }
    }

    private suspend fun startRoute(api: NaviHelper, place: Place): GuideOutcome {
        val waiting = CompletableDeferred<GuideOutcome>()
        pendingGuide = waiting
        val sent = guarded("navi route", false) {
            api.requestRoute(
                RouteInfo(
                    latitude = place.latitude, longitude = place.longitude,
                    poiName = place.name, poiId = place.id, address = addressOf(place), poiSubId = POI_SUB_ID,
                ),
            )
            true
        }
        if (!sent) return GuideOutcome.FAILED
        return withTimeoutOrNull(guideTimeoutMs) { waiting.await() } ?: GuideOutcome.FAILED
    }

    private suspend fun addWaypoint(api: NaviHelper, place: Place): GuideOutcome {
        val current = freshWaypoints(api)
        if (current.any { it.poiId == place.id }) return GuideOutcome.ALREADY_WAYPOINT
        // 슬롯이 차면 내비가 응답 없이 무시한다(실측). 묻지 않고 바로 답한다
        if (current.size >= MAX_WAYPOINTS) return GuideOutcome.SLOTS_FULL
        val waiting = CompletableDeferred<GuideOutcome>()
        pendingGuide = waiting
        pendingPoiId = place.id
        val sent = guarded("navi waypoint", false) {
            api.addWaypoint(
                RequestWaypointInfo(
                    latitude = place.latitude, longitude = place.longitude, poiName = place.name,
                    waypointIndex = WaypointIndex.FIRST, poiId = place.id, address = addressOf(place), poiSubId = POI_SUB_ID,
                ),
            )
            true
        }
        if (!sent) return GuideOutcome.FAILED
        return withTimeoutOrNull(guideTimeoutMs) { waiting.await() } ?: GuideOutcome.FAILED
    }

    private suspend fun freshWaypoints(api: NaviHelper): List<WaypointInfo> {
        val waiting = CompletableDeferred<List<WaypointInfo>>()
        pendingList = waiting
        guarded("navi waypoints", Unit) { api.getWaypointInfo() }
        val list = withTimeoutOrNull(WAYPOINT_LIST_TIMEOUT_MS) { waiting.await() }
        pendingList = null
        return list ?: cachedWaypoints
    }

    override fun openSearch(keyword: String): Boolean {
        val api = helper ?: return false
        return guarded("navi search", false) {
            api.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword))
            true
        }
    }

    private fun pendingIsWaypoint() = pendingPoiId != null

    // 내비는 빈 문자열 주소를 거절할 수 있다. 필수 항목이라 이름으로 채운다
    private fun addressOf(place: Place) = place.address.ifBlank { place.name }

    fun currentFix(nowMs: Long): GeoFix? = currentLocation?.let {
        GeoFix(it.latitude, it.longitude, FixRules.heading(it.heading), nowMs)
    }

    companion object {
        /** F3-9. 실측 8~25초라 설계값 5초에서 늘렸다. 넘으면 V28을 말한 뒤 경유지가 들어가는 어긋남이 생긴다 */
        const val GUIDE_TIMEOUT_MS = 30_000L
        private const val MEASURE_TIMEOUT_MS = 5000L
        private const val WAYPOINT_LIST_TIMEOUT_MS = 3000L
        private const val CANCEL_DEBOUNCE_MS = 3000L
        private const val REFRESH_MS = 60_000L
        private const val MAX_WAYPOINTS = 2
        private const val POI_SUB_ID = "0"
    }
}

/** NaviHelper 현재 위치를 위치 공급자로 쓴다. 조회형이라 대체 경로일 때만 poll로 묻는다 */
internal class NaviLocationSource(private val navi: PleosNavi, private val nowMs: () -> Long) : LocationSource {
    override val via = com.conditiondrive.vehicle.port.LocationVia.NAVI
    override val latest: GeoFix? get() = navi.currentFix(nowMs())
    override fun activate() = Unit
    override fun deactivate() = Unit
    override fun poll() = navi.requestCurrentLocation()
}
