package com.conditiondrive.core.contract

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable enum class GlucoseTrendDto {
    @SerialName("falling_fast") FALLING_FAST, @SerialName("falling") FALLING,
    @SerialName("flat") FLAT, @SerialName("rising") RISING,
}
@Serializable enum class GlucoseSourceDto {
    @SerialName("partner_realtime") PARTNER_REALTIME, @SerialName("simulated") SIMULATED,
}

@Serializable data class SummaryDto(
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("valid_until") val validUntil: String,
    @SerialName("sleep_minutes_last_night") val sleepMinutesLastNight: Int?,
    @SerialName("sleep_short") val sleepShort: Boolean?,
    @SerialName("sleep_valid_until") val sleepValidUntil: String?,
    @SerialName("glucose_mmol") val glucoseMmol: Double?,
    @SerialName("glucose_trend") val glucoseTrend: GlucoseTrendDto?,
    @SerialName("glucose_source") val glucoseSource: GlucoseSourceDto?,
    @SerialName("glucose_measured_at") val glucoseMeasuredAt: String?,
)

/**
 * 11.1 "알 수 없는 enum은 혈당 전체를 무효로 처리한다". 예외로 끝나면 유효한 수면까지 잃으므로,
 * 혈당 4필드만 null로 지운 뒤 디코딩한다. 저혈당 값이 추세 없이 flat으로 읽히는 일도 막는다.
 */
object LenientSummarySerializer : JsonTransformingSerializer<SummaryDto>(SummaryDto.serializer()) {
    private val GLUCOSE_FIELDS = listOf("glucose_mmol", "glucose_trend", "glucose_source", "glucose_measured_at")
    private val TRENDS = setOf("falling_fast", "falling", "flat", "rising")
    private val SOURCES = setOf("simulated", "partner_realtime")

    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonObject) return element
        val known = isKnown(element["glucose_trend"], TRENDS) && isKnown(element["glucose_source"], SOURCES)
        if (known) return element
        return JsonObject(element + GLUCOSE_FIELDS.associateWith { JsonNull })
    }

    private fun isKnown(value: JsonElement?, allowed: Set<String>): Boolean = when (value) {
        null, is JsonNull -> true
        is JsonPrimitive -> value.isString && value.content in allowed
        else -> false
    }
}

@Serializable data class SummaryUploadResponse(
    val applied: Boolean,
    val reason: String? = null,
    @SerialName("received_at") val receivedAt: String? = null,
    @SerialName("vehicle_last_seen_at") val vehicleLastSeenAt: String? = null,
    @SerialName("server_time") val serverTime: String,
)
@Serializable data class LatestSummaryResponse(
    val paired: Boolean,
    @SerialName("phone_name") val phoneName: String? = null,
    @SerialName("server_time") val serverTime: String,
    @SerialName("received_at") val receivedAt: String? = null,
    @Serializable(with = LenientSummarySerializer::class) val summary: SummaryDto? = null,
)

@Serializable data class VehicleRegisterRequest(@SerialName("app_version") val appVersion: String)
@Serializable data class VehicleRegisterResponse(
    @SerialName("account_id") val accountId: String, @SerialName("device_id") val deviceId: String,
    @SerialName("vehicle_token") val vehicleToken: String, @SerialName("server_time") val serverTime: String,
)
@Serializable data class PairingCodeResponse(
    val code: String, @SerialName("qr_payload") val qrPayload: String, @SerialName("expires_at") val expiresAt: String,
    @SerialName("ttl_sec") val ttlSec: Int, @SerialName("replaces_phone") val replacesPhone: Boolean,
    @SerialName("server_time") val serverTime: String,
)
@Serializable data class ConsentItem(val type: String, val version: String)
@Serializable data class PairingClaimRequest(
    val code: String, @SerialName("device_name") val deviceName: String,
    @SerialName("app_version") val appVersion: String, val consents: List<ConsentItem>,
)
@Serializable data class PairingClaimResponse(
    @SerialName("account_id") val accountId: String, @SerialName("device_id") val deviceId: String,
    @SerialName("phone_token") val phoneToken: String, @SerialName("server_time") val serverTime: String,
)
@Serializable data class PhoneInfo(
    @SerialName("device_id") val deviceId: String, @SerialName("device_name") val deviceName: String,
    @SerialName("paired_at") val pairedAt: String, @SerialName("last_seen_at") val lastSeenAt: String?,
)
@Serializable data class PairingStatusResponse(
    val paired: Boolean, val phone: PhoneInfo?, @SerialName("vehicle_last_seen_at") val vehicleLastSeenAt: String?,
    @SerialName("pending_code_expires_at") val pendingCodeExpiresAt: String?, @SerialName("server_time") val serverTime: String,
)

