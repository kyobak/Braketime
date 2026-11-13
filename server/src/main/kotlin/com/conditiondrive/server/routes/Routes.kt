package com.conditiondrive.server.routes

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ConsentPutRequest
import com.conditiondrive.core.contract.GuardianAlertRequest
import com.conditiondrive.core.contract.GuardianConfirmRequest
import com.conditiondrive.core.contract.GuardianOptOutRequest
import com.conditiondrive.core.contract.GuardianOptOutResponse
import com.conditiondrive.core.contract.GuardianRegisterRequest
import com.conditiondrive.core.contract.HealthResponse
import com.conditiondrive.core.contract.PairingClaimRequest
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.contract.SummaryValidator
import com.conditiondrive.core.contract.TripUploadRequest
import com.conditiondrive.core.contract.VehicleRegisterRequest
import com.conditiondrive.server.ApiTime
import com.conditiondrive.server.Config
import com.conditiondrive.server.Limits
import com.conditiondrive.server.ServerApp
import com.conditiondrive.server.auth.Role
import com.conditiondrive.server.service.ClaimResult
import com.conditiondrive.server.service.ConsentTypes
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val ANY_DEVICE = arrayOf(Role.VEHICLE, Role.PHONE)
private const val SHORT_TEXT_MAX = 40
private const val GUARDIAN_ID_PARAM = "id"
private const val CONSENT_TYPE_PARAM = "type"
private val ALERT_SOURCES = setOf("simulated", "partner_realtime")

fun Route.apiRoutes(app: ServerApp) {
    healthRoutes(app)
    pairingRoutes(app)
    summaryRoutes(app)
    consentRoutes(app)
    tripRoutes(app)
    guardianRoutes(app)
}

private fun Route.healthRoutes(app: ServerApp) {
    endpoint(HttpMethod.Get, ApiPaths.HEALTHZ) { call ->
        val ok = app.db.ping()
        val body = HealthResponse(
            status = if (ok) "ok" else "degraded",
            version = Config.VERSION,
            db = if (ok) "ok" else "error",
            serverTime = ApiTime.format(app.clock.nowMs()),
        )
        call.respondJson(body, if (ok) HttpStatusCode.OK else HttpStatusCode.ServiceUnavailable)
    }
}

private fun isShortText(value: String) = value.isNotBlank() && value.length <= SHORT_TEXT_MAX && value.none(Char::isISOControl)

private fun Route.pairingRoutes(app: ServerApp) {
    endpoint(HttpMethod.Post, ApiPaths.DEVICES_VEHICLE) { call ->
        val request = call.readChecked(Limits.BODY_DEFAULT, VehicleRegisterRequest.serializer(), mapOf("app_version" to Kind.STRING))
        if (!isShortText(request.appVersion)) throw validationError("app_version", "range")
        app.limiter.acquire("vehicle_register", Limits.VEHICLE_REGISTER_PER_HOUR, Limits.HOUR_MS)
        call.respondJson(app.accounts.registerVehicle(), HttpStatusCode.Created)
    }

    endpoint(HttpMethod.Post, ApiPaths.PAIRING_CODES) { call ->
        val auth = app.auth.require(call, Role.VEHICLE)
        app.limiter.acquire("pairing_codes:${auth.deviceId}", Limits.PAIRING_CODES_PER_HOUR, Limits.HOUR_MS)
        call.respondJson(app.accounts.createPairingCode(auth), HttpStatusCode.Created)
    }

    endpoint(HttpMethod.Post, ApiPaths.PAIRING_CLAIM) { call -> handleClaim(app, call) }

    endpoint(HttpMethod.Get, ApiPaths.PAIRING) { call ->
        val auth = app.auth.require(call, *ANY_DEVICE)
        app.limiter.acquire("pairing_status:${auth.deviceId}", Limits.PAIRING_STATUS_PER_MIN, Limits.MINUTE_MS)
        call.respondJson(app.accounts.pairingStatus(auth))
    }

    endpoint(HttpMethod.Delete, ApiPaths.PAIRING) { call ->
        val auth = app.auth.require(call, *ANY_DEVICE)
        call.respondJson(app.accounts.unpair(auth))
    }

    endpoint(HttpMethod.Delete, ApiPaths.ACCOUNT) { call ->
        val auth = app.auth.require(call, Role.VEHICLE)
        call.respondJson(app.accounts.deleteAccount(auth))
    }
}

