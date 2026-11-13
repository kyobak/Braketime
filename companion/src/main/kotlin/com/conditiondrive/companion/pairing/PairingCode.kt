package com.conditiondrive.companion.pairing

import java.net.URI
import java.net.URISyntaxException

/** 12.4의 8자 코드. Crockford Base32라 I, L, O, U가 코드에 없다 */
object PairingCode {
    const val LENGTH = 8
    private val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toSet()

    /** 대문자로 바꾸고 하이픈과 공백을 지운다. 차량 화면을 보고 옮겨 적다 헷갈리는 글자는 Crockford 규칙으로 바로잡는다 */
    fun normalize(text: String): String = text.uppercase()
        .filterNot { it == '-' || it.isWhitespace() }
        .map { char ->
            when (char) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> char
            }
        }
        .joinToString("")

    fun isValid(normalized: String): Boolean = normalized.length == LENGTH && normalized.all { it in ALPHABET }
}

/**
 * conditiondrive://pair?v=1&code=K7M2QX9T 만 받는다. 다른 파라미터는 읽지 않는다.
 * QR로 서버 주소를 바꿀 수 있으면 악성 QR이 건강 요약을 다른 서버로 보내게 만들 수 있다(12.4).
 */
object PairingLink {
    private const val SCHEME = "conditiondrive"
    private const val HOST = "pair"
    private const val VERSION = "1"

    fun parse(text: String): String? {
        val uri = try {
            URI(text.trim())
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        val params = uri.rawQuery.orEmpty().split('&').mapNotNull { pair ->
            val parts = pair.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }
        // 같은 키가 둘이면 어느 코드로 연결될지 사용자가 알 수 없다
        val versions = params.filter { it.first == "v" }
        val codes = params.filter { it.first == "code" }
        if (versions.size != 1 || versions[0].second != VERSION || codes.size != 1) return null
        return PairingCode.normalize(codes[0].second).takeIf(PairingCode::isValid)
    }
}
