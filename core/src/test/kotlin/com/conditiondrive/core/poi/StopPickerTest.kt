package com.conditiondrive.core.poi

import com.conditiondrive.core.engine.Policy
import com.conditiondrive.core.voice.Formats
import com.conditiondrive.core.voice.PrivacyContext
import com.conditiondrive.core.voice.RepoFiles
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** test-scenarios.md P표. P11~P16, P18의 경유지 동작은 NaviPort가 있는 vehicle.runtime 테스트 몫이다 */
class StopPickerTest {
    // 경부고속도로 옥천 부근, 남쪽 진행
    private val lat = 36.30
    private val lon = 127.60
    private val fix = GeoFix(lat, lon, headingDeg = 180.0, atMs = 0)

    private fun placeAt(bearingDeg: Double, distanceM: Double, id: String = "p$bearingDeg-$distanceM"): Place {
        val (la, lo) = destination(lat, lon, bearingDeg, distanceM)
        return Place(id, "쉼터$id", la, lo, PlaceKind.DROWSY_SHELTER)
    }

    private fun destination(aLat: Double, aLon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val delta = distanceM / 6_371_008.8
        val theta = Math.toRadians(bearingDeg)
        val phi1 = Math.toRadians(aLat)
        val phi2 = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lambda = Math.toRadians(aLon) + atan2(sin(theta) * sin(delta) * cos(phi1), cos(delta) - sin(phi1) * sin(phi2))
        return Math.toDegrees(phi2) to Math.toDegrees(lambda)
    }

    private fun ids(result: List<PlaceCandidate>) = result.map { it.place.id }

    @Test
    fun p1_selectsPlaceAhead() {
        val shelter = placeAt(180.0, 3200.0, "ahead")
        val result = StopPicker.pick(fix, 80.0, listOf(shelter), 5)
        assertEquals(listOf("ahead"), ids(result))
        assertEquals(3200.0, result.single().straightDistanceM.toDouble(), 2.0)
    }

    @Test
    fun p2_prefersAheadOverCloserBehind() {
        val result = StopPicker.pick(fix, 80.0, listOf(placeAt(0.0, 1000.0, "behind"), placeAt(180.0, 3200.0, "ahead")), 5)
        assertEquals(listOf("ahead"), ids(result))
    }

    @Test
    fun p3_excludesBearingDifference70() {
        assertEquals(emptyList(), StopPicker.pick(fix, 80.0, listOf(placeAt(180.0 + 70, 3000.0), placeAt(180.0 - 70, 3000.0)), 5))
    }

    @Test
    fun p4_includesBearingDifferenceExactly60() {
        val result = StopPicker.pick(fix, 80.0, listOf(placeAt(180.0 + 60, 3000.0, "right"), placeAt(180.0 - 60, 3000.0, "left")), 5)
        assertEquals(setOf("right", "left"), ids(result).toSet())
        assertEquals(emptyList(), StopPicker.pick(fix, 80.0, listOf(placeAt(180.0 + 60.5, 3000.0)), 5))
    }

    @Test
    fun p5_excludesInsideMinimumLeadAt80Kmh() {
        assertEquals(emptyList(), StopPicker.pick(fix, 80.0, listOf(placeAt(180.0, 1000.0)), 5))
        assertEquals(emptyList(), StopPicker.pick(fix, 80.0, listOf(placeAt(180.0, 1320.0)), 5))
        assertEquals(1, StopPicker.pick(fix, 80.0, listOf(placeAt(180.0, 1340.0)), 5).size)
    }

    @Test
    fun p6_nullSpeedUsesFloor500m() {
        assertEquals(emptyList(), StopPicker.pick(fix, null, listOf(placeAt(180.0, 400.0)), 5))
        assertEquals(1, StopPicker.pick(fix, null, listOf(placeAt(180.0, 510.0)), 5).size)
        // 저속도 하한을 넘지 못한다
        assertEquals(emptyList(), StopPicker.pick(fix, 10.0, listOf(placeAt(180.0, 400.0)), 5))
    }