private suspend fun handleClaim(app: ServerApp, call: ApplicationCall) {
    app.claimGuard.checkNotLocked()
    val obj = call.readJsonObject(Limits.BODY_DEFAULT)
    throwIfAny(
        checkShape(
            obj,
            mapOf("code" to Kind.STRING, "device_name" to Kind.STRING, "app_version" to Kind.STRING, "consents" to Kind.ARRAY),
        ),
    )
    val consentItems = obj.getValue("consents").jsonArray
    consentItems.forEach { item ->
        if (item !is JsonObject) throw validationError("consents", "format")
        throwIfAny(checkShape(item, mapOf("type" to Kind.STRING, "version" to Kind.STRING), prefix = "consents."))
    }
    val request = decodeOr422(PairingClaimRequest.serializer(), obj)
    if (request.deviceName.isBlank() || request.deviceName.length > SHORT_TEXT_MAX || request.deviceName.any(Char::isISOControl)) {
        throw validationError("device_name", "range")
    }
    if (!isShortText(request.appVersion)) throw validationError("app_version", "range")
    request.consents.forEach {
        if (it.type !in ConsentTypes.ALL) throw validationError("consents.type", "enum")
        if (!isShortText(it.version)) throw validationError("consents.version", "range")
    }
    val hasHealthConsent = request.consents.any { it.type == ConsentTypes.HEALTH_SUMMARY }
    // 코드 검사 전에 막으면 코드를 소비하지 않고, 추측 시도로도 세지 않는다
    if (!hasHealthConsent) throw ApiException(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
    when (val result = app.accounts.claim(request, request.consents)) {
        is ClaimResult.Claimed -> {
            call.respondJson(result.response, HttpStatusCode.Created)
        }
        ClaimResult.Invalid -> {
            app.claimGuard.recordFailure()
            throw ApiException(HttpStatusCode.NotFound, ApiCodes.PAIRING_CODE_INVALID)
        }
        ClaimResult.Expired -> {
            app.claimGuard.recordFailure()
            throw ApiException(HttpStatusCode.Gone, ApiCodes.PAIRING_CODE_EXPIRED)
        }
    }
}

private fun Route.summaryRoutes(app: ServerApp) {
    endpoint(HttpMethod.Post, ApiPaths.SUMMARY) { call ->
        val auth = app.auth.require(call, Role.PHONE)
        app.limiter.acquire("summary_post:${auth.deviceId}", Limits.SUMMARY_POST_PER_MIN, Limits.MINUTE_MS)
        if (!app.consents.isActive(auth.accountId, ConsentTypes.HEALTH_SUMMARY)) {
            throw ApiException(HttpStatusCode.Forbidden, ApiCodes.CONSENT_REQUIRED)
        }
        val obj = call.readJsonObject(Limits.BODY_SUMMARY)
        throwIfAny(SummaryValidator.validateRawKeys(obj))
        val dto = decodeOr422(SummaryDto.serializer(), obj)
        throwIfAny(SummaryValidator.validate(dto, app.clock.nowMs(), app.config.allowPartnerRealtime))
        call.respondJson(app.summaries.upload(auth, dto))
    }

    endpoint(HttpMethod.Get, ApiPaths.SUMMARY_LATEST) { call ->
        val auth = app.auth.require(call, Role.VEHICLE)
        app.limiter.acquire("summary_latest:${auth.deviceId}", Limits.SUMMARY_LATEST_PER_MIN, Limits.MINUTE_MS)
        call.respondJson(app.summaries.latest(auth))
    }

    endpoint(HttpMethod.Delete, ApiPaths.SUMMARY) { call ->
        val auth = app.auth.require(call, Role.PHONE)
        call.respondJson(app.summaries.delete(auth))
    }
}

private fun Route.consentRoutes(app: ServerApp) {
    val template = "${ApiPaths.CONSENTS}/{$CONSENT_TYPE_PARAM}"

    endpoint(HttpMethod.Put, template) { call ->
        val type = call.consentType()
        // 건강정보 동의는 폰에서만 받는다(14장 C1). 차량 토큰의 철회(DELETE)는 API 능력으로만 남고 차량 화면에는 없다(U-31)
        val roles = if (type == ConsentTypes.HEALTH_SUMMARY) arrayOf(Role.PHONE) else ANY_DEVICE
        val auth = app.auth.require(call, *roles)
        val request = call.readChecked(Limits.BODY_DEFAULT, ConsentPutRequest.serializer(), mapOf("version" to Kind.STRING))
        if (!isShortText(request.version)) throw validationError("version", "range")
        call.respondJson(app.consents.grant(auth.accountId, type, request.version))
    }

    endpoint(HttpMethod.Delete, template) { call ->
        val type = call.consentType()
        val auth = app.auth.require(call, *ANY_DEVICE)
        call.respondJson(app.consents.withdraw(auth.accountId, type))
    }
}

private fun ApplicationCall.consentType(): String =
    parameters[CONSENT_TYPE_PARAM]?.takeIf { it in ConsentTypes.ALL }
        ?: throw ApiException(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)

private fun Route.tripRoutes(app: ServerApp) {
    val segmentSchema = mapOf("type" to Kind.STRING, "start" to Kind.STRING, "end" to Kind.STRING_OR_NULL)
    val tripSchema = mapOf(
        "trip_id" to Kind.STRING, "rev" to Kind.INT, "started_at" to Kind.STRING, "ended_at" to Kind.STRING_OR_NULL,
        "drive_sec" to Kind.LONG, "rest_sec" to Kind.LONG, "segments" to Kind.ARRAY,
        "stop_advised_count" to Kind.INT, "declined_count" to Kind.INT, "rest_completed_count" to Kind.INT,
    )

    endpoint(HttpMethod.Post, ApiPaths.TRIPS) { call ->
        val auth = app.auth.require(call, Role.VEHICLE)
        app.limiter.acquire("trips:${auth.deviceId}", Limits.TRIPS_PER_MIN, Limits.MINUTE_MS)
        val obj = call.readJsonObject(Limits.BODY_TRIP)
        throwIfAny(checkShape(obj, tripSchema))
        (obj.getValue("segments") as JsonArray).forEach { segment ->
            if (segment !is JsonObject) throw validationError("segments", "format")
            throwIfAny(checkShape(segment, segmentSchema, prefix = "segments."))
        }
        val trip = decodeOr422(TripUploadRequest.serializer(), obj)
        throwIfAny(app.trips.validate(trip, app.clock.nowMs()))
        val result = app.trips.upload(auth.accountId, trip)
        call.respondJson(result.response, if (result.created) HttpStatusCode.Created else HttpStatusCode.OK)
    }

    endpoint(HttpMethod.Delete, ApiPaths.TRIPS) { call ->
        val auth = app.auth.require(call, *ANY_DEVICE)
        val before = call.request.queryParameters["before"]?.let {
            ApiTime.parseMs(it) ?: throw validationError("before", "format")
        }
        call.respondJson(app.trips.delete(auth.accountId, before))
    }
}

private fun Route.guardianRoutes(app: ServerApp) {
    val registerSchema = mapOf(
        "phone_e164" to Kind.STRING, "guardian_label" to Kind.STRING,
        "driver_display_name" to Kind.STRING, "driver_consent" to Kind.OBJECT,
    )
    val driverConsentSchema = mapOf("version" to Kind.STRING, "agreed" to Kind.BOOL)
    val alertSchema = mapOf(
        "alert_id" to Kind.STRING, "reason" to Kind.STRING, "occurred_at" to Kind.STRING,
        "ignored_for_sec" to Kind.INT, "glucose_source" to Kind.STRING,
    )

    endpoint(HttpMethod.Post, ApiPaths.GUARDIANS) { call ->
        val auth = app.auth.require(call, *ANY_DEVICE)
        val obj = call.readJsonObject(Limits.BODY_DEFAULT)
        throwIfAny(checkShape(obj, registerSchema))
        throwIfAny(checkShape(obj.getValue("driver_consent") as JsonObject, driverConsentSchema, prefix = "driver_consent."))
        val request = decodeOr422(GuardianRegisterRequest.serializer(), obj)
        call.respondJson(app.guardians.register(auth.accountId, request), HttpStatusCode.Created)
    }

    endpoint(HttpMethod.Get, ApiPaths.GUARDIANS) { call ->
        val auth = app.auth.require(call, *ANY_DEVICE)
        call.respondJson(app.guardians.current(auth.accountId))
    }

    endpoint(HttpMethod.Post, "${ApiPaths.GUARDIANS}/{$GUARDIAN_ID_PARAM}/confirm") { call ->
        val auth = app.auth.require(call, *ANY_DEVICE)
        val request = call.readChecked(Limits.BODY_DEFAULT, GuardianConfirmRequest.serializer(), mapOf("code" to Kind.STRING))
        call.respondJson(app.guardians.confirm(auth.accountId, call.guardianId(), request.code))
    }

    endpoint(HttpMethod.Post, "${ApiPaths.GUARDIANS}/{$GUARDIAN_ID_PARAM}/resend") { call ->
        val auth = app.auth.require(call, *ANY_DEVICE)
        call.respondJson(app.guardians.resend(auth.accountId, call.guardianId()))
    }

    endpoint(HttpMethod.Delete, "${ApiPaths.GUARDIANS}/{$GUARDIAN_ID_PARAM}") { call ->
        val auth = app.auth.require(call, *ANY_DEVICE)
        call.respondJson(app.guardians.delete(auth.accountId, call.guardianId()))
    }

    endpoint(HttpMethod.Post, ApiPaths.GUARDIAN_OPT_OUT) { call ->
        val request = call.readChecked(Limits.BODY_DEFAULT, GuardianOptOutRequest.serializer(), mapOf("opt_out_code" to Kind.STRING))
        app.limiter.acquire("guardian_opt_out", Limits.OPT_OUT_PER_MIN, Limits.MINUTE_MS)
        app.guardians.optOut(request.optOutCode)
        call.respondJson(GuardianOptOutResponse(ok = true))
    }

    endpoint(HttpMethod.Post, ApiPaths.GUARDIAN_ALERTS) { call ->
        val auth = app.auth.require(call, Role.VEHICLE)
        val obj = call.readJsonObject(Limits.BODY_DEFAULT)
        throwIfAny(checkShape(obj, alertSchema))
        // DTO enum 디코딩이 실패하면 필드 없는 오류가 되므로 먼저 본다
        if (obj.getValue("glucose_source").jsonPrimitive.content !in ALERT_SOURCES) throw validationError("glucose_source", "enum")
        val request = decodeOr422(GuardianAlertRequest.serializer(), obj)
        val result = app.guardians.alert(auth.accountId, request)
        call.respondJson(result.response, if (result.created) HttpStatusCode.Accepted else HttpStatusCode.OK)
    }
}

private fun ApplicationCall.guardianId(): String =
    parameters[GUARDIAN_ID_PARAM] ?: throw ApiException(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND)
