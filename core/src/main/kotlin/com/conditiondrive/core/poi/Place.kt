package com.conditiondrive.core.poi

import kotlinx.serialization.Serializable

@Serializable
enum class PlaceKind { REST_AREA, DROWSY_SHELTER, PARKING, CONVENIENCE }

@Serializable
data class Place(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val kind: PlaceKind,
    val direction: String? = null,   // 데이터에 있으면 "부산방향"
    val address: String = "",
    // 원본 휴게소 종류(tools/poi_build.py가 그대로 옮긴다). null은 종류를 모른다는 뜻이다. 졸음쉼터와 외부 검색 결과는 늘 null이다
    val facility: String? = null,
) {
    /**
     * 화물차휴게소인지. 일반 차량이 쓸 수 있는지 시설별로 확인하지 못해 앱이 스스로 고르는 안내에서 뺀다(P-15).
     * 원본 값의 표기가 바뀌어도 놓치지 않게 "화물"이 들어 있는지로 본다. 종류를 모르면 일반으로 본다
     */
    val truckRestArea: Boolean get() = facility?.contains(TRUCK_MARK) == true

    companion object {
        const val TRUCK_MARK = "화물"
    }
}

data class GeoFix(val latitude: Double, val longitude: Double, val headingDeg: Double?, val atMs: Long, val accuracyM: Double? = null)

/** routeDistanceM은 경로로 잰 값이다. 못 쟀으면 null이고 말할 때 "약"을 붙인다 */
data class PlaceCandidate(val place: Place, val straightDistanceM: Int, val routeDistanceM: Int? = null) {
    val distanceM: Int get() = routeDistanceM ?: straightDistanceM
}
