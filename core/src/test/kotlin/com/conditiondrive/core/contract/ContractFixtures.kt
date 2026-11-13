package com.conditiondrive.core.contract

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.OffsetDateTime

object ContractFixtures {
    fun text(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/contract/$name")) { "픽스처 없음: $name" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun json(name: String): JsonElement = ContractJson.Strict.parseToJsonElement(text(name))

    fun obj(name: String): JsonObject = json(name) as JsonObject

    fun ms(rfc3339: String): Long = OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli()
}
