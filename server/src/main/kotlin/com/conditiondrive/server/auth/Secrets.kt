package com.conditiondrive.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Tokens {
    private const val TOKEN_BYTES = 32
    const val VEHICLE_PREFIX = "cdv_"
    const val PHONE_PREFIX = "cdp_"
    private val random = SecureRandom()

    fun newVehicleToken(): String = VEHICLE_PREFIX + randomToken()
    fun newPhoneToken(): String = PHONE_PREFIX + randomToken()

    fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())

    fun newId(prefix: String): String = prefix + "_" + hex(randomBytes(8))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(TOKEN_BYTES))
}

object Codes {
    // I, L, O, U가 없어 입력 실수가 적다
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()
    private const val PAIRING_LENGTH = 8
    private const val CONSENT_BOUND = 1_000_000
    private const val OPT_OUT_LENGTH = 6
    private val PAIRING_FORMAT = Regex("[$CROCKFORD]{$PAIRING_LENGTH}")

    fun pairing(): String = String(CharArray(PAIRING_LENGTH) { CROCKFORD[random.nextInt(CROCKFORD.length)] })

    fun consent(): String = "%06d".format(random.nextInt(CONSENT_BOUND))

    /** 12.5: 대문자로 바꾸고 하이픈·공백을 지운 뒤 비교한다 */
    fun normalizePairing(raw: String): String = raw.uppercase().filterNot { it == '-' || it.isWhitespace() }

    fun isPairingShape(code: String): Boolean = PAIRING_FORMAT.matches(code)

    fun requestId(): String = "req_" + String(CharArray(PAIRING_LENGTH) { CROCKFORD[random.nextInt(CROCKFORD.length)] })

    /** HMAC을 코드로 바꾼다. 평문을 저장하지 않고도 모든 문자에 같은 거부 코드를 넣기 위해서다. */
    fun optOutFrom(digest: ByteArray): String =
        String(CharArray(OPT_OUT_LENGTH) { CROCKFORD[digest[it].toInt() and 0x1f] })
}

/** 경우의 수가 작은 값(코드, 전화번호)은 SHA-256만으로는 DB 유출 때 바로 역산되므로 서버 키로 HMAC한다. */
class Secrets(key: ByteArray) {
    private val keySpec = SecretKeySpec(key, ALGORITHM)

    fun hmac(purpose: String, value: String): ByteArray {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        mac.update(purpose.toByteArray())
        mac.update(0)
        return mac.doFinal(value.toByteArray())
    }

    fun pairingCodeHash(code: String) = hmac("pairing", code)
    fun consentCodeHash(guardianId: String, code: String) = hmac("consent", "$guardianId:$code")
    fun optOutCodeHash(code: String) = hmac("opt_out", code)
    fun phoneHmac(e164: String) = hmac("phone", e164)
    fun optOutCode(guardianId: String) = Codes.optOutFrom(hmac("opt_out_code", guardianId))

    private companion object {
        const val ALGORITHM = "HmacSHA256"
    }
}
