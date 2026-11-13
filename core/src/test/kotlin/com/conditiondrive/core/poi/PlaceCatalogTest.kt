package com.conditiondrive.core.poi

import com.conditiondrive.core.voice.Formats
import com.conditiondrive.core.voice.RepoFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** tools/poi_build.py 산출물 검증. 런타임은 이 파일만 믿고 장소를 음성으로 말한다 */
class PlaceCatalogTest {
    private val raw = RepoFiles.text("vehicle/src/main/assets/poi.json")
    private val file = PlaceCatalog.parseFile(raw)
    private val places = file.places

    @Test
    fun fileDeclaresVersionNoticeAndSources() {
        assertEquals(PlaceCatalog.SCHEMA_VERSION, file.schemaVersion)
        assertTrue("데모용" in file.notice && "갱신" in file.notice, file.notice)
        assertEquals(2, file.sources.size)
        assertTrue(file.sources.all { "data.go.kr" in it.url && it.count > 0 })
        assertEquals(places.size, file.sources.sumOf { it.count })
    }

    @Test
    fun everyPlaceHasUsableCoordinatesAndName() {
        assertTrue(places.size > 300, "장소가 너무 적음: ${places.size}")
        assertEquals(places.size, places.map { it.id }.toSet().size)
        for (place in places) {
            assertTrue(place.latitude in 33.0..38.7, "${place.id} ${place.latitude}")
            assertTrue(place.longitude in 124.5..131.0, "${place.id} ${place.longitude}")
            assertTrue(place.kind == PlaceKind.REST_AREA || place.kind == PlaceKind.DROWSY_SHELTER, place.id)
            val spoken = Formats.placeName(place.name)
            assertTrue(spoken.endsWith("휴게소") || spoken.endsWith("졸음쉼터"), "${place.id} [$spoken]")
            assertTrue(place.direction == null || place.direction == "양방향" || place.direction.endsWith("방향"), "${place.id} ${place.direction}")
        }
    }

    @Test
    fun coversBothKinds() {
        val byKind = places.groupingBy { it.kind }.eachCount()
        assertTrue((byKind[PlaceKind.REST_AREA] ?: 0) > 100, "$byKind")
        assertTrue((byKind[PlaceKind.DROWSY_SHELTER] ?: 0) > 100, "$byKind")
    }

