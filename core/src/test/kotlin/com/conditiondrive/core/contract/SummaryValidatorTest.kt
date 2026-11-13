package com.conditiondrive.core.contract

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S2~S6. 12.7 표 2~14번의 코어 부분. HTTP 상태와 동의(403)는 서버 통합 테스트 몫이다 */
class SummaryValidatorTest {
    private val full = ContractJson.Strict.decodeFromString<SummaryDto>(ContractFixtures.text("summary_full.json"))
    private val glucoseOnly = ContractJson.Strict.decodeFromString<SummaryDto>(ContractFixtures.text("summary_glucose_only.json"))
    private val allNull = ContractJson.Strict.decodeFromString<SummaryDto>(ContractFixtures.text("summary_all_null.json"))
    private val now = ContractFixtures.ms("2026-11-20T08:12:05+09:00")

    private fun validate(dto: SummaryDto, nowMs: Long = now, allowPartner: Boolean = false) =
        SummaryValidator.validate(dto, nowMs, allowPartner)

    private fun assertRule(dto: SummaryDto, field: String, rule: String, code: String = ViolationCodes.VALIDATION_FAILED, nowMs: Long = now) {
        val violations = validate(dto, nowMs)
        assertTrue(Violation(field, rule, code) in violations, "$field/$rule 기대, 실제 $violations")
    }

    @Test
    fun goldenSummariesAreValid() {
        assertEquals(emptyList(), validate(full))
        assertEquals(emptyList(), validate(glucoseOnly))
        assertEquals(emptyList(), validate(allNull))
        for (name in listOf("summary_full.json", "summary_glucose_only.json", "summary_all_null.json")) {
            assertEquals(emptyList(), SummaryValidator.validateRawKeys(ContractFixtures.obj(name)), name)
        }
    }

    // 표 2번
    @Test
    fun row2_forbiddenKeysAreRejectedCaseInsensitively() {
        for (key in SummaryLimits.FORBIDDEN_KEYS) {
            for (variant in listOf(key, key.uppercase(), key.replaceFirstChar { it.uppercase() })) {
                val obj = JsonObject(ContractFixtures.obj("summary_full.json") + (variant to JsonPrimitive(1)))
                val first = SummaryValidator.validateRawKeys(obj).first()
                assertEquals(ViolationCodes.RAW_HEALTH_DATA_REJECTED, first.code, variant)
                assertEquals(key, first.field, variant)
            }
        }
    }

    @Test
    fun row2_listHasExactly15Keys() {
        assertEquals(15, SummaryLimits.FORBIDDEN_KEYS.size)
        assertEquals(9, SummaryLimits.ALLOWED_KEYS.size)
        val doc = com.conditiondrive.core.voice.RepoFiles.text("docs/DESIGN.md")
        val row = doc.lines().first { it.startsWith("| 2 | 키 | 거부 목록") }
        for (key in SummaryLimits.FORBIDDEN_KEYS) assertTrue("`$key`" in row, key)
    }

    // 표 3번
    @Test
    fun row3_arraysAndObjectsAreRejected() {
        val array = JsonObject(ContractFixtures.obj("summary_full.json") + ("glucose_mmol" to JsonArray(listOf(JsonPrimitive(5.4)))))
        assertEquals(Violation("glucose_mmol", "scalar_only", ViolationCodes.RAW_HEALTH_DATA_REJECTED), SummaryValidator.validateRawKeys(array).first())
        val nested = JsonObject(ContractFixtures.obj("summary_full.json") + ("sleep_short" to buildJsonObject { put("a", JsonPrimitive(1)) }))
        assertEquals(ViolationCodes.RAW_HEALTH_DATA_REJECTED, SummaryValidator.validateRawKeys(nested).first().code)
    }

    // 표 4번
    @Test
    fun row4_unknownKeysAreRejected() {
        val obj = JsonObject(ContractFixtures.obj("summary_full.json") + ("mood" to JsonPrimitive("good")))
        assertEquals(listOf(Violation("mood", "unknown_field", ViolationCodes.UNKNOWN_FIELD)), SummaryValidator.validateRawKeys(obj))
    }

    // 표 5번
    @Test
    fun row5_everyMissingKeyIsReported() {
        for (key in SummaryLimits.ALLOWED_KEYS) {
            val obj = JsonObject(ContractFixtures.obj("summary_full.json") - key)
            assertEquals(listOf(Violation(key, "required", ViolationCodes.VALIDATION_FAILED)), SummaryValidator.validateRawKeys(obj), key)
        }
    }