    @Test
    fun p7_expandsRadiusInPolicyOrder() {
        assertEquals(listOf(5, 10, 20, 40), Policy.DEFAULT.POI_RADII_KM)
        val places = listOf(placeAt(180.0, 8000.0, "eight"))
        val found = Policy.DEFAULT.POI_RADII_KM.firstOrNull { StopPicker.pick(fix, 80.0, places, it).isNotEmpty() }
        assertEquals(10, found)
    }

    @Test
    fun p8_nullHeadingSkipsDirectionFilter() {
        val noHeading = fix.copy(headingDeg = null)
        assertEquals(listOf("behind"), ids(StopPicker.pick(noHeading, 80.0, listOf(placeAt(0.0, 3000.0, "behind")), 5)))
        // 최소 거리는 방향 없이도 유지한다
        assertEquals(emptyList(), StopPicker.pick(noHeading, 80.0, listOf(placeAt(0.0, 1000.0)), 5))
        // NaN도 방향 없음과 같다
        assertEquals(1, StopPicker.pick(fix.copy(headingDeg = Double.NaN), 80.0, listOf(placeAt(0.0, 3000.0)), 5).size)
    }

    @Test
    fun p9_detourOver2xIsRemovedAndNextCandidateUsed() {
        val first = PlaceCandidate(placeAt(180.0, 3000.0, "first"), 3000)
        val second = PlaceCandidate(placeAt(180.0, 4000.0, "second"), 4000)
        val measured = mutableListOf<String>()
        val chosen = StopPicker.firstWithinDetour(listOf(first, second)) { place ->
            measured += place.id
            if (place.id == "first") 6300 else 5000   // 2.1배, 1.25배
        }
        assertEquals("second", chosen?.place?.id)
        assertEquals(listOf("first", "second"), measured)

        // 정확히 2배는 "넘으면 지운다"에 해당하지 않는다
        assertEquals("first", StopPicker.firstWithinDetour(listOf(first)) { 6000 }?.place?.id)
        assertNull(StopPicker.firstWithinDetour(listOf(first)) { 6001 })
        assertNull(StopPicker.firstWithinDetour(emptyList()) { 1 })
    }

    @Test
    fun p10_measureFailureProceedsWithStraightDistance() {
        val first = PlaceCandidate(placeAt(180.0, 3000.0, "first"), 3000)
        assertEquals(first, StopPicker.firstWithinDetour(listOf(first, first)) { null })
    }

    @Test
    fun p11_nothingFoundUsesNoPlaceVariant() {
        val empty = Policy.DEFAULT.POI_RADII_KM.flatMap { StopPicker.pick(fix, 80.0, listOf(placeAt(0.0, 3000.0)), it) }
        assertEquals(emptyList(), empty)
        assertEquals(VoiceId.V04N, VoiceCatalog.resolveNoPlace(VoiceId.V04))
    }

    @Test
    fun p17_placeNameLosesParenthesesInVoice() {
        assertEquals("칠곡졸음쉼터", Formats.placeName("칠곡졸음쉼터(부산방향)"))
        val text = VoiceCatalog.render(
            VoiceId.V09, VoiceVars(placeName = "칠곡졸음쉼터(부산방향)"), PrivacyContext(passengerPresentOrUnknown = false),
        )
        assertTrue("칠곡졸음쉼터까지" in text && "부산방향" !in text, text)
    }

    @Test
    fun resultIsSortedByDistanceAndLimitedToThree() {
        val places = listOf(5000.0, 2000.0, 4000.0, 3000.0).map { placeAt(180.0, it, "d$it") }
        assertEquals(listOf("d2000.0", "d3000.0", "d4000.0"), ids(StopPicker.pick(fix, 80.0, places, 5)))
        assertEquals(listOf("d2000.0"), ids(StopPicker.pick(fix, 80.0, places, 5, maxResults = 1)))
    }

    @Test
    fun radiusIsAnUpperBound() {
        assertEquals(emptyList(), StopPicker.pick(fix, 80.0, listOf(placeAt(180.0, 5100.0)), 5))
        assertEquals(1, StopPicker.pick(fix, 80.0, listOf(placeAt(180.0, 4900.0)), 5).size)
    }

