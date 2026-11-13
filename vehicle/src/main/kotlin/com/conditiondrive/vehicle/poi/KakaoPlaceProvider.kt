package com.conditiondrive.vehicle.poi

import com.conditiondrive.core.poi.Place
import com.conditiondrive.core.voice.ForbiddenWords
import com.conditiondrive.core.voice.Formats
import com.conditiondrive.core.poi.PlaceKind
import com.conditiondrive.vehicle.port.PlaceProvider
import com.conditiondrive.vehicle.port.PlaceQuery
import com.conditiondrive.vehicle.summary.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 2차 보조 공급자(F3). 위치를 외부로 보내므로 S5 동의가 켜진 때만 호출한다(14장, 기본 꺼짐).
 * 실패는 빈 목록이다. 호출부가 내장 결과로 이어 간다.
 */
class KakaoPlaceProvider(
    client: OkHttpClient,
    private val apiKey: String?,                // BuildConfig. 키는 local.properties에서만 읽는다(10.3)
    private val consentGiven: () -> Boolean,
    private val baseUrl: HttpUrl = "https://dapi.kakao.com".toHttpUrl(),
) : PlaceProvider {
    override val id: String = "kakao"

    private val http = client.newBuilder().callTimeout(LIMIT_MS, TimeUnit.MILLISECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun nearby(query: PlaceQuery): List<Place> {
        val key = apiKey?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (!consentGiven()) return emptyList()
        return withTimeoutOrNull(LIMIT_MS) {
            searchesFor(query).flatMap { search(key, query, it) }.distinctBy { it.id }
        } ?: emptyList()
    }

    private sealed interface Search {
        data class Keyword(val text: String) : Search
        data class Category(val code: String) : Search
    }

    /** 고속도로에서는 주차장보다 쉼터가 진입하기 쉽다. 일반 도로에서는 쉼터 자체가 없다 */
    private fun searchesFor(query: PlaceQuery): List<Search> =
        if (query.highwayLikely) {
            listOf(Search.Keyword(KEYWORD_REST_AREA), Search.Keyword(KEYWORD_SHELTER))
        } else {
            listOf(Search.Category(CATEGORY_PARKING), Search.Category(CATEGORY_CONVENIENCE))
        }

    private suspend fun search(key: String, query: PlaceQuery, search: Search): List<Place> {
        val url = baseUrl.newBuilder()
            .addPathSegments(if (search is Search.Keyword) "v2/local/search/keyword.json" else "v2/local/search/category.json")
            .apply {
                when (search) {
                    is Search.Keyword -> addQueryParameter("query", search.text)
                    is Search.Category -> addQueryParameter("category_group_code", search.code)
                }
            }
            .addQueryParameter("x", query.fix.longitude.toString())
            .addQueryParameter("y", query.fix.latitude.toString())
            .addQueryParameter("radius", (query.radiusKm * 1000).coerceAtMost(MAX_RADIUS_M).toString())
            .addQueryParameter("sort", "distance")
            .build()
        val request = Request.Builder().url(url).header("Authorization", "KakaoAK $key").build()
        return try {
            http.newCall(request).await().use { response ->
                if (!response.isSuccessful) return emptyList()
                json.decodeFromString(KakaoResponse.serializer(), response.body.string()).documents
                    .mapNotNull { it.toPlace() }
                    // 장소 이름은 그대로 음성이 된다. 내장 POI는 시험으로 걸렀지만 외부 이름은 받을 때 걸러야 한다(6.4)
                    .filter { ForbiddenWords.find(Formats.placeName(it.name)).isEmpty() }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: IOException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()     // 응답 형식이 바뀐 경우. 내장 결과가 있으니 멈출 이유가 없다
        }
    }

    @Serializable
    private data class KakaoResponse(val documents: List<KakaoDocument> = emptyList())

    @Serializable
    private data class KakaoDocument(
        val id: String,
        @SerialName("place_name") val placeName: String,
        val x: String,
        val y: String,
        @SerialName("category_group_code") val categoryGroupCode: String = "",
        @SerialName("road_address_name") val roadAddress: String = "",
        @SerialName("address_name") val address: String = "",
    ) {
        fun toPlace(): Place? {
            val longitude = x.toDoubleOrNull() ?: return null
            val latitude = y.toDoubleOrNull() ?: return null
            return Place(
                id = "kakao-$id",
                name = placeName,
                latitude = latitude,
                longitude = longitude,
                kind = when {
                    categoryGroupCode == CATEGORY_PARKING -> PlaceKind.PARKING
                    categoryGroupCode == CATEGORY_CONVENIENCE -> PlaceKind.CONVENIENCE
                    KEYWORD_SHELTER in placeName -> PlaceKind.DROWSY_SHELTER
                    else -> PlaceKind.REST_AREA
                },
                address = roadAddress.ifEmpty { address },
            )
        }
    }

    private companion object {
        const val LIMIT_MS = 2000L              // 설계서 F3 공급자 순서
        const val MAX_RADIUS_M = 20_000         // 카카오 로컬 API 상한
        const val KEYWORD_REST_AREA = "휴게소"
        const val KEYWORD_SHELTER = "졸음쉼터"
        const val CATEGORY_PARKING = "PK6"
        const val CATEGORY_CONVENIENCE = "CS2"
    }
}
