package com.conditiondrive.companion.storage

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 11.3 토큰 보관. androidx.security.crypto는 deprecated라 직접 감싼다 */
interface TokenCipher {
    fun seal(plain: String): String
    fun open(blob: String): String?
}

/** 키 출처만 바꿔 JVM 테스트로 검증하려고 형식과 키 보관을 나눴다. 블롭은 base64url(IV 12바이트 + 암호문) */
class AesGcmTokenCipher(private val keyProvider: () -> SecretKey) : TokenCipher {
    override fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // IV는 Keystore가 직접 만들게 둔다. 호출자가 지정하면 randomized 키에서 거절된다
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.allocate(cipher.iv.size + body.size).put(cipher.iv).put(body).array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // 복호화 실패는 미연결로 처리한다. 키가 무효가 되면 다시 연결하는 수밖에 없다
    override fun open(blob: String): String? = try {
        val bytes = Base64.getUrlDecoder().decode(blob)
        if (bytes.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: ProviderException) {
        null
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
