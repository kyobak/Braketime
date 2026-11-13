package com.conditiondrive.vehicle.poi

import com.conditiondrive.core.poi.PlaceKind
import com.conditiondrive.vehicle.port.PlaceQuery
import com.conditiondrive.vehicle.runtime.TEST_FIX
import com.conditiondrive.vehicle.runtime.placeNorth
import com.conditiondrive.vehicle.runtime.placeSouth
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceProvidersTest {
    private val server = MockWebServer()

    @Before
    fun up() = server.start()

    @After
    fun down() = server.close()

    private fun poiJson(): String {
        val candidates = listOf(File("src/main/assets/poi.json"), File("vehicle/src/main/assets/poi.json"))
        return candidates.first { it.exists() }.readText()
    }

    @Test
    fun `F3-6 내장 POI를 반경으로 거른다`() = runBlocking {
        val provider = BundledPlaceProvider { poiJson() }
        val near = provider.nearby(PlaceQuery(TEST_FIX, 40, highwayLikely = true))
        val all = provider.nearby(PlaceQuery(TEST_FIX, 1000, highwayLikely = true))
        assertTrue(near.isNotEmpty())
        assertTrue(near.size < all.size)
        assertEquals(468, all.size)
    }

    @Test
    fun `내장 POI가 깨져도 예외 없이 빈 목록`() = runBlocking {
        assertTrue(BundledPlaceProvider { "{깨짐" }.nearby(PlaceQuery(TEST_FIX, 40, false)).isEmpty())
    }

    @Test
    fun `시나리오 공급자는 반경 안의 장소만 돌려준다`() = runBlocking {
        val provider = ScriptedPlaceProvider(listOf(placeSouth("a", 3.2), placeNorth("b", 30.0)))
        assertEquals(listOf("a"), provider.nearby(PlaceQuery(TEST_FIX, 5, true)).map { it.id })
    }

    private fun kakao(consent: Boolean = true, key: String? = "test-key") =
        KakaoPlaceProvider(OkHttpClient(), key, { consent }, server.url("/"))

    @Test
    fun `카카오는 동의와 키가 없으면 호출하지 않는다`() = runBlocking {
        assertTrue(kakao(consent = false).nearby(PlaceQuery(TEST_FIX, 5, true)).isEmpty())
        assertTrue(kakao(key = null).nearby(PlaceQuery(TEST_FIX, 5, true)).isEmpty())
        assertTrue(kakao(key = "").nearby(PlaceQuery(TEST_FIX, 5, true)).isEmpty())
        assertEquals(0, server.requestCount)
    }

    private val documents = """{"documents":[
        {"id":"1","place_name":"칠곡휴게소","x":"128.42","y":"36.01","category_group_code":"","road_address_name":"경북 칠곡","address_name":""},
        {"id":"2","place_name":"깨진좌표","x":"abc","y":"36.01"}
    ]}"""

    @Test
    fun `고속도로면 쉼터 키워드로 찾고 인증 헤더를 보낸다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(documents).build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"documents":[{"id":"3","place_name":"칠곡졸음쉼터","x":"128.5","y":"36.0"}]}""").build())
        val places = kakao().nearby(PlaceQuery(TEST_FIX, 40, highwayLikely = true))
        assertEquals(listOf("kakao-1", "kakao-3"), places.map { it.id })
        assertEquals(PlaceKind.REST_AREA, places[0].kind)
        assertEquals(PlaceKind.DROWSY_SHELTER, places[1].kind)
        assertEquals("경북 칠곡", places[0].address)
        val request = server.takeRequest()
        assertEquals("KakaoAK test-key", request.headers["Authorization"])
        assertEquals("/v2/local/search/keyword.json", request.url.encodedPath)
        assertEquals("휴게소", request.url.queryParameter("query"))
        // 카카오 API 상한으로 자른다
        assertEquals("20000", request.url.queryParameter("radius"))
    }

    @Test
    fun `일반 도로면 주차장과 편의점 분류로 찾는다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"documents":[{"id":"4","place_name":"공영주차장","x":"128.0","y":"36.4","category_group_code":"PK6"}]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"documents":[{"id":"5","place_name":"편의점","x":"128.0","y":"36.4","category_group_code":"CS2"}]}""").build())
        val places = kakao().nearby(PlaceQuery(TEST_FIX, 5, highwayLikely = false))
        assertEquals(listOf(PlaceKind.PARKING, PlaceKind.CONVENIENCE), places.map { it.kind })
        val request = server.takeRequest()
        assertEquals("/v2/local/search/category.json", request.url.encodedPath)
        assertEquals("PK6", request.url.queryParameter("category_group_code"))
        assertEquals("5000", request.url.queryParameter("radius"))
    }

    @Test
    fun `오류 응답과 깨진 본문은 빈 목록`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).build())
        server.enqueue(MockResponse.Builder().code(200).body("{깨짐").build())
        assertTrue(kakao().nearby(PlaceQuery(TEST_FIX, 5, true)).isEmpty())
    }

    @Test
    fun `2초를 넘기면 포기한다`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body(documents).headersDelay(3, TimeUnit.SECONDS).build())
        val startedAt = System.nanoTime()
        val places = kakao().nearby(PlaceQuery(TEST_FIX, 5, true))
        val tookMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue(places.isEmpty())
        assertTrue(tookMs < 2800, "걸린 시간 $tookMs")
    }

    /** 외부 이름은 그대로 음성이 된다(6.4). 받을 때 거른다 */
    @Test
    fun `금칙어가 든 외부 장소 이름은 버린다`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().code(200).body(
                """{"documents":[
                    {"id":"1","place_name":"현대오일뱅크 휴게소","x":"128.4","y":"36.0"},
                    {"id":"2","place_name":"당뇨 치료 쉼터","x":"128.4","y":"36.0"},
                    {"id":"3","place_name":"칠곡휴게소","x":"128.4","y":"36.0"}
                ]}""",
            ).build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body("""{"documents":[]}""").build())
        val places = kakao().nearby(PlaceQuery(TEST_FIX, 5, highwayLikely = true))
        assertEquals(listOf("kakao-3"), places.map { it.id })
    }
}
