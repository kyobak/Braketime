package com.conditiondrive.companion.pairing

import com.conditiondrive.companion.storage.CompanionStore
import com.conditiondrive.companion.storage.PendingRevoke
import com.conditiondrive.companion.storage.TokenVault
import com.conditiondrive.companion.upload.ApiResult
import com.conditiondrive.companion.upload.CompanionApi
import com.conditiondrive.core.contract.ConsentItem
import com.conditiondrive.core.contract.PairingClaimRequest
import com.conditiondrive.core.time.Clock

object ConsentPolicy {
    const val TYPE_HEALTH_SUMMARY = "health_summary"
    const val VERSION = "2026-11-v1"
}

enum class ClaimResult { PAIRED, CODE_FORMAT, CODE_INVALID, CODE_EXPIRED, CONSENT_MISSING, RATE_LIMITED, NOT_REACHED, FAILED }

/** 서버 삭제까지 끝났는지. 로컬 삭제는 어느 쪽이든 이미 끝난 뒤다 */
enum class RemoteDeletion { DONE, PENDING }

class PairingService(
    private val api: CompanionApi,
    private val store: CompanionStore,
    private val tokens: TokenVault,
    private val clock: Clock,
    private val deviceName: () -> String,
    private val appVersion: String,
) {
    /** 동의를 기록한 뒤에만 호출이 나간다. 코드 형식이 틀려도 호출하지 않아 서버 실패 제한을 쓰지 않는다 */
    suspend fun claim(rawCode: String): ClaimResult {
        val code = PairingCode.normalize(rawCode)
        if (!PairingCode.isValid(code)) return ClaimResult.CODE_FORMAT
        val state = store.read()
        val consentVersion = state.consentVersion ?: return ClaimResult.CONSENT_MISSING
        val request = PairingClaimRequest(
            code = code,
            deviceName = sanitizedDeviceName(deviceName()),
            appVersion = appVersion,
            consents = listOf(ConsentItem(ConsentPolicy.TYPE_HEALTH_SUMMARY, consentVersion)),
        )
        return when (val result = api.claim(request)) {
            is ApiResult.Ok -> {
                val sealed = tokens.seal(result.value.phoneToken)
                store.update { current ->
                    // 기존 연결은 새 코드가 성공한 뒤에야 끊는다. 다른 계정이면 서버가 옛 토큰을 모르므로 직접 폐기한다
                    val old = current.tokenBlob?.let { PendingRevoke(it, withdrawConsent = false) }
                    current.copy(
                        tokenBlob = sealed,
                        deviceId = result.value.deviceId,
                        clockOffsetMs = 0,
                        uploadPaused = false,
                        lastUploadAtMs = null,
                        vehicleLastSeenAtMs = null,
                        pendingRevokes = current.pendingRevokes + listOfNotNull(old),
                    )
                }
                ClaimResult.PAIRED
            }
            ApiResult.NetworkError -> ClaimResult.NOT_REACHED
            is ApiResult.Failed -> when {
                result.status == 404 -> ClaimResult.CODE_INVALID
                result.status == 410 -> ClaimResult.CODE_EXPIRED
                result.status == 403 -> ClaimResult.CONSENT_MISSING
                result.status == 429 -> ClaimResult.RATE_LIMITED
                result.status >= 500 -> ClaimResult.NOT_REACHED
                else -> ClaimResult.FAILED
            }
        }
    }

    /** 연결 해제. 토큰과 캐시를 먼저 지워 전송이 바로 멈추게 하고, 서버 삭제는 못 끝나면 나중에 마저 한다 */
    suspend fun unpair(): RemoteDeletion = forget(withdrawConsent = false)

    /** 동의 철회(14장). 철회 즉시 요약이 지워져야 하므로 서버 호출을 기다리지 않고 폰부터 비운다 */
    suspend fun withdraw(): RemoteDeletion = forget(withdrawConsent = true)

    private suspend fun forget(withdrawConsent: Boolean): RemoteDeletion {
        var queued = false
        store.update { current ->
            val pending = current.tokenBlob?.let { PendingRevoke(it, withdrawConsent) }
            queued = pending != null
            current.copy(
                consentVersion = current.consentVersion.takeUnless { withdrawConsent },
                consentGrantedAtMs = current.consentGrantedAtMs.takeUnless { withdrawConsent },
                tokenBlob = null,
                deviceId = null,
                uploadPaused = false,
                lastUploadAtMs = null,
                vehicleLastSeenAtMs = null,
                sleep = null,
                pendingRevokes = current.pendingRevokes + listOfNotNull(pending),
            )
        }
        if (!queued) return RemoteDeletion.DONE
        return if (drainRevokes()) RemoteDeletion.DONE else RemoteDeletion.PENDING
    }

    /** 다 끝났으면 true. 실패한 항목은 남겨서 다음 앱 실행이나 워커가 다시 한다 */
    suspend fun drainRevokes(): Boolean {
        val pending = store.read().pendingRevokes
        for (item in pending) {
            // 복호화가 안 되면 서버에 증명할 수단이 없다. 요약은 자기 만료로 사라지므로 항목만 버린다
            val done = tokens.open(item.tokenBlob)?.let { revoke(it, item.withdrawConsent) } ?: true
            if (done) store.update { it.copy(pendingRevokes = it.pendingRevokes - item) }
        }
        return store.read().pendingRevokes.isEmpty()
    }

    private suspend fun revoke(token: String, withdrawConsent: Boolean): Boolean {
        // 동의 API가 없는 서버(P1 미배포)면 404다. 페어링 삭제가 요약을 지우므로 계속 간다
        if (withdrawConsent && !isFinal(api.withdrawConsent(token, ConsentPolicy.TYPE_HEALTH_SUMMARY))) return false
        return isFinal(api.unpair(token))
    }

    // 401은 이미 폐기된 토큰이라 원하는 상태와 같다. 그 외 4xx는 다시 해도 결과가 같으니 붙들지 않는다
    private fun isFinal(result: ApiResult<Unit>): Boolean = when (result) {
        is ApiResult.Ok -> true
        ApiResult.NetworkError -> false
        is ApiResult.Failed -> result.status in 400..499 && result.status != 429
    }

    /** 요약만 지운다. 연결은 그대로라 곧바로 다시 쌓이지 않게 호출하는 쪽이 전송을 먼저 멈춘다 */
    suspend fun deleteServerSummary(): RemoteDeletion {
        val token = tokens.current() ?: return RemoteDeletion.DONE
        return when (val result = api.deleteSummary(token)) {
            is ApiResult.Ok -> RemoteDeletion.DONE
            ApiResult.NetworkError -> RemoteDeletion.PENDING
            is ApiResult.Failed -> if (result.status == 401) RemoteDeletion.DONE else RemoteDeletion.PENDING
        }
    }

    /** 다른 기기가 연결되어 이 토큰이 폐기됐는지 앱을 열 때 확인한다 */
    suspend fun isRevoked(): Boolean {
        val token = tokens.current() ?: return false
        val result = api.pairingStatus(token)
        return result is ApiResult.Failed && result.status == 401
    }

    suspend fun recordConsent() {
        val now = clock.nowMs()
        store.update { it.copy(consentVersion = ConsentPolicy.VERSION, consentGrantedAtMs = now) }
        // 폰 밖에서 철회되어 돌아온 경우다(차량 토큰 API, U-31). 연결은 살아 있고 서버 동의만 비어 있다
        val token = tokens.current() ?: return
        val result = api.grantConsent(token, ConsentPolicy.TYPE_HEALTH_SUMMARY, ConsentPolicy.VERSION)
        // 서버가 받지 않으면 다음 전송이 또 consent_required로 돌아온다. 다시 연결하면 claim이 동의를 함께 기록한다
        if (result is ApiResult.Failed) {
            store.update { it.copy(tokenBlob = null, deviceId = null, pendingRevokes = it.pendingRevokes + listOfNotNull(it.tokenBlob?.let { blob -> PendingRevoke(blob, false) })) }
        }
    }

    companion object {
        private const val MAX_DEVICE_NAME = 40
        const val FALLBACK_DEVICE_NAME = "내 휴대폰"

        /** 12.5: 1~40자. 차량 S2에 그대로 보이는 값이라 제어 문자도 뺀다 */
        fun sanitizedDeviceName(raw: String): String {
            val name = raw.filterNot(Char::isISOControl).trim().take(MAX_DEVICE_NAME).trim()
            return name.ifEmpty { FALLBACK_DEVICE_NAME }
        }
    }
}