    @Test
    fun rawViolationsFollowTableOrder() {
        val base = ContractFixtures.obj("summary_full.json") - "glucose_mmol"
        val obj = JsonObject(base + mapOf("mood" to JsonPrimitive(1), "sleep_short" to JsonArray(emptyList()), "HR" to JsonPrimitive(60)))
        assertEquals(
            listOf("forbidden_key", "scalar_only", "unknown_field", "required"),
            SummaryValidator.validateRawKeys(obj).map { it.rule },
        )
    }

    /** 응답에 값이 되돌아가면 안 된다. 키 이름도 입력이므로 식별자 꼴이 아니면 내보내지 않는다 */
    @Test
    fun s3_violationsNeverEchoValuesOrSuspiciousKeys() {
        val obj = JsonObject(
            ContractFixtures.obj("summary_full.json") + mapOf(
                "+821099990000" to JsonPrimitive("x"),
                "821099990000" to JsonPrimitive("x"),
                "note" to JsonPrimitive("3.1415"),
                "glucose_mmol" to JsonPrimitive("3.1415"),
                "sleep_minutes_last_night" to JsonPrimitive(271.5),
            ),
        )
        val text = SummaryValidator.validateRawKeys(obj).toString()
        for (canary in listOf("3.1415", "271", "99990000")) assertFalse(canary in text, text)
        assertTrue("note" in text, text)
    }

    /** 통과하면 Strict 디코딩이 예외로 끝나지 않는다. 나머지 오류는 표의 code로 나가야 하기 때문이다 */
    @Test
    fun rawCheckCoversEveryDecodeFailure() {
        val base = ContractFixtures.obj("summary_full.json")
        val badValues: List<Pair<String, JsonElement>> = listOf(
            "generated_at" to JsonNull,
            "generated_at" to JsonPrimitive(1),
            "valid_until" to JsonNull,
            "sleep_valid_until" to JsonPrimitive(1),
            "glucose_measured_at" to JsonPrimitive(true),
            "sleep_minutes_last_night" to JsonPrimitive("290"),
            "sleep_minutes_last_night" to JsonPrimitive(290.5),
            "sleep_short" to JsonPrimitive("true"),
            "sleep_short" to JsonPrimitive(1),
            "glucose_mmol" to JsonPrimitive("5.4"),
            "glucose_mmol" to JsonPrimitive(true),
            "glucose_trend" to JsonPrimitive("down"),
            "glucose_trend" to JsonPrimitive(1),
            "glucose_source" to JsonPrimitive("health_connect"),
            "glucose_source" to JsonPrimitive(1),
        )
        for ((key, value) in badValues) {
            val obj = JsonObject(base + (key to value))
            assertTrue(SummaryValidator.validateRawKeys(obj).any { it.field == key }, "$key=$value")
        }
    }

    // 표 6번, S6
    @Test
    fun row6_clockSkewBoundaryIs300Seconds() {
        val generated = ContractFixtures.ms(full.generatedAt)
        for ((offset, ok) in listOf(-300_000L to true, 300_000L to true, -301_000L to false, 301_000L to false)) {
            // valid_until 검사와 섞이지 않도록 미래 쪽은 그 범위 안에서만 본다
            val nowMs = generated + offset
            val skew = validate(full, nowMs).any { it.code == ViolationCodes.CLOCK_SKEW }
            assertEquals(!ok, skew, "offset=$offset")
        }
        assertEquals(ViolationCodes.CLOCK_SKEW, validate(full, generated + 301_000).first().code)
    }

    // 표 7번, S6
    @Test
    fun row7_validUntilOrderAndExpiry() {
        assertRule(full.copy(validUntil = full.generatedAt), "valid_until", "time_order")
        assertRule(full.copy(validUntil = "2026-11-20T08:11:59+09:00"), "valid_until", "time_order")
        assertEquals(emptyList(), validate(full.copy(validUntil = "2026-11-20T08:12:01+09:00"), ContractFixtures.ms(full.generatedAt)))
        // generated_at+900 정확히는 허용, +1초는 거절. 측정 시각은 경계와 섞이지 않게 맞춘다
        val atLimit = full.copy(validUntil = "2026-11-20T08:27:00+09:00", glucoseMeasuredAt = "2026-11-20T08:12:00+09:00")
        assertEquals(emptyList(), validate(atLimit))
        assertRule(atLimit.copy(validUntil = "2026-11-20T08:27:01+09:00"), "valid_until", "time_order")
    }

