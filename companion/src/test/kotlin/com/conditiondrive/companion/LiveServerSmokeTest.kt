package com.conditiondrive.companion

import com.conditiondrive.companion.pairing.ClaimResult
import com.conditiondrive.companion.pairing.ConsentPolicy
import com.conditiondrive.companion.pairing.PairingLink
import com.conditiondrive.companion.pairing.PairingService
import com.conditiondrive.companion.pairing.RemoteDeletion
import com.conditiondrive.companion.storage.StoredState
import com.conditiondrive.companion.upload.ApiResult
import com.conditiondrive.companion.upload.GlucoseReading
import com.conditiondrive.companion.upload.OkHttpCompanionApi
import com.conditiondrive.companion.upload.SleepValue
import com.conditiondrive.companion.upload.SummaryInput
import com.conditiondrive.companion.upload.UploadCoordinator
import com.conditiondrive.companion.upload.UploadStatus
import com.conditiondrive.core.contract.ApiPaths
import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.contract.LatestSummaryResponse
import com.conditiondrive.core.contract.PairingCodeResponse
import com.conditiondrive.core.contract.VehicleRegisterResponse
import com.conditiondrive.core.time.Clock
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 실제 :server 프로세스를 상대로 폰 경로 전체를 돈다. :companion은 :server를 의존할 수 없어 서버를 띄운 뒤에만 돈다.
 * CD_COMPANION_SMOKE_URL=http://127.0.0.1:18080 ./gradlew :companion:testDebugUnitTest
 */
class LiveServerSmokeTest {
    private val base: String? = System.getenv("CD_COMPANION_SMOKE_URL")
    private val http = OkHttpClient()
    private val json = "application/json".toMediaType()

    private fun request(method: String, path: String, token: String? = null, body: String? = null): Pair<Int, String> {
        val request = Request.Builder().url(base + path).method(method, body?.toRequestBody(json)).apply {
            if (token != null) header("Authorization", "Bearer $token")
        }.build()
        return http.newCall(request).execute().use { it.code to it.body.string() }
    }

    private fun registerVehicle(): String {
        val (status, text) = request("POST", ApiPaths.DEVICES_VEHICLE, body = """{"app_version":"0.1.0"}""")
        assertEquals(201, status)
        return ContractJson.Lenient.decodeFromString(VehicleRegisterResponse.serializer(), text).vehicleToken
    }

    private fun newCodeFromQr(vehicleToken: String): String {
        val (status, text) = request("POST", ApiPaths.PAIRING_CODES, vehicleToken, "")
        assertEquals(201, status)
        // 차량이 QR로 보여 주는 문자열을 폰 파서로 읽는다. 두 앱의 형식이 어긋나면 여기서 걸린다
        return checkNotNull(PairingLink.parse(ContractJson.Lenient.decodeFromString(PairingCodeResponse.serializer(), text).qrPayload))
    }

    private fun latest(vehicleToken: String): LatestSummaryResponse {
        val (status, text) = request("GET", ApiPaths.SUMMARY_LATEST, vehicleToken)
        assertEquals(200, status)
        return ContractJson.Lenient.decodeFromString(LatestSummaryResponse.serializer(), text)
    }

    private inner class Phone(clock: Clock) {
        val store = MemoryStore(StoredState(consentVersion = ConsentPolicy.VERSION))
        val api = OkHttpCompanionApi(baseUrl = { checkNotNull(base) })
        val pairing = PairingService(api, store, vaultOf(store), clock, { "Smoke Phone" }, "0.1.0")
        var unauthorized = 0
        val uploads = UploadCoordinator(
            api, store, vaultOf(store), clock, { ZoneId.of("Asia/Seoul") },
            onUnauthorized = { unauthorized++ },
            onConsentRequired = {},
        )
    }

    private val realClock = Clock { System.currentTimeMillis() }

