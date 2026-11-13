package com.conditiondrive.core.poi

import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.voice.Formats
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object StopPicker {
    // 정확히 60도인 곳이 부동소수 오차로 빠지지 않게 하는 여유. 위치 오차보다 훨씬 작다
    private const val BEARING_EPSILON_DEG = 1e-6
    private const val SAME_WAY_MAX_DEG = 90.0
    private const val OPPOSITE_MIN_DEG = 135.0

    /** C-46~C-48. 입력은 반경 내 전체, 출력은 직선 거리순 상위 maxResults. 화물차휴게소는 고르지 않는다(P-15) */
    fun pick(fix: GeoFix, speedKmh: Double?, places: List<Place>, radiusKm: Int, maxResults: Int = 3): List<PlaceCandidate> {
        val policy = Policy.DEFAULT
        val maxDistanceM = radiusKm * 1000
        val minDistanceM = minLeadM(speedKmh, policy)
        val heading = fix.headingDeg?.takeUnless { it.isNaN() || it.isInfinite() }

        val usable = if (heading == null) places else dropOppositeDirection(fix, heading, places)
        return usable.asSequence()
            // 쌍 판별이 끝난 뒤에 뺀다. 화물차휴게소도 같은 이름 짝의 방향을 알려 주므로, 먼저 빼면 맞은편 일반 휴게소가 짝 없는 시설로 남는다
            .filterNot { it.truckRestArea }
            .map { PlaceCandidate(it, Geo.distanceM(fix.latitude, fix.longitude, it.latitude, it.longitude)) }
            .filter { it.straightDistanceM in minDistanceM..maxDistanceM }
            .filter { heading == null || isAhead(fix, heading, it.place, policy) }
            .sortedWith(compareBy({ it.straightDistanceM }, { it.place.id }))
            .take(maxResults)
            .toList()
    }

    /**
     * 설계서 F3 ④. 경로 거리가 직선의 C-50배를 넘는 후보를 지우고 첫 후보를 고른다. 측정 실패(null)는 직선 거리로 진행한다.
     * inline이라 런타임이 suspend인 NaviPort.measure를 그대로 넘길 수 있다.
     */
    inline fun firstWithinDetour(candidates: List<PlaceCandidate>, measureRouteM: (Place) -> Int?): PlaceCandidate? {
        val ratio = Policy.DEFAULT.POI_MAX_DETOUR_RATIO
        for (candidate in candidates) {
            val routeM = measureRouteM(candidate.place)
            if (routeM == null || routeM <= candidate.straightDistanceM * ratio) return candidate.copy(routeDistanceM = routeM)
        }
        return null
    }

    /**
     * 시설의 방향 표기가 진행 방향과 맞는지. true면 맞고 false면 반대이고 null이면 판별할 수 없다.
     * 경유지를 스스로 넣는 혈당 경로가 경로 측정을 못 했을 때 쓴다. 맞다고 확인되지 않으면 넣지 않는다.
     */
    fun directionMatches(fix: GeoFix, place: Place): Boolean? {
        val heading = fix.headingDeg?.takeUnless { it.isNaN() || it.isInfinite() } ?: return null
        if (place.direction == DirectionTargets.BOTH_WAYS) return true
        val diff = angleToDirection(fix, heading, place) ?: return null
        return when {
            diff <= SAME_WAY_MAX_DEG -> true
            diff >= OPPOSITE_MIN_DEG -> false
            else -> null
        }
    }

    /**
     * 내장 POI에는 같은 이름의 서울방향, 부산방향이 따로 있고 좌표만으로는 어느 쪽 차로인지 알 수 없다(C-46은 방위만 본다).
     * 쌍이면 표기한 도시가 진행 방향에 더 가까운 쪽을 남기고, 혼자면 명백히 반대일 때만 뺀다.
     */
    private fun dropOppositeDirection(fix: GeoFix, heading: Double, places: List<Place>): List<Place> {
        val groups = places.groupBy { Formats.placeName(it.name) }
        return places.filter { place ->
            val mine = angleToDirection(fix, heading, place) ?: return@filter true
            val partners = groups[Formats.placeName(place.name)].orEmpty().filter { it.direction != place.direction && it.direction != DirectionTargets.BOTH_WAYS }
            val others = partners.map { angleToDirection(fix, heading, it) }
            when {
                partners.isEmpty() -> mine < OPPOSITE_MIN_DEG
                null in others -> true
                else -> mine <= others.filterNotNull().min()
            }
        }
    }

    private fun angleToDirection(fix: GeoFix, heading: Double, place: Place): Double? {
        val (lat, lon) = DirectionTargets.of(place.direction) ?: return null
        val bearing = Geo.bearingDeg(fix.latitude, fix.longitude, lat, lon)
        return abs(((bearing - heading) % 360 + 540) % 360 - 180)
    }

    private fun minLeadM(speedKmh: Double?, policy: Policy): Int {
        val leadM = (speedKmh ?: 0.0).takeUnless { it.isNaN() }?.let { abs(it) / 3.6 * policy.POI_MIN_LEAD_SEC } ?: 0.0
        return max(policy.POI_MIN_LEAD_FLOOR_M.toDouble(), leadM).roundToInt()
    }

    private fun isAhead(fix: GeoFix, heading: Double, place: Place, policy: Policy): Boolean {
        val bearing = Geo.bearingDeg(fix.latitude, fix.longitude, place.latitude, place.longitude)
        val diff = abs(((bearing - heading) % 360 + 540) % 360 - 180)
        return diff <= policy.POI_BEARING_DEG + BEARING_EPSILON_DEG
    }
}

