package com.conditiondrive.server.routes

import com.conditiondrive.core.contract.ApiError
import com.conditiondrive.core.contract.ApiErrorEnvelope
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.FieldViolation
import com.conditiondrive.core.contract.SummaryLimits
import com.conditiondrive.core.contract.Violation
import com.conditiondrive.core.contract.ViolationCodes
import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.ApiTime
import com.conditiondrive.server.Limits
import com.conditiondrive.server.auth.Codes
import com.conditiondrive.server.auth.Role
import com.conditiondrive.server.auth.Tokens
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.sql.SQLException
import kotlin.coroutines.cancellation.CancellationException

object ApiCodes {
    const val MALFORMED_JSON = "malformed_json"
    const val INVALID_TOKEN = "invalid_token"
    const val DEVICE_REVOKED = "device_revoked"
    const val WRONG_DEVICE_ROLE = "wrong_device_role"
    const val CONSENT_REQUIRED = "consent_required"
    const val NOT_FOUND = "not_found"
    const val METHOD_NOT_ALLOWED = "method_not_allowed"
    const val PAIRING_CODE_INVALID = "pairing_code_invalid"
    const val PAIRING_CODE_EXPIRED = "pairing_code_expired"
    const val CONSENT_CODE_EXPIRED = "consent_code_expired"
    const val CONSENT_CODE_INVALID = "consent_code_invalid"
    const val GUARDIAN_NOT_ACTIVE = "guardian_not_active"
    const val GUARDIAN_EXISTS = "guardian_exists"
    const val GUARDIAN_OPTED_OUT = "guardian_opted_out"
    const val PAYLOAD_TOO_LARGE = "payload_too_large"
    const val UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type"
    const val STALE_ALERT = "stale_alert"
    const val RATE_LIMITED = "rate_limited"
    const val ALERT_RATE_LIMITED = "alert_rate_limited"
    const val INTERNAL_ERROR = "internal_error"
    const val UNAVAILABLE = "unavailable"
    const val SENDER_FAILED = "sender_failed"
}

/** message를 두지 않는다. 입력 값이 예외 메시지로 들어가 로그나 응답에 나갈 길을 막는다(12.17). */
class ApiException(
    val status: HttpStatusCode,
    val code: String,
    val fields: List<FieldViolation> = emptyList(),
    val retryAfterMs: Long? = null,
) : Exception()

fun validationError(field: String, rule: String) = violationError(listOf(Violation(field, rule, ViolationCodes.VALIDATION_FAILED)))

fun violationError(violations: List<Violation>) = ApiException(
    HttpStatusCode.UnprocessableEntity,
    violations.first().code,
    violations.map { FieldViolation(it.field, it.rule) },
)

fun rateLimited(retryAfterMs: Long, code: String = ApiCodes.RATE_LIMITED) =
    ApiException(HttpStatusCode.TooManyRequests, code, retryAfterMs = retryAfterMs)

/** 사용자에게 보이지 않는다(13장). 입력을 담지 않는 고정 문장만 쓴다. */
private val MESSAGES = mapOf(
    ViolationCodes.VALIDATION_FAILED to "요청 값을 확인해 주세요.",
    ViolationCodes.UNKNOWN_FIELD to "허용되지 않은 항목이 있어요.",
    ViolationCodes.RAW_HEALTH_DATA_REJECTED to "측정 원본은 받지 않아요.",
    ViolationCodes.GLUCOSE_SOURCE_NOT_ALLOWED to "허용되지 않은 출처예요.",
    ViolationCodes.CLOCK_SKEW to "기기 시각을 확인해 주세요.",
    ViolationCodes.SUMMARY_EXPIRED to "유효 시간이 지났어요.",
    ApiCodes.MALFORMED_JSON to "요청 형식이 올바르지 않아요.",
    ApiCodes.INVALID_TOKEN to "인증 정보가 올바르지 않아요.",
    ApiCodes.DEVICE_REVOKED to "연결이 해제된 기기예요.",
    ApiCodes.WRONG_DEVICE_ROLE to "이 기기에서 호출할 수 없어요.",
    ApiCodes.CONSENT_REQUIRED to "동의가 필요해요.",
    ApiCodes.PAYLOAD_TOO_LARGE to "요청이 너무 커요.",
    ApiCodes.UNSUPPORTED_MEDIA_TYPE to "지원하지 않는 형식이에요.",
    ApiCodes.RATE_LIMITED to "잠시 뒤 다시 시도해 주세요.",
    ApiCodes.ALERT_RATE_LIMITED to "잠시 뒤 다시 시도해 주세요.",
    ApiCodes.INTERNAL_ERROR to "서버 오류가 발생했어요.",
    ApiCodes.UNAVAILABLE to "서버를 사용할 수 없어요.",
    ApiCodes.SENDER_FAILED to "발송에 실패했어요.",
)
private const val DEFAULT_MESSAGE = "요청을 처리하지 못했어요."

