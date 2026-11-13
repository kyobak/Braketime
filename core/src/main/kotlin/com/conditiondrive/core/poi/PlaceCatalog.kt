package com.conditiondrive.core.poi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 내장 POI 파일(tools/poi_build.py가 만드는 poi.json) 형식. 파서를 여기 둬 생성 결과를 JVM 테스트로 검증한다. */
@Serializable
data class PlaceCatalogFile(
    val schemaVersion: Int,
    val notice: String,
    val sources: List<PlaceCatalogSource>,
    val places: List<Place>,
)

@Serializable
data class PlaceCatalogSource(val name: String, val url: String, val dataDate: String, val count: Int)

object PlaceCatalog {
    const val SCHEMA_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }

    fun parseFile(text: String): PlaceCatalogFile = json.decodeFromString(PlaceCatalogFile.serializer(), text)

    /** 버전이 다르면 빈 목록이다. 장소 없음은 N 변형으로 이어지고 안내 자체는 멈추지 않는다 */
    fun parse(text: String): List<Place> {
        val file = runCatching { parseFile(text) }.getOrNull() ?: return emptyList()
        return if (file.schemaVersion == SCHEMA_VERSION) file.places else emptyList()
    }
}