/** 시설 방향 표기의 도시 좌표. 진행 방향과 비교하는 데만 쓰는 대략값이다. poi.json의 표기를 빠짐없이 담는다(StopPickerTest) */
internal object DirectionTargets {
    const val BOTH_WAYS = "양방향"
    private const val SUFFIX = "방향"

    private val CITIES: Map<String, Pair<Double, Double>> = mapOf(
        "서울" to (37.56 to 126.98), "부산" to (35.18 to 129.08), "서부산" to (35.15 to 128.99), "순천" to (34.95 to 127.49),
        "목포" to (34.81 to 126.39), "통영" to (34.85 to 128.43), "창원" to (35.23 to 128.68), "하남" to (37.54 to 127.21),
        "양평" to (37.49 to 127.49), "천안" to (36.81 to 127.11), "강릉" to (37.75 to 128.88), "춘천" to (37.88 to 127.73),
        "인천" to (37.46 to 126.71), "영덕" to (36.42 to 129.37), "청주" to (36.64 to 127.49), "대구" to (35.87 to 128.60),
        "동대구" to (35.88 to 128.63), "달서" to (35.83 to 128.53), "대전" to (36.35 to 127.38), "포항" to (36.02 to 129.34),
        "평택" to (36.99 to 127.11), "제천" to (37.13 to 128.19), "일산" to (37.66 to 126.77), "양양" to (38.08 to 128.62),
        "광주" to (35.16 to 126.85), "광주광역시" to (35.16 to 126.85), "장수" to (35.65 to 127.52), "속초" to (38.21 to 128.59),
        "삼척" to (37.45 to 129.17), "논산" to (36.19 to 127.10), "익산" to (35.95 to 126.96), "회덕" to (36.39 to 127.44),
        "울산" to (35.54 to 129.31), "영암" to (34.80 to 126.70), "당진" to (36.89 to 126.63), "완주" to (35.90 to 127.16),
        "남이" to (36.55 to 127.46), "공주" to (36.45 to 127.12), "구리" to (37.59 to 127.13), "판교" to (37.39 to 127.11),
        "고창" to (35.44 to 126.70), "포천" to (37.89 to 127.20), "새만금" to (35.80 to 126.50), "담양" to (35.32 to 126.99),
        "서천" to (36.08 to 126.69), "함양" to (35.52 to 127.73), "파주" to (37.76 to 126.78), "언양" to (35.56 to 129.13),
        "세종" to (36.48 to 127.29), "양주" to (37.79 to 127.05), "기장" to (35.24 to 129.22), "산인" to (35.30 to 128.40),
        "산내" to (36.28 to 127.46), "전주" to (35.82 to 127.15), "무안" to (34.99 to 126.48),
    )

    fun of(direction: String?): Pair<Double, Double>? = direction?.removeSuffix(SUFFIX)?.let(CITIES::get)
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Int {
        val phiA = Math.toRadians(aLat)
        val phiB = Math.toRadians(bLat)
        val dPhi = phiB - phiA
        val dLambda = Math.toRadians(bLon - aLon)
        val h = sin(dPhi / 2).pow(2) + cos(phiA) * cos(phiB) * sin(dLambda / 2).pow(2)
        return (2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))).roundToInt()
    }

    /** 0 이상 360 미만. 0이 북, 90이 동 */
    fun bearingDeg(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val phiA = Math.toRadians(aLat)
        val phiB = Math.toRadians(bLat)
        val dLambda = Math.toRadians(bLon - aLon)
        val y = sin(dLambda) * cos(phiB)
        val x = cos(phiA) * sin(phiB) - sin(phiA) * cos(phiB) * cos(dLambda)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