private val RequestIdKey = AttributeKey<String>("cd.requestId")
private val RouteKey = AttributeKey<String>("cd.route")
private val CallerKey = AttributeKey<String>("cd.caller")
private const val UNMATCHED_ROUTE = "unmatched"
private const val NANOS_PER_MS = 1_000_000L

fun ApplicationCall.markCaller(accountId: String, role: Role) {
    val acct = Tokens.hex(Tokens.sha256(accountId)).take(8)
    attributes.put(CallerKey, "acct=$acct role=${role.db}")
}

/**
 * 모든 응답의 공통 헤더와 12.17 접근 로그. 경로는 템플릿만 쓴다.
 * 실제 경로와 쿼리스트링에는 ID나 시각 값이 있어 로그에 남기지 않는다.
 */
private val RequestContext = createApplicationPlugin("CdRequestContext") {
    val access = LoggerFactory.getLogger("cd.access")
    val startKey = AttributeKey<Long>("cd.start")
    onCall { call ->
        // 클라이언트가 보낸 ID를 쓰면 로그에 입력이 들어간다
        val requestId = Codes.requestId()
        call.attributes.put(RequestIdKey, requestId)
        call.attributes.put(startKey, System.nanoTime())
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.response.header("X-Request-Id", requestId)
    }
    on(ResponseSent) { call ->
        val elapsedMs = (System.nanoTime() - call.attributes[startKey]) / NANOS_PER_MS
        val route = call.attributes.getOrNull(RouteKey) ?: UNMATCHED_ROUTE
        val status = call.response.status()?.value ?: 0
        val caller = call.attributes.getOrNull(CallerKey) ?: "acct=- role=-"
        access.info("${call.attributes[RequestIdKey]} ${call.request.httpMethod.value} $route $status $elapsedMs $caller")
    }
}

fun Application.installHttp(clock: Clock) {
    val logger = LoggerFactory.getLogger("cd.server")
    install(RequestContext)
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondApiError(clock, e) }
        exception<SQLException> { call, e ->
            logger.error("db_error type=${e::class.simpleName}")
            call.respondApiError(clock, ApiException(HttpStatusCode.ServiceUnavailable, ApiCodes.UNAVAILABLE))
        }
        exception<Throwable> { call, e ->
            if (e is CancellationException) throw e
            // kotlinx.serialization 예외 메시지는 입력 JSON을 담을 수 있다(12.17)
            logger.error("unhandled type=${e::class.simpleName}")
            call.respondApiError(clock, ApiException(HttpStatusCode.InternalServerError, ApiCodes.INTERNAL_ERROR))
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respondApiError(clock, ApiException(HttpStatusCode.NotFound, ApiCodes.NOT_FOUND))
        }
        status(HttpStatusCode.MethodNotAllowed) { call, _ ->
            call.respondApiError(clock, ApiException(HttpStatusCode.MethodNotAllowed, ApiCodes.METHOD_NOT_ALLOWED))
        }
    }
}

private suspend fun ApplicationCall.respondApiError(clock: Clock, e: ApiException) {
    e.retryAfterMs?.let { response.header(HttpHeaders.RetryAfter, retryAfterSeconds(it).toString()) }
    val requestId = attributes.getOrNull(RequestIdKey) ?: Codes.requestId()
    val body = ApiErrorEnvelope(
        ApiError(e.code, MESSAGES[e.code] ?: DEFAULT_MESSAGE, e.fields, requestId, ApiTime.format(clock.nowMs())),
    )
    respondText(ContractJson.Strict.encodeToString(body), ContentType.Application.Json, e.status)
}

/** 12.1: 클라이언트는 최대 5분만 기다린다. 더 긴 값을 알려도 쓰이지 않으므로 상한을 맞춘다. */
private fun retryAfterSeconds(ms: Long): Long =
    ((ms + 999) / 1000).coerceIn(1, Limits.RETRY_AFTER_MAX_SEC)

/** 라우트 템플릿을 로그용으로 남기면서 핸들러를 등록한다. */
fun Route.endpoint(method: HttpMethod, template: String, handler: suspend (ApplicationCall) -> Unit) {
    route(template, method) {
        handle {
            call.attributes.put(RouteKey, template)
            handler(call)
        }
    }
}

