package com.conditiondrive.core.contract

import com.conditiondrive.core.engine.Policy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

data class Violation(val field: String, val rule: String, val code: String)

object SummaryLimits {
    const val GLUCOSE_TTL_SEC = 900L
    const val GLUCOSE_MIN = 1.0
    const val GLUCOSE_MAX = 33.3
    const val SLEEP_MAX_MIN = 1440
    const val CLOCK_SKEW_SEC = 300L
    const val SLEEP_VALID_MAX_SEC = 108_000L          // 30시간
    const val BODY_MAX_BYTES = 2048
    const val MEASURED_AHEAD_MAX_SEC = 60L            // 12.7 표 14번
    val FORBIDDEN_KEYS: Set<String> = setOf(          // 12.7 표 2번
        "samples", "readings", "records", "series", "egv", "heart_rate", "hr", "bpm",
        "sleep_sessions", "stages", "glucose_history", "glucose_series", "location", "lat", "lng",
    )
    val ALLOWED_KEYS: Set<String> = setOf(            // 9개
        "generated_at", "valid_until", "sleep_minutes_last_night", "sleep_short", "sleep_valid_until",
        "glucose_mmol", "glucose_trend", "glucose_source", "glucose_measured_at",
    )
}

object ViolationCodes {
    const val VALIDATION_FAILED = "validation_failed"
    const val UNKNOWN_FIELD = "unknown_field"
    const val RAW_HEALTH_DATA_REJECTED = "raw_health_data_rejected"
    const val GLUCOSE_SOURCE_NOT_ALLOWED = "glucose_source_not_allowed"
    const val CLOCK_SKEW = "clock_skew"
    const val SUMMARY_EXPIRED = "summary_expired"
}

object SleepPolicy {
    fun isShort(minutes: Int): Boolean = minutes < Policy.DEFAULT.SLEEP_SHORT_MINUTES

    /** C-36. generated_at 이후 처음 맞는 경계, 그때까지 최소 시간 미만이면 다음 날 경계 */
    fun validUntil(generatedAt: OffsetDateTime, zone: ZoneId): OffsetDateTime {
        val rule = Policy.DEFAULT.SLEEP_VALID_RULE
        val start = generatedAt.atZoneSameInstant(zone)
        var boundary = start.toLocalDate().atTime(rule.boundary).atZone(zone)
        if (!boundary.isAfter(start)) boundary = boundary.plusDays(1)
        if (Duration.between(start, boundary) < Duration.ofHours(rule.minHoursUntilBoundary.toLong())) {
            boundary = boundary.plusDays(1)
        }
        return boundary.toOffsetDateTime()
    }
}

