package com.conditiondrive.companion.upload

import java.net.URI
import java.net.URISyntaxException

/** 8.3 서버 주소 규칙. QR에 주소를 넣지 않는 것과 같은 이유로, 건강 요약이 갈 곳을 좁혀 둔다 */
object ServerAddress {
    private const val EMULATOR_HOST = "10.0.2.2"
    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 8080

    /** 에뮬레이터는 호스트 루프백으로, 실폰은 adb reverse로 같은 포트에 닿는다(12.1) */
    fun default(isEmulator: Boolean): String = "http://${if (isEmulator) EMULATOR_HOST else LOOPBACK_HOST}:$DEFAULT_PORT"

    /** 허용되면 끝 슬래시 없는 주소를, 아니면 null을 돌려준다 */
    fun normalize(text: String): String? {
        val uri = try {
            URI(text.trim().trimEnd('/'))
        } catch (_: URISyntaxException) {
            return null
        }
        val host = uri.host ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null || !uri.path.isNullOrEmpty()) return null
        val allowed = when (uri.scheme) {
            "https" -> true
            "http" -> (host == EMULATOR_HOST || host == LOOPBACK_HOST) && uri.port != -1
            else -> false
        }
        return if (allowed) uri.toString() else null
    }
}
