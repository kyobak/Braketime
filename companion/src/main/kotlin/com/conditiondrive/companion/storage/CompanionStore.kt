package com.conditiondrive.companion.storage

import com.conditiondrive.companion.upload.SleepValue
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** 해제가 네트워크 때문에 끝나지 못했을 때 서버 쪽 삭제를 마저 하려고 남기는 항목 */
@Serializable
data class PendingRevoke(val tokenBlob: String, val withdrawConsent: Boolean)

/**
 * 폰에 남기는 전부다. 수면 원본과 심박은 여기 없다. 토큰은 암호문만 둔다.
 * 혈당도 없다: 프로세스가 죽으면 시뮬레이터가 멈추고, 차량이 끊김으로 알아야 하기 때문이다.
 */
@Serializable
data class StoredState(
    val consentVersion: String? = null,
    val consentGrantedAtMs: Long? = null,
    val tokenBlob: String? = null,
    val deviceId: String? = null,
    val clockOffsetMs: Long = 0,
    val serverUrl: String? = null,
    val uploadPaused: Boolean = false,
    val lastUploadAtMs: Long? = null,
    val vehicleLastSeenAtMs: Long? = null,
    val sleep: SleepValue? = null,
    val pendingRevokes: List<PendingRevoke> = emptyList(),
)

interface CompanionStore {
    val state: Flow<StoredState>
    suspend fun read(): StoredState
    suspend fun update(transform: (StoredState) -> StoredState)
}
