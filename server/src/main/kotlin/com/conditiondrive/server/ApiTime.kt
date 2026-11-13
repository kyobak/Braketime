package com.conditiondrive.server

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

object ApiTime {
    // java 파서만 쓰면 초 없는 값과 소문자가 통과한다(12.1 RFC 3339, 오프셋 필수)
    private val FORMAT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")

    fun format(ms: Long): String = Instant.ofEpochMilli(ms).toString()

    fun parseMs(text: String): Long? {
        if (!FORMAT.matches(text)) return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