    @Test
    fun fullPhonePathAgainstRealServer() = runTest {
        assumeTrue("서버가 떠 있을 때만", base != null)
        val vehicle = registerVehicle()
        val phone = Phone(realClock)

        // 틀린 코드는 표의 결과로 돌아온다
        assertEquals(ClaimResult.CODE_INVALID, phone.pairing.claim("00000000"))

        assertEquals(ClaimResult.PAIRED, phone.pairing.claim(newCodeFromQr(vehicle)))
        assertEquals("Smoke Phone", latest(vehicle).phoneName)
        assertFalse(phone.pairing.isRevoked())

        // 수면과 혈당이 함께 차량 쪽 응답까지 간다
        val now = System.currentTimeMillis()
        phone.uploads.offer(SummaryInput(SleepValue(290, now), GlucoseReading(3.8, GlucoseTrendDto.FALLING, now)))
        phone.uploads.attempt()
        assertEquals(UploadStatus.Delivered, phone.uploads.status.value)
        val summary = assertNotNull(latest(vehicle).summary)
        assertEquals(3.8, summary.glucoseMmol)
        assertEquals(290, summary.sleepMinutesLastNight)
        assertEquals(true, summary.sleepShort)

        // null이 오면 null로 덮인다. 끊김이 즉시 전달되는 경로다(12.7)
        Thread.sleep(1100)
        phone.uploads.offer(SummaryInput(SleepValue(290, now), null))
        phone.uploads.attempt()
        val cut = assertNotNull(latest(vehicle).summary)
        assertNull(cut.glucoseMmol)
        assertEquals(290, cut.sleepMinutesLastNight)

        // 서버 요약 지우기
        assertEquals(RemoteDeletion.DONE, phone.pairing.deleteServerSummary())
        assertNull(latest(vehicle).summary)
        assertTrue(latest(vehicle).paired)

        // 연결 해제 뒤에는 토큰이 죽고 요약이 없다
        val oldToken = testCipher.open(checkNotNull(phone.store.value.tokenBlob))!!
        assertEquals(RemoteDeletion.DONE, phone.pairing.unpair())
        assertFalse(latest(vehicle).paired)
        assertEquals(ApiResult.Failed(401, "device_revoked").status, (phone.api.deleteSummary(oldToken) as ApiResult.Failed).status)
    }

    /** 폰 시계가 1시간 틀려도 clock_skew 응답으로 보정해 첫 전송에 성공한다 */
    @Test
    fun clockSkewIsCorrectedAgainstRealServer() = runTest {
        assumeTrue("서버가 떠 있을 때만", base != null)
        val vehicle = registerVehicle()
        val slowClock = Clock { System.currentTimeMillis() - 3_600_000 }
        val phone = Phone(slowClock)
        assertEquals(ClaimResult.PAIRED, phone.pairing.claim(newCodeFromQr(vehicle)))

        val now = slowClock.nowMs()
        phone.uploads.offer(SummaryInput(null, GlucoseReading(5.4, GlucoseTrendDto.FLAT, now)))
        phone.uploads.attempt()
        assertEquals(UploadStatus.Delivered, phone.uploads.status.value)
        assertTrue(phone.store.value.clockOffsetMs in 3_500_000..3_700_000)
        assertEquals(5.4, latest(vehicle).summary?.glucoseMmol)
    }

    /** 14장: 철회하면 요약이 지워지고, 재동의(12.10)가 서버에 닿는다 */
    @Test
    fun consentWithdrawAndRegrantAgainstRealServer() = runTest {
        assumeTrue("서버가 떠 있을 때만", base != null)
        val vehicle = registerVehicle()
        val phone = Phone(realClock)
        assertEquals(ClaimResult.PAIRED, phone.pairing.claim(newCodeFromQr(vehicle)))
        phone.uploads.offer(SummaryInput(null, GlucoseReading(5.4, null, System.currentTimeMillis())))
        phone.uploads.attempt()
        assertNotNull(latest(vehicle).summary)

        // 차량 토큰으로 철회 API를 부른 경우(U-31: 차량 화면에는 없는 API 능력). 폰의 다음 전송은 동의 없음으로 거절된다
        assertEquals(200, request("DELETE", "${ApiPaths.CONSENTS}/health_summary", vehicle).first)
        assertNull(latest(vehicle).summary)
        val token = testCipher.open(checkNotNull(phone.store.value.tokenBlob))!!
        assertEquals(ApiResult.Ok(Unit), phone.api.grantConsent(token, "health_summary", ConsentPolicy.VERSION))
        phone.uploads.offer(SummaryInput(null, GlucoseReading(5.4, null, System.currentTimeMillis())))
        phone.uploads.attempt()
        assertEquals(UploadStatus.Delivered, phone.uploads.status.value)

        assertEquals(RemoteDeletion.DONE, phone.pairing.withdraw())
        assertEquals(emptyList(), phone.store.value.pendingRevokes)
        assertFalse(latest(vehicle).paired)
        assertNull(phone.store.value.consentVersion)
    }
}