    /** 시연 경로(경부고속도로)에서 실제 데이터로 후보가 나오는지 본다. 옥천 부근, 남쪽 진행 */
    @Test
    fun picksCandidateOnGyeongbuRoute() {
        val fix = GeoFix(36.42, 127.42, headingDeg = 135.0, atMs = 0)
        val candidates = StopPicker.pick(fix, 100.0, places, 20)
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.straightDistanceM in 1667..20_000 }, "$candidates")
        assertEquals(candidates.sortedBy { it.straightDistanceM }, candidates)
    }

    @Test
    fun rejectsOtherVersionAndBrokenFile() {
        assertEquals(emptyList(), PlaceCatalog.parse("""{"schemaVersion":999,"notice":"","sources":[],"places":[]}"""))
        assertEquals(emptyList(), PlaceCatalog.parse("not json"))
        assertEquals(emptyList(), PlaceCatalog.parse(""))
    }

    /**
     * P-15. 휴게소마다 원본 종류 값이 있어야 한다. 2026-11-07에 포털 표의 휴게소종류 열로 215곳을 모두 채웠다(poi-sources.md).
     * 값이 빠진 곳이 있으면 일부만 갱신한 파일이라 실패시킨다. 빠진 곳이 화물차휴게소면 앱이 일반 휴게소로 안내한다
     */
    @Test
    fun everyRestAreaDeclaresFacility() {
        val rows = Json.parseToJsonElement(raw).jsonObject["places"]!!.jsonArray.map { it.jsonObject }
        val rest = rows.filter { it["kind"]!!.jsonPrimitive.content == "REST_AREA" }
        assertEquals(215, rest.size)
        assertEquals(emptyList(), rest.filter { "facility" !in it }.map { it["id"]!!.jsonPrimitive.content })
        val byFacility = places.filter { it.kind == PlaceKind.REST_AREA }.groupingBy { it.facility }.eachCount()
        assertEquals(mapOf<String?, Int>("일반휴게소" to 187, "화물차휴게소" to TRUCK_IDS.size, "간이휴게소" to 7), byFacility)
        assertTrue(places.filter { it.kind == PlaceKind.DROWSY_SHELTER }.all { it.facility == null })
    }

    /** P-15. 원본 표(기준일 2026-09-17)의 화물차휴게소 21행이 그대로 표시돼 있어야 한다. 다시 만들어 수가 바뀌면 목록을 사람이 다시 본다 */
    @Test
    fun bundledFileMarksExactlyTheTruckRestAreas() {
        assertEquals(21, TRUCK_IDS.size)
        assertEquals(TRUCK_IDS, places.filter { it.truckRestArea }.map { it.id }.toSet())
        assertTrue(places.filter { it.truckRestArea }.all { it.kind == PlaceKind.REST_AREA && it.facility == "화물차휴게소" })
        // 널리 쓰이는 곳도 이 분류에 있다. 허용 목록을 만들 때(P-15 [뒤집기] ①) 이 시험의 목록도 고친다
        val names = places.filter { it.truckRestArea }.map { it.name }
        for (name in listOf("신탄진휴게소(서울방향)", "매송휴게소(서울방향)", "매송휴게소(목포방향)", "칠곡휴게소(부산방향)")) assertTrue(name in names, name)
    }

    /**
     * 실제 파일에서 화물차휴게소로 표시된 곳은 앱이 고르지 않는다.
     * 표시가 없을 때도 통과하던 빈 검사가 되지 않게, 종류를 지우면 그 곳이 실제로 골라지는 자리인지도 함께 본다
     */
    @Test
    fun bundledTruckRestAreasAreNeverPicked() {
        val trucks = places.filter { it.truckRestArea }
        assertEquals(TRUCK_IDS.size, trucks.size)
        for (truck in trucks) {
            val fix = GeoFix(truck.latitude + 3.0 / 111.195, truck.longitude, headingDeg = null, atMs = 0)
            val unmarked = places.map { if (it.id == truck.id) it.copy(facility = null) else it }
            assertTrue(StopPicker.pick(fix, 100.0, unmarked, 40, maxResults = places.size).any { it.place.id == truck.id }, "표시가 없으면 골라져야 함: ${truck.id}")
            assertTrue(StopPicker.pick(fix, 100.0, places, 40, maxResults = places.size).none { it.place.truckRestArea }, truck.id)
        }
    }

    @Test
    fun parsesPlaceFields() {
        val json = """{"schemaVersion":1,"notice":"n","sources":[],"places":[
            {"id":"a","name":"칠곡졸음쉼터(부산방향)","latitude":36.0,"longitude":128.4,"kind":"DROWSY_SHELTER","direction":"부산방향","address":"경북"}]}"""
        assertEquals(
            listOf(Place("a", "칠곡졸음쉼터(부산방향)", 36.0, 128.4, PlaceKind.DROWSY_SHELTER, "부산방향", "경북")),
            PlaceCatalog.parse(json),
        )
    }

    @Test
    fun parsesFacility() {
        val json = """{"schemaVersion":1,"notice":"n","sources":[],"places":[
            {"id":"a","name":"가휴게소","latitude":36.0,"longitude":128.4,"kind":"REST_AREA","facility":"화물차휴게소"},
            {"id":"b","name":"나휴게소","latitude":36.0,"longitude":128.4,"kind":"REST_AREA","facility":null},
            {"id":"c","name":"다휴게소","latitude":36.0,"longitude":128.4,"kind":"REST_AREA"}]}"""
        val parsed = PlaceCatalog.parse(json)
        assertEquals(listOf("화물차휴게소", null, null), parsed.map { it.facility })
        assertEquals(listOf(true, false, false), parsed.map { it.truckRestArea })
    }

    private companion object {
        /** 2026-11-07 포털 표에서 휴게소종류가 화물차휴게소인 21행을 poi_build.stable_id로 다시 계산한 id */
        val TRUCK_IDS = setOf(
            "ra-e1077847d392", "ra-34156308872b", "ra-b5db6372a861", "ra-7e4e6fc55c49", "ra-4620a88f82df", "ra-67b3c251b42a",
            "ra-fe44f7849061", "ra-439e38696f37", "ra-9d28f5d4c4d5", "ra-107da818e92d", "ra-31fb0ed01060", "ra-e2a7bbfde2fb",
            "ra-9627ddfb90af", "ra-42f0db8e87fd", "ra-53f0ac051013", "ra-d4990747548f", "ra-2516d38dfc57", "ra-b7dd8f06952d",
            "ra-d26d8cc1a6fa", "ra-18c500ec45a0", "ra-e7ad2b2b63d2",
        )
    }
}
