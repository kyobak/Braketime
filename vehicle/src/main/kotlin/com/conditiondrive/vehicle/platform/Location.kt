package com.conditiondrive.vehicle.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import com.conditiondrive.core.poi.GeoFix
import com.conditiondrive.vehicle.port.LocationPort
import com.conditiondrive.vehicle.port.LocationVia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 값 하나를 믿을지 정한다. 내장 POI가 국내뿐이라 국외 좌표는 위치 없음과 같다 */
internal object FixRules {
    private const val MIN_LAT = 33.0
    private const val MAX_LAT = 39.0
    private const val MIN_LON = 124.0
    private const val MAX_LON = 132.0
    const val MAX_AGE_MS = 120_000L

    /**
     * 실측: 에뮬레이터의 FusedLocation은 지도와 달리 37.42, -122.08(기본값)을 준다.
     * 이 값을 쓰면 후보가 영원히 없어 N 변형만 나오므로 다음 공급자로 넘긴다.
     */
    fun usable(fix: GeoFix?, nowMs: Long): Boolean =
        fix != null && fix.latitude in MIN_LAT..MAX_LAT && fix.longitude in MIN_LON..MAX_LON && nowMs - fix.atMs <= MAX_AGE_MS

    /** 실측: 정지 중이나 모를 때 정확히 0이 온다. 그대로 쓰면 StopPicker가 북쪽만 남긴다 */
    fun heading(raw: Double?): Double? = raw?.takeIf { it != 0.0 && !it.isNaN() }
}

/** 위치 공급자 하나. 윗 순위가 값을 주면 아랫 순위는 꺼 둔다 */
internal interface LocationSource {
    val via: LocationVia
    val latest: GeoFix?
    fun activate()
    fun deactivate()
    /** 콜백이 없는 공급자의 조회 기회. 대체 경로일 때만 불린다 */
    fun poll() {}
}

/** 10.2 체인: FusedLocation → NaviHelper 현재 위치 → LocationManager → null */
internal class ChainLocation(
    private val sources: List<LocationSource>,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val tickMs: Long = 15_000,
) : LocationPort {
    private val _fix = MutableStateFlow<GeoFix?>(null)
    override val fix: StateFlow<GeoFix?> = _fix.asStateFlow()
    private val _via = MutableStateFlow(LocationVia.NONE)
    /** 지금 값을 주는 공급자. S5가 대체 경로를 FusedLocation처럼 말하지 않게 한다 */
    val via: StateFlow<LocationVia> = _via.asStateFlow()
    private var job: Job? = null

    override fun start() {
        if (job != null) return
        job = scope.launch {
            while (true) {
                evaluate(poll = true)
                delay(tickMs)
            }
        }
    }

    /**
     * 공급자가 새 값을 받으면 다음 tick까지 기다리지 않고 알린다.
     * 여기서 다시 poll하면 조회 → 응답 → 조회가 무한히 돈다(실측으로 잡은 버그). poll은 tick에서만 한다.
     */
    fun onSourceUpdated() {
        if (job != null) evaluate(poll = false)
    }

    @Synchronized
    private fun evaluate(poll: Boolean) {
        val now = nowMs()
        val index = sources.indexOfFirst { FixRules.usable(it.latest, now) }
        // 믿을 값이 있는 곳까지만 켠다. 없으면 전부 켠다
        val keep = if (index >= 0) index else sources.lastIndex
        sources.forEachIndexed { i, source ->
            if (i <= keep) {
                source.activate()
                if (poll && i > 0) source.poll()
            } else {
                source.deactivate()
            }
        }
        _fix.value = if (index >= 0) sources[index].latest else null
        _via.value = if (index >= 0) sources[index].via else LocationVia.NONE
    }

    override fun stop() {
        job?.cancel()
        job = null
        sources.forEach { guarded("location stop", Unit) { it.deactivate() } }
        _fix.value = null
        _via.value = LocationVia.NONE
    }
}

internal class FusedLocationSource(
    private val context: Context,
    private val nowMs: () -> Long,
    private val onUpdated: () -> Unit,
) : LocationSource {
    override val via = LocationVia.FUSED
    @Volatile override var latest: GeoFix? = null
        private set
    private var sdk: FusedLocation? = null
    private var initialized = false
    private var registered = false

    private val callback = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            // 10.2: null은 유효한 위치가 아니다. 직전 값은 만료 규칙에 맡긴다
            location ?: return
            latest = GeoFix(
                location.latitude, location.longitude, FixRules.heading(location.heading.toDouble()),
                nowMs(), location.confidence.toDouble(),
            )
            onUpdated()
        }

        override fun onError(exception: Exception) {
            logFailure("fused callback", exception)
            latest = null
            onUpdated()
        }
    }

    override fun activate() {
        if (registered) return
        // dangerous 권한이다. 없으면 호출 즉시 SecurityException이 나므로(실측) 먼저 본다. 허용되면 다음 tick에 살아난다
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) return
        guarded("fused start", Unit) {
            val api = sdk ?: FusedLocation(context).also { sdk = it }
            if (!api.isSupported()) return
            if (!initialized) {
                api.initialize()
                initialized = true
            }
            api.registerFusedLocationCallback(callback)
            registered = true
            api.getLastKnownLocation(callback)
        }
    }

    override fun deactivate() {
        val api = sdk ?: return
        if (registered) guarded("fused unregister", Unit) { api.unregisterFusedLocationCallback(callback) }
        registered = false
        if (initialized) guarded("fused release", Unit) { api.release() }
        initialized = false
        sdk = null
        latest = null
    }

    private companion object {
        const val PERMISSION = "pleos.car.permission.FUSED_LOCATION"
    }
}

/** LocationManager 대체. 실측: gps, fused, passive 제공자가 있고 위치 권한이 필요하다 */
internal class AndroidLocationSource(
    private val context: Context,
    private val nowMs: () -> Long,
    private val onUpdated: () -> Unit,
) : LocationSource {
    override val via = LocationVia.DEVICE
    @Volatile override var latest: GeoFix? = null
        private set
    private var active = false
    private val manager get() = context.getSystemService(LocationManager::class.java)
    private val listener = LocationListener { location -> update(location) }

    private fun update(location: Location) {
        val heading = if (location.hasBearing()) location.bearing.toDouble() else null
        val accuracy = if (location.hasAccuracy()) location.accuracy.toDouble() else null
        latest = GeoFix(location.latitude, location.longitude, FixRules.heading(heading), nowMs(), accuracy)
        onUpdated()
    }

    override fun activate() {
        if (active) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        guarded("lm start", Unit) {
            val m = manager ?: return
            val provider = LocationManager.GPS_PROVIDER.takeIf { it in m.allProviders } ?: return
            m.requestLocationUpdates(provider, 5000L, 0f, listener, Looper.getMainLooper())
            active = true
        }
    }

    override fun deactivate() {
        if (active) guarded("lm stop", Unit) { manager?.removeUpdates(listener) }
        active = false
        latest = null
    }
}