    /** 진행 방향이 0도 근처여도 각도 차를 짧은 쪽으로 잰다 */
    @Test
    fun bearingDifferenceWrapsAroundNorth() {
        val north = fix.copy(headingDeg = 350.0)
        assertEquals(1, StopPicker.pick(north, 80.0, listOf(placeAt(40.0, 3000.0)), 5).size)
        assertEquals(0, StopPicker.pick(north, 80.0, listOf(placeAt(60.0, 3000.0)), 5).size)
        assertEquals(1, StopPicker.pick(fix.copy(headingDeg = -10.0), 80.0, listOf(placeAt(40.0, 3000.0)), 5).size)
    }

    @Test
    fun tiesAreStableById() {
        val a = Place("a", "가", lat - 0.03, lon, PlaceKind.DROWSY_SHELTER)
        val b = Place("b", "나", lat - 0.03, lon, PlaceKind.DROWSY_SHELTER)
        assertEquals(listOf("a", "b"), ids(StopPicker.pick(fix, 80.0, listOf(b, a), 5)))
    }

    /** 내장 POI의 방향 표기를 빠짐없이 판별할 수 있어야 한다. 모르는 표기는 혈당 자동 안내에서 경유지를 넣지 못한다 */
    @Test
    fun everyBundledDirectionLabelIsKnown() {
        val places = PlaceCatalog.parse(RepoFiles.text("vehicle/src/main/assets/poi.json"))
        val unknown = places.mapNotNull { it.direction }.toSet()
            .filter { it != DirectionTargets.BOTH_WAYS && DirectionTargets.of(it) == null }
        assertEquals(emptyList(), unknown)
    }

    /** 실제 데이터로 재현: 남행 중 같은 이름 쌍이면 서울방향을 고르면 안 된다. 죽암휴게소(서울방향)가 더 가까워도 부산방향이다 */
    @Test
    fun southboundNeverPicksSeoulSideOfPair() {
        val places = PlaceCatalog.parse(RepoFiles.text("vehicle/src/main/assets/poi.json"))
        val pairs = places.groupBy { Formats.placeName(it.name) }.filterValues { group ->
            group.any { it.direction == "서울방향" } && group.any { it.direction == "부산방향" }
        }
        assertTrue(pairs.size >= 10, "쌍을 찾지 못함: ${pairs.size}")
        val wrong = pairs.filter { (_, group) ->
            val busanSide = group.first { it.direction == "부산방향" }
            // 부산방향 시설 3km 북쪽에서 남쪽으로 100km/h로 달린다
            val fix = GeoFix(busanSide.latitude + 3.0 / 111.195, busanSide.longitude, 180.0, 0)
            StopPicker.pick(fix, 100.0, group, 5).any { it.place.direction == "서울방향" }
        }.keys
        assertEquals(emptySet(), wrong)
    }

    @Test
    fun chukamPairPicksDrivingSide() {
        val places = listOf(
            Place("s", "죽암휴게소(서울방향)", 36.50 - 1.9 / 111.195, 127.45, PlaceKind.REST_AREA, "서울방향"),
            Place("b", "죽암휴게소(부산방향)", 36.50 - 3.0 / 111.195, 127.45, PlaceKind.REST_AREA, "부산방향"),
        )
        val picked = StopPicker.pick(GeoFix(36.50, 127.45, 180.0, 0), 100.0, places, 5)
        assertEquals(listOf("b"), picked.map { it.place.id })
    }

    @Test
    fun loneFacilityFacingTheOtherWayIsDropped() {
        val place = Place("s", "입장휴게소(서울방향)", 36.50 - 3.0 / 111.195, 127.45, PlaceKind.REST_AREA, "서울방향")
        assertTrue(StopPicker.pick(GeoFix(36.50, 127.45, 180.0, 0), 80.0, listOf(place), 5).isEmpty())
    }