suspend inline fun <reified T> ApplicationCall.respondJson(value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    check(status.isSuccess() || status == HttpStatusCode.ServiceUnavailable)
    respondText(ContractJson.Strict.encodeToString(value), ContentType.Application.Json, status)
}

enum class Kind { STRING, STRING_OR_NULL, INT, LONG, BOOL, ARRAY, OBJECT }

/** 12.1: Content-Length를 믿지 않고 스트림에서 잘라 파싱 전에 크기를 강제한다. */
suspend fun ApplicationCall.readJsonObject(limit: Int): JsonObject {
    val type = request.contentType()
    if (!type.match(ContentType.Application.Json)) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, ApiCodes.UNSUPPORTED_MEDIA_TYPE)
    }
    val declared = request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
    if (declared != null && declared > limit) throw tooLarge()
    val bytes = receiveChannel().readBuffer(limit + 1L).readByteArray()
    if (bytes.size > limit) throw tooLarge()
    val element = try {
        ContractJson.Strict.parseToJsonElement(bytes.toString(Charsets.UTF_8))
    } catch (_: IllegalArgumentException) {
        throw malformed()
    }
    return element as? JsonObject ?: throw malformed()
}

private fun tooLarge() = ApiException(HttpStatusCode.PayloadTooLarge, ApiCodes.PAYLOAD_TOO_LARGE)
private fun malformed() = ApiException(HttpStatusCode.BadRequest, ApiCodes.MALFORMED_JSON)

// 키 이름도 입력이다. 전화번호처럼 생긴 키가 응답으로 되돌아가지 않게 식별자 꼴만 그대로 쓴다
private val SAFE_KEY = Regex("""[a-z][a-z0-9_]{0,39}""")
private const val UNSAFE_KEY_LABEL = "_invalid_key"

private fun safeKey(key: String) = if (SAFE_KEY.matches(key)) key else UNSAFE_KEY_LABEL

/**
 * 요약 외 본문의 12.7 2~5번 대응. 허용 목록 밖의 키를 받지 않아 사유·혈당·좌표가 어느 경로로도 저장되지 않게 한다.
 * prefix는 중첩 객체의 필드 이름을 구분한다.
 */
fun checkShape(obj: JsonObject, schema: Map<String, Kind>, prefix: String = ""): List<Violation> = buildList {
    for (key in obj.keys) {
        val lower = key.lowercase()
        when {
            lower in SummaryLimits.FORBIDDEN_KEYS ->
                add(Violation(prefix + lower, "forbidden_key", ViolationCodes.RAW_HEALTH_DATA_REJECTED))
            key !in schema -> add(Violation(prefix + safeKey(key), "unknown_field", ViolationCodes.UNKNOWN_FIELD))
        }
    }
    for ((key, kind) in schema) {
        val value = obj[key]
        when {
            value == null -> add(Violation(prefix + key, "required", ViolationCodes.VALIDATION_FAILED))
            !kind.accepts(value) -> add(Violation(prefix + key, "format", ViolationCodes.VALIDATION_FAILED))
        }
    }
}

private fun Kind.accepts(value: kotlinx.serialization.json.JsonElement): Boolean = when (this) {
    Kind.STRING -> value is JsonPrimitive && value !is JsonNull && value.isString
    Kind.STRING_OR_NULL -> value is JsonNull || (value is JsonPrimitive && value.isString)
    Kind.INT -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.intOrNull != null
    Kind.LONG -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.longOrNull != null
    Kind.BOOL -> value is JsonPrimitive && value !is JsonNull && !value.isString && value.booleanOrNull != null
    Kind.ARRAY -> value is JsonArray
    Kind.OBJECT -> value is JsonObject
}

fun throwIfAny(violations: List<Violation>) {
    if (violations.isNotEmpty()) throw violationError(violations)
}

/** 예외 메시지에 입력이 담기므로 원인을 버리고 필드 없는 오류로 바꾼다. */
fun <T> decodeOr422(strategy: DeserializationStrategy<T>, obj: JsonObject): T = try {
    ContractJson.Strict.decodeFromJsonElement(strategy, obj)
} catch (_: IllegalArgumentException) {
    throw ApiException(HttpStatusCode.UnprocessableEntity, ViolationCodes.VALIDATION_FAILED)
}

suspend fun <T> ApplicationCall.readChecked(limit: Int, strategy: DeserializationStrategy<T>, schema: Map<String, Kind>): T {
    val obj = readJsonObject(limit)
    throwIfAny(checkShape(obj, schema))
    return decodeOr422(strategy, obj)
}
