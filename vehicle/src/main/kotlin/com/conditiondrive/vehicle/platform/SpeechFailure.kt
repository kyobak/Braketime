package com.conditiondrive.vehicle.platform

/** 10.2 실패 판정. 새 오류 값이 와도 죽지 않게 이름으로 받고 else로 대체 경로에 보낸다 */
internal enum class SpeechFailure { FATAL, RETRY_ONCE }

internal fun classifySpeechError(errorName: String): SpeechFailure = when (errorName) {
    "NETWORK_ERROR", "TIMEOUT" -> SpeechFailure.RETRY_ONCE
    // NOT_SUPPORTED_API, ON_DEVICE_ENGINE_ERROR, PERMISSION_DENIED, UNAVAILABLE_LANGUAGE와 처음 보는 값
    else -> SpeechFailure.FATAL
}