@Serializable data class TripSegmentDto(val type: String, val start: String, val end: String?)  // "drive" | "rest"
@Serializable data class TripUploadRequest(
    @SerialName("trip_id") val tripId: String, val rev: Int,
    @SerialName("started_at") val startedAt: String, @SerialName("ended_at") val endedAt: String?,
    @SerialName("drive_sec") val driveSec: Long, @SerialName("rest_sec") val restSec: Long,
    val segments: List<TripSegmentDto>,
    @SerialName("stop_advised_count") val stopAdvisedCount: Int,
    @SerialName("declined_count") val declinedCount: Int,
    @SerialName("rest_completed_count") val restCompletedCount: Int,
)
@Serializable data class TripUploadResponse(val applied: Boolean, @SerialName("trip_id") val tripId: String, val rev: Int, @SerialName("server_time") val serverTime: String)

@Serializable data class GuardianAlertRequest(
    @SerialName("alert_id") val alertId: String, val reason: String,   // "glucose_stop_ignored"만
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("ignored_for_sec") val ignoredForSec: Int,
    @SerialName("glucose_source") val glucoseSource: GlucoseSourceDto,
)
@Serializable data class GuardianAlertResponse(@SerialName("alert_id") val alertId: String, val delivery: String, @SerialName("server_time") val serverTime: String)
@Serializable data class FieldViolation(val field: String, val rule: String)
@Serializable data class ApiError(
    val code: String, val message: String, val fields: List<FieldViolation> = emptyList(),
    @SerialName("request_id") val requestId: String, @SerialName("server_time") val serverTime: String,
)
@Serializable data class ApiErrorEnvelope(val error: ApiError)

// 이하 11.2의 "12장 JSON을 그대로 옮긴다"에 따른 DTO. 이름은 설계서에 없어 여기서 정한다

@Serializable data class HealthResponse(
    val status: String, val version: String, val db: String, @SerialName("server_time") val serverTime: String,
)
@Serializable data class UnpairResponse(
    val unpaired: Boolean, @SerialName("summary_deleted") val summaryDeleted: Boolean, @SerialName("server_time") val serverTime: String,
)
@Serializable data class SummaryDeleteResponse(val deleted: Boolean, @SerialName("server_time") val serverTime: String)
@Serializable data class AccountDeleteResponse(val deleted: Boolean)

@Serializable data class ConsentPutRequest(val version: String)
@Serializable data class ConsentPutResponse(val type: String, val version: String, @SerialName("granted_at") val grantedAt: String)
@Serializable data class ConsentWithdrawResponse(val withdrawn: Boolean, @SerialName("data_deleted") val dataDeleted: Boolean)

@Serializable data class TripDeleteResponse(
    @SerialName("deleted_count") val deletedCount: Int,
    @SerialName("trips_deleted_before") val tripsDeletedBefore: String?,
    @SerialName("server_time") val serverTime: String,
)

@Serializable data class DriverConsent(val version: String, val agreed: Boolean)
@Serializable data class GuardianRegisterRequest(
    @SerialName("phone_e164") val phoneE164: String,
    @SerialName("guardian_label") val guardianLabel: String,
    @SerialName("driver_display_name") val driverDisplayName: String,
    @SerialName("driver_consent") val driverConsent: DriverConsent,
)
@Serializable data class GuardianRegisterResponse(
    @SerialName("guardian_id") val guardianId: String, val status: String,
    @SerialName("phone_masked") val phoneMasked: String,
    @SerialName("consent_expires_at") val consentExpiresAt: String,
    val delivery: String, @SerialName("server_time") val serverTime: String,
)
@Serializable data class GuardianConfirmRequest(val code: String)
@Serializable data class GuardianConfirmResponse(
    @SerialName("guardian_id") val guardianId: String, val status: String,
    @SerialName("guardian_consented_at") val guardianConsentedAt: String,
    @SerialName("server_time") val serverTime: String,
)
@Serializable data class GuardianOptOutRequest(@SerialName("opt_out_code") val optOutCode: String)
@Serializable data class GuardianOptOutResponse(val ok: Boolean)