/** RFC 3339, 오프셋과 초 필수(12.1). java 파서만 쓰면 초 없는 값과 소문자가 통과한다 */
internal object Rfc3339 {
    private val FORMAT = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})""")

    fun parseMs(text: String): Long? {
        if (!FORMAT.matches(text)) return null
        return try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }
    }
}

/**
 * 12.7 검증표. 결과는 표 순서라 첫 항목의 code가 응답 code가 된다. Violation에는 필드 이름과 규칙만 담고 값은 담지 않는다.
 * 서버: validateRawKeys → DTO 디코딩 → validate. 폰: 전송 전 validate. 차량: 수신 후 validateReceived.
 */
object SummaryValidator {
    private const val KEY_GENERATED_AT = "generated_at"
    private const val KEY_VALID_UNTIL = "valid_until"
    private const val KEY_SLEEP_MINUTES = "sleep_minutes_last_night"
    private const val KEY_SLEEP_SHORT = "sleep_short"
    private const val KEY_SLEEP_VALID = "sleep_valid_until"
    private const val KEY_GLUCOSE = "glucose_mmol"
    private const val KEY_TREND = "glucose_trend"
    private const val KEY_SOURCE = "glucose_source"
    private const val KEY_MEASURED = "glucose_measured_at"

    private const val RULE_FORMAT = "format"
    private const val RULE_TIME_ORDER = "time_order"
    private const val RULE_RANGE = "range"
    private const val RULE_COMBINATION = "combination"
    private const val RULE_ENUM = "enum"

    private val KNOWN_TRENDS = setOf("falling_fast", "falling", "flat", "rising")
    private val KNOWN_SOURCES = setOf("simulated", "partner_realtime")
    // 키 이름도 입력이다. 전화번호처럼 생긴 키가 응답으로 되돌아가지 않게 식별자 꼴만 그대로 쓴다
    private val SAFE_KEY = Regex("""[a-z][a-z0-9_]{0,39}""")
    private const val UNSAFE_KEY_LABEL = "_invalid_key"

    /** 폰 전송 전, 서버 수신 시 실행한다. 12.7 표 6~14번 */
    fun validate(dto: SummaryDto, nowMs: Long, allowPartner: Boolean): List<Violation> =
        validateCommon(dto, nowMs, allowPartner, checkFreshness = true)

    /**
     * 차량 수신 시 실행한다. 표 6번과 7번의 "현재 이후"만 뺀다.
     * 12.8 응답은 유효 항목만 남긴 마스킹 값이라 generated_at이 몇 시간 전이고 valid_until이 지났어도 정상이다.
     * 만료는 Mappers가 server_time 기준으로 항목별로 처리한다.
     */
    fun validateReceived(dto: SummaryDto, serverTimeMs: Long, allowPartner: Boolean): List<Violation> =
        validateCommon(dto, serverTimeMs, allowPartner, checkFreshness = false)

    /**
     * 12.7 표 2~5번과, 디코딩이 예외로 끝나지 않도록 하는 값 형태 검사. 비어 있으면 Strict 디코딩이 실패하지 않는다.
     * 표 11·12번을 여기서도 보는 이유: enum이 DTO로 바뀌면 "health_connect"와 오타를 구분할 수 없다.
     */
    fun validateRawKeys(obj: JsonObject): List<Violation> = buildList {
        for (key in obj.keys) {
            val lower = key.lowercase()
            if (lower in SummaryLimits.FORBIDDEN_KEYS) {
                add(Violation(lower, "forbidden_key", ViolationCodes.RAW_HEALTH_DATA_REJECTED))
            }
        }
        for ((key, value) in obj) {
            if (key.lowercase() !in SummaryLimits.FORBIDDEN_KEYS && value !is JsonPrimitive) {
                add(Violation(safeKey(key), "scalar_only", ViolationCodes.RAW_HEALTH_DATA_REJECTED))
            }
        }
        for (key in obj.keys) {
            if (key !in SummaryLimits.ALLOWED_KEYS && key.lowercase() !in SummaryLimits.FORBIDDEN_KEYS) {
                add(Violation(safeKey(key), "unknown_field", ViolationCodes.UNKNOWN_FIELD))
            }
        }
        for (key in SummaryLimits.ALLOWED_KEYS) {
            if (key !in obj) add(Violation(key, "required", ViolationCodes.VALIDATION_FAILED))
        }
        addAll(valueShapeViolations(obj))
    }

    private fun safeKey(key: String): String = if (SAFE_KEY.matches(key)) key else UNSAFE_KEY_LABEL

    private fun valueShapeViolations(obj: JsonObject): List<Violation> = buildList {
        fun primitive(key: String) = obj[key] as? JsonPrimitive
        fun isNull(value: JsonPrimitive) = value is JsonNull

        for (key in listOf(KEY_GENERATED_AT, KEY_VALID_UNTIL)) {
            val value = primitive(key) ?: continue
            if (isNull(value) || !value.isString) add(failed(key, RULE_FORMAT))
        }
        for (key in listOf(KEY_SLEEP_VALID, KEY_MEASURED)) {
            val value = primitive(key) ?: continue
            if (!isNull(value) && !value.isString) add(failed(key, RULE_FORMAT))
        }
        primitive(KEY_SLEEP_MINUTES)?.let {
            if (!isNull(it) && (it.isString || it.intOrNull == null)) add(failed(KEY_SLEEP_MINUTES, RULE_RANGE))
        }
        primitive(KEY_SLEEP_SHORT)?.let {
            if (!isNull(it) && (it.isString || it.booleanOrNull == null)) add(failed(KEY_SLEEP_SHORT, RULE_COMBINATION))
        }
        primitive(KEY_GLUCOSE)?.let {
            if (!isNull(it) && (it.isString || it.doubleOrNull == null)) add(failed(KEY_GLUCOSE, RULE_RANGE))
        }
        primitive(KEY_TREND)?.let {
            if (!isNull(it) && (!it.isString || it.content !in KNOWN_TRENDS)) add(failed(KEY_TREND, RULE_ENUM))
        }
        primitive(KEY_SOURCE)?.let {
            if (!isNull(it) && (!it.isString || it.content !in KNOWN_SOURCES)) add(sourceNotAllowed())
        }
    }

    private fun validateCommon(dto: SummaryDto, nowMs: Long, allowPartner: Boolean, checkFreshness: Boolean): List<Violation> = buildList {
        val generated = Rfc3339.parseMs(dto.generatedAt)
        val validUntil = Rfc3339.parseMs(dto.validUntil)
        val sleepValid = dto.sleepValidUntil?.let(Rfc3339::parseMs)
        val measured = dto.glucoseMeasuredAt?.let(Rfc3339::parseMs)

        // 6
        if (generated == null) {
            add(failed(KEY_GENERATED_AT, RULE_FORMAT))
        } else if (checkFreshness && kotlin.math.abs(generated - nowMs) > SummaryLimits.CLOCK_SKEW_SEC * 1000) {
            add(Violation(KEY_GENERATED_AT, "clock_skew", ViolationCodes.CLOCK_SKEW))
        }

        // 7
        if (validUntil == null) {
            add(failed(KEY_VALID_UNTIL, RULE_FORMAT))
        } else {
            if (generated != null && (validUntil <= generated || validUntil > generated + SummaryLimits.GLUCOSE_TTL_SEC * 1000)) {
                add(failed(KEY_VALID_UNTIL, RULE_TIME_ORDER))
            }
            if (checkFreshness && validUntil <= nowMs) {
                add(Violation(KEY_VALID_UNTIL, "expired", ViolationCodes.SUMMARY_EXPIRED))
            }
        }

        // 8
        dto.sleepMinutesLastNight?.let {
            if (it !in 0..SummaryLimits.SLEEP_MAX_MIN) add(failed(KEY_SLEEP_MINUTES, RULE_RANGE))
        }

        // 9
        val sleepFields = listOf(dto.sleepMinutesLastNight, dto.sleepShort, dto.sleepValidUntil)
        if (sleepFields.any { it != null } && sleepFields.any { it == null }) {
            add(failed(KEY_SLEEP_SHORT, RULE_COMBINATION))
        }
        if (dto.sleepMinutesLastNight != null && dto.sleepShort != null && dto.sleepShort != SleepPolicy.isShort(dto.sleepMinutesLastNight)) {
            add(failed(KEY_SLEEP_SHORT, RULE_COMBINATION))
        }
        if (dto.sleepValidUntil != null) {
            when {
                sleepValid == null -> add(failed(KEY_SLEEP_VALID, RULE_FORMAT))
                generated != null && (sleepValid <= generated || sleepValid > generated + SummaryLimits.SLEEP_VALID_MAX_SEC * 1000) ->
                    add(failed(KEY_SLEEP_VALID, RULE_TIME_ORDER))
            }
        }

        // 10
        dto.glucoseMmol?.let {
            if (!isValidGlucose(it)) add(failed(KEY_GLUCOSE, RULE_RANGE))
        }

        // 11은 DTO가 enum이라 validateRawKeys가 맡는다

        // 12
        if (dto.glucoseSource == GlucoseSourceDto.PARTNER_REALTIME && !allowPartner) add(sourceNotAllowed())

        // 13
        if (dto.glucoseMmol != null) {
            if (dto.glucoseSource == null) add(failed(KEY_SOURCE, RULE_COMBINATION))
            if (dto.glucoseMeasuredAt == null) add(failed(KEY_MEASURED, RULE_COMBINATION))
        } else {
            if (dto.glucoseTrend != null) add(failed(KEY_TREND, RULE_COMBINATION))
            if (dto.glucoseSource != null) add(failed(KEY_SOURCE, RULE_COMBINATION))
            if (dto.glucoseMeasuredAt != null) add(failed(KEY_MEASURED, RULE_COMBINATION))
        }

        // 14
        if (dto.glucoseMeasuredAt != null) {
            if (measured == null) {
                add(failed(KEY_MEASURED, RULE_FORMAT))
            } else {
                if (generated != null && measured > generated + SummaryLimits.MEASURED_AHEAD_MAX_SEC * 1000) {
                    add(failed(KEY_MEASURED, RULE_TIME_ORDER))
                }
                if (validUntil != null && validUntil > measured + SummaryLimits.GLUCOSE_TTL_SEC * 1000) {
                    add(failed(KEY_MEASURED, RULE_TIME_ORDER))
                }
            }
        }
    }

    private fun isValidGlucose(value: Double): Boolean {
        if (value.isNaN() || value.isInfinite()) return false
        if (value < SummaryLimits.GLUCOSE_MIN || value > SummaryLimits.GLUCOSE_MAX) return false
        return BigDecimal.valueOf(value).stripTrailingZeros().scale() <= 2
    }

    private fun failed(field: String, rule: String) = Violation(field, rule, ViolationCodes.VALIDATION_FAILED)

    private fun sourceNotAllowed() = Violation(KEY_SOURCE, "not_allowed", ViolationCodes.GLUCOSE_SOURCE_NOT_ALLOWED)
}