    /** P23. 화물차휴게소는 더 가까워도 고르지 않는다. 종류를 모르는 곳(null)은 지금처럼 고른다 */
    @Test
    fun p23_truckRestAreaIsNeverPicked() {
        val truck = placeAt(180.0, 2000.0, "truck").copy(kind = PlaceKind.REST_AREA, facility = "화물차휴게소")
        val general = placeAt(180.0, 3000.0, "general").copy(kind = PlaceKind.REST_AREA, facility = "일반휴게소")
        val unknown = placeAt(180.0, 4000.0, "unknown").copy(kind = PlaceKind.REST_AREA)
        assertEquals(listOf("general", "unknown"), ids(StopPicker.pick(fix, 80.0, listOf(truck, general, unknown), 5)))
        assertEquals(emptyList(), ids(StopPicker.pick(fix, 80.0, listOf(truck), 5)), "화물차휴게소뿐이면 장소 없음(N·H 변형)")
        assertEquals(emptyList(), ids(StopPicker.pick(fix.copy(headingDeg = null), 80.0, listOf(truck), 5)), "방향을 몰라도 뺀다")
    }

    @Test
    fun p23_truckMarkIsReadFromAnySpelling() {
        assertTrue(Place("a", "가", 0.0, 0.0, PlaceKind.REST_AREA, facility = "화물차휴게소").truckRestArea)
        assertTrue(Place("a", "가", 0.0, 0.0, PlaceKind.REST_AREA, facility = "화물차 전용").truckRestArea)
        assertTrue(!Place("a", "가", 0.0, 0.0, PlaceKind.REST_AREA, facility = "일반휴게소").truckRestArea)
        assertTrue(!Place("a", "가", 0.0, 0.0, PlaceKind.REST_AREA).truckRestArea)
    }

    /**
     * 화물차휴게소도 같은 이름 짝의 방향 판별에는 쓴다. 남행에서 광주방향(진행 쪽)이 화물차휴게소이고 포항방향이 일반이면,
     * 포항방향은 맞은편이라 빠져야 한다. 화물차휴게소를 먼저 빼면 포항방향이 짝 없는 시설(73°)로 남아 고르게 된다
     */
    @Test
    fun p23_truckPartnerStillMarksTheOtherSide() {
        val base = GeoFix(36.50, 127.45, 180.0, 0)
        val drivingSide = Place("g", "가휴게소(광주방향)", 36.50 - 3.0 / 111.195, 127.45, PlaceKind.REST_AREA, "광주방향", facility = "화물차휴게소")
        val otherSide = Place("p", "가휴게소(포항방향)", 36.50 - 3.1 / 111.195, 127.45, PlaceKind.REST_AREA, "포항방향", facility = "일반휴게소")
        assertEquals(listOf("p"), StopPicker.pick(base, 80.0, listOf(otherSide), 5).map { it.place.id }, "짝이 없으면 남는 위치다")
        assertEquals(emptyList(), StopPicker.pick(base, 80.0, listOf(drivingSide, otherSide), 5).map { it.place.id })
    }

    @Test
    fun directionMatchesReportsWhatItKnows() {
        val south = GeoFix(36.5, 127.45, 170.0, 0)
        assertEquals(true, StopPicker.directionMatches(south, Place("a", "가(부산방향)", 36.4, 127.45, PlaceKind.REST_AREA, "부산방향")))
        assertEquals(false, StopPicker.directionMatches(south, Place("b", "나(서울방향)", 36.4, 127.45, PlaceKind.REST_AREA, "서울방향")))
        assertEquals(true, StopPicker.directionMatches(south, Place("c", "다(양방향)", 36.4, 127.45, PlaceKind.REST_AREA, "양방향")))
        assertEquals(null, StopPicker.directionMatches(south, Place("d", "라", 36.4, 127.45, PlaceKind.REST_AREA)))
        assertEquals(null, StopPicker.directionMatches(south.copy(headingDeg = null), Place("a", "가(부산방향)", 36.4, 127.45, PlaceKind.REST_AREA, "부산방향")))
    }
}