    @Test
    fun row7_expiryIsSummaryExpired() {
        val validUntil = ContractFixtures.ms(full.validUntil)
        val shortLived = full.copy(generatedAt = "2026-11-20T08:24:00+09:00", glucoseMeasuredAt = "2026-11-20T08:23:30+09:00")
        assertEquals(emptyList(), validate(shortLived, validUntil - 1_000))
        val expired = Violation("valid_until", "expired", ViolationCodes.SUMMARY_EXPIRED)
        assertTrue(expired in validate(shortLived, validUntil))
        assertTrue(expired in validate(shortLived, validUntil + 1_000))
    }

    @Test
    fun timestampsMustBeRfc3339WithOffsetAndSeconds() {
        for (bad in listOf("2026-11-20 08:12:00+09:00", "2026-11-20T08:12:00", "2026-11-20T08:12+09:00", "2026-13-20T08:12:00+09:00", "어제", "")) {
            assertRule(full.copy(generatedAt = bad), "generated_at", "format")
            assertRule(full.copy(validUntil = bad), "valid_until", "format")
            assertRule(full.copy(sleepValidUntil = bad), "sleep_valid_until", "format")
            assertRule(full.copy(glucoseMeasuredAt = bad), "glucose_measured_at", "format")
        }
        val utc = full.copy(generatedAt = "2026-11-19T23:12:00Z", validUntil = "2026-11-19T23:26:30.000Z")
        assertEquals(emptyList(), validate(utc))
    }

    // 표 8번
    @Test
    fun row8_sleepMinutesRange() {
        fun with(minutes: Int) = full.copy(sleepMinutesLastNight = minutes, sleepShort = SleepPolicy.isShort(minutes))
        assertEquals(emptyList(), validate(with(0)))
        assertEquals(emptyList(), validate(with(1440)))
        assertRule(with(-1), "sleep_minutes_last_night", "range")
        assertRule(with(1441), "sleep_minutes_last_night", "range")
    }

    // 표 9번, S5
    @Test
    fun row9_sleepFieldsMoveTogether() {
        assertRule(full.copy(sleepMinutesLastNight = null), "sleep_short", "combination")
        assertRule(full.copy(sleepShort = null), "sleep_short", "combination")
        assertRule(full.copy(sleepValidUntil = null), "sleep_short", "combination")
        assertRule(full.copy(sleepShort = false), "sleep_short", "combination")
        assertRule(full.copy(sleepMinutesLastNight = 360, sleepShort = true), "sleep_short", "combination")
        assertEquals(emptyList(), validate(full.copy(sleepMinutesLastNight = 360, sleepShort = false)))
        assertEquals(emptyList(), validate(full.copy(sleepMinutesLastNight = 359, sleepShort = true)))
    }

    @Test
    fun row9_sleepValidUntilOrder() {
        assertRule(full.copy(sleepValidUntil = full.generatedAt), "sleep_valid_until", "time_order")
        assertEquals(emptyList(), validate(full.copy(sleepValidUntil = "2026-11-21T14:12:00+09:00")))
        assertRule(full.copy(sleepValidUntil = "2026-11-21T14:12:01+09:00"), "sleep_valid_until", "time_order")
    }

