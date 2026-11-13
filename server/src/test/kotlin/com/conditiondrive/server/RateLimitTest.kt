package com.conditiondrive.server

import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.time.ManualClock
import com.conditiondrive.server.routes.ApiCodes
import com.conditiondrive.server.routes.ApiException
import com.conditiondrive.server.service.RateLimiter
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class RateLimitTest {
    private val tooMany = HttpStatusCode.TooManyRequests

    private fun Res.assertLimited(expectedRetryAfter: String) {
        assertError(tooMany, ApiCodes.RATE_LIMITED)
        assertEquals(expectedRetryAfter, assertNotNull(headers[HttpHeaders.RetryAfter]))
    }

    @Test fun vehicleRegisterGlobalPerHour() = serverTest {
        repeat(Limits.VEHICLE_REGISTER_PER_HOUR) { registerVehicle().assertOk(HttpStatusCode.Created) }
        registerVehicle().assertLimited("300")
        // 값이 틀린 요청은 제한을 쓰지 않아 쓰레기 요청이 정상 등록을 막지 못한다
        post(ApiPaths.DEVICES_VEHICLE, "{}").assertError(HttpStatusCode.UnprocessableEntity, "validation_failed")
        clock.advance(HOUR)
        registerVehicle().assertOk(HttpStatusCode.Created)
    }

    @Test fun pairingCodesPerVehiclePerHour() = serverTest {
        val vehicle = vehicleToken()
        repeat(Limits.PAIRING_CODES_PER_HOUR) { post(ApiPaths.PAIRING_CODES, token = vehicle).assertOk(HttpStatusCode.Created) }
        post(ApiPaths.PAIRING_CODES, token = vehicle).assertLimited("300")
        // 차량별 제한이다
        post(ApiPaths.PAIRING_CODES, token = vehicleToken()).assertOk(HttpStatusCode.Created)
        clock.advance(HOUR)
        post(ApiPaths.PAIRING_CODES, token = vehicle).assertOk(HttpStatusCode.Created)
    }

    @Test fun pairingStatusPerDevicePerMinute() = serverTest {
        val p = pair()
        repeat(Limits.PAIRING_STATUS_PER_MIN) { get(ApiPaths.PAIRING, p.vehicle).assertOk() }
        get(ApiPaths.PAIRING, p.vehicle).assertLimited("60")
        // 폰은 별도 기기다
        get(ApiPaths.PAIRING, p.phone).assertOk()
        clock.advance(MIN)
        get(ApiPaths.PAIRING, p.vehicle).assertOk()
    }

    @Test fun summaryPostPerDevicePerMinute() = serverTest {
        val p = pair()
        repeat(Limits.SUMMARY_POST_PER_MIN) {
            clock.advance(1)
            upload(p.phone).assertOk()
        }
        upload(p.phone).assertError(tooMany, ApiCodes.RATE_LIMITED)
        clock.advance(MIN)
        upload(p.phone).assertOk()
    }

    @Test fun summaryLatestPerDevicePerMinute() = serverTest {
        val p = pair()
        repeat(Limits.SUMMARY_LATEST_PER_MIN) { get(ApiPaths.SUMMARY_LATEST, p.vehicle).assertOk() }
        get(ApiPaths.SUMMARY_LATEST, p.vehicle).assertLimited("60")
        clock.advance(MIN)
        get(ApiPaths.SUMMARY_LATEST, p.vehicle).assertOk()
    }

    @Test fun tripsPerVehiclePerMinute() = serverTest {
        val vehicle = vehicleToken()
        repeat(Limits.TRIPS_PER_MIN) { post(ApiPaths.TRIPS, trip(tripId = UUID.randomUUID().toString()), vehicle).assertOk(HttpStatusCode.Created) }
        post(ApiPaths.TRIPS, trip(tripId = UUID.randomUUID().toString()), vehicle).assertLimited("60")
    }

    @Test fun windowSlidesAndRejectedCallsDoNotExtendIt() {
        val clock = ManualClock(0)
        val limiter = RateLimiter(clock)
        limiter.acquire("k", 2, 1000)
        clock.advance(600)
        limiter.acquire("k", 2, 1000)
        clock.advance(399)
        val first = assertFailsWith<ApiException> { limiter.acquire("k", 2, 1000) }
        assertEquals(1L, first.retryAfterMs)
        clock.advance(1)
        limiter.acquire("k", 2, 1000)
        assertFailsWith<ApiException> { limiter.acquire("k", 2, 1000) }
        clock.advance(600)
        limiter.acquire("k", 2, 1000)
    }

    @Test fun pruneForgetsExpiredKeys() {
        val clock = ManualClock(0)
        val limiter = RateLimiter(clock)
        limiter.acquire("k", 1, 1000)
        clock.advance(1000)
        limiter.prune()
        limiter.acquire("k", 1, 1000)
    }
}