    // 표 10번
    @Test
    fun row10_glucoseRangeAndPrecision() {
        assertEquals(emptyList(), validate(full.copy(glucoseMmol = 1.0)))
        assertEquals(emptyList(), validate(full.copy(glucoseMmol = 33.3)))
        assertEquals(emptyList(), validate(full.copy(glucoseMmol = 3.99)))
        for (bad in listOf(0.99, 33.31, 3.999, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertRule(full.copy(glucoseMmol = bad), "glucose_mmol", "range")
        }
    }

    // 표 11번
    @Test
    fun row11_trendEnumAndNullTrendAllowed() {
        assertEquals(emptyList(), validate(full.copy(glucoseTrend = null)))
        val obj = JsonObject(ContractFixtures.obj("summary_full.json") + ("glucose_trend" to JsonPrimitive("down")))
        assertEquals(listOf(Violation("glucose_trend", "enum", ViolationCodes.VALIDATION_FAILED)), SummaryValidator.validateRawKeys(obj))
    }

    // 표 12번, S4
    @Test
    fun row12_sourcePolicy() {
        val partner = full.copy(glucoseSource = GlucoseSourceDto.PARTNER_REALTIME)
        val notAllowed = Violation("glucose_source", "not_allowed", ViolationCodes.GLUCOSE_SOURCE_NOT_ALLOWED)
        assertEquals(listOf(notAllowed), validate(partner))
        assertEquals(emptyList(), validate(partner, allowPartner = true))

        val healthConnect = JsonObject(ContractFixtures.obj("summary_full.json") + ("glucose_source" to JsonPrimitive("health_connect")))
        assertEquals(listOf(notAllowed), SummaryValidator.validateRawKeys(healthConnect))
    }

    // 표 13번
    @Test
    fun row13_glucoseCombination() {
        assertRule(full.copy(glucoseSource = null), "glucose_source", "combination")
        assertRule(full.copy(glucoseMeasuredAt = null), "glucose_measured_at", "combination")
        val noValue = full.copy(glucoseMmol = null)
        assertRule(noValue, "glucose_trend", "combination")
        assertRule(noValue, "glucose_source", "combination")
        assertRule(noValue, "glucose_measured_at", "combination")
    }

    // 표 14번
    @Test
    fun row14_measuredAtOrder() {
        val ahead60 = full.copy(generatedAt = "2026-11-20T08:12:00+09:00", glucoseMeasuredAt = "2026-11-20T08:13:00+09:00")
        assertEquals(emptyList(), validate(ahead60))
        assertRule(full.copy(glucoseMeasuredAt = "2026-11-20T08:13:01+09:00"), "glucose_measured_at", "time_order")
        // valid_until = measured+900 정확히는 허용, measured가 1초 이르면 거절
        assertRule(full.copy(glucoseMeasuredAt = "2026-11-20T08:11:29+09:00"), "glucose_measured_at", "time_order")
    }

    @Test
    fun violationOrderPutsMostSpecificCodeFirst() {
        val dto = full.copy(generatedAt = "2026-11-20T07:00:00+09:00", glucoseMmol = 99.0)
        assertEquals(ViolationCodes.CLOCK_SKEW, validate(dto).first().code)
    }

    /** 12.8 마스킹 응답은 generated_at이 오래됐고 valid_until이 지났어도 정상이다 */
    @Test
    fun receivedMaskedSummaryIsValidForVehicle() {
        val masked = ContractJson.Lenient.decodeFromString<LatestSummaryResponse>(ContractFixtures.text("latest_masked.json"))
        val serverTime = ContractFixtures.ms(masked.serverTime)
        val summary = masked.summary!!
        assertEquals(emptyList(), SummaryValidator.validateReceived(summary, serverTime, allowPartner = false))
        // 같은 값을 전송 검사에 넣으면 거절돼야 한다
        assertTrue(SummaryValidator.validate(summary, serverTime, allowPartner = false).isNotEmpty())
    }

    @Test
    fun receivedStillChecksStructure() {
        val anyTime = ContractFixtures.ms("2026-11-20T09:00:00+09:00")
        assertEquals(emptyList(), SummaryValidator.validateReceived(full, anyTime, allowPartner = false))
        assertTrue(SummaryValidator.validateReceived(full.copy(glucoseMmol = 99.0), anyTime, allowPartner = false).any { it.rule == "range" })
        assertTrue(SummaryValidator.validateReceived(full.copy(validUntil = "2026-11-20T08:00:00+09:00"), anyTime, allowPartner = false).any { it.rule == "time_order" })
        assertTrue(
            SummaryValidator.validateReceived(full.copy(glucoseSource = GlucoseSourceDto.PARTNER_REALTIME), anyTime, allowPartner = false)
                .any { it.code == ViolationCodes.GLUCOSE_SOURCE_NOT_ALLOWED },
        )
    }

    @Test
    fun limitsMatchDesign() {
        assertEquals(900L, SummaryLimits.GLUCOSE_TTL_SEC)
        assertEquals(300L, SummaryLimits.CLOCK_SKEW_SEC)
        assertEquals(108_000L, SummaryLimits.SLEEP_VALID_MAX_SEC)
        assertEquals(2048, SummaryLimits.BODY_MAX_BYTES)
        assertEquals(1.0, SummaryLimits.GLUCOSE_MIN)
        assertEquals(33.3, SummaryLimits.GLUCOSE_MAX)
        assertEquals(1440, SummaryLimits.SLEEP_MAX_MIN)
    }
}
