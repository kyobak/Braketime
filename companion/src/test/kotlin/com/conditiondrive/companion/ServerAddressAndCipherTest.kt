package com.conditiondrive.companion

import com.conditiondrive.companion.storage.AesGcmTokenCipher
import com.conditiondrive.companion.upload.ServerAddress
import java.util.Base64
import javax.crypto.KeyGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAddressAndCipherTest {
    @Test
    fun defaultsReachHostLoopback() {
        assertEquals("http://10.0.2.2:8080", ServerAddress.default(isEmulator = true))
        assertEquals("http://127.0.0.1:8080", ServerAddress.default(isEmulator = false))
    }

    /** 8.3 허용 목록 */
    @Test
    fun allowedAddresses() {
        assertEquals("http://10.0.2.2:8080", ServerAddress.normalize("http://10.0.2.2:8080/"))
        assertEquals("http://127.0.0.1:9000", ServerAddress.normalize(" http://127.0.0.1:9000 "))
        assertEquals("https://api.example.com", ServerAddress.normalize("https://api.example.com"))
    }

    @Test
    fun rejectedAddresses() {
        listOf(
            "http://example.com:8080",
            "http://192.168.0.2:8080",
            "http://10.0.2.2",
            "ftp://10.0.2.2:8080",
            "https://user:pw@example.com",
            "https://example.com/path",
            "https://example.com?x=1",
            "example.com",
            "",
        ).forEach { assertNull(ServerAddress.normalize(it), it) }
    }

    private val cipher = testCipher

    @Test
    fun cipherRoundTrip() {
        val blob = cipher.seal("cdp_secret")
        assertFalse("cdp_secret" in blob)
        assertEquals("cdp_secret", cipher.open(blob))
    }

    @Test
    fun ivIsFreshEveryTime() {
        assertNotEquals(cipher.seal("cdp_secret"), cipher.seal("cdp_secret"))
    }

    @Test
    fun tamperedOrForeignBlobIsNull() {
        val bytes = Base64.getUrlDecoder().decode(cipher.seal("cdp_secret"))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        assertNull(cipher.open(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)))
        assertNull(cipher.open("not base64 !!"))
        assertNull(cipher.open(""))
        assertNull(cipher.open("AAAA"))

        val other = AesGcmTokenCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().let { key -> { key } })
        assertNull(other.open(cipher.seal("cdp_secret")))
    }

    @Test
    fun brokenKeystoreIsNotACrash() {
        val broken = AesGcmTokenCipher { throw java.security.ProviderException("keystore") }
        assertTrue(broken.open(cipher.seal("cdp_secret")) == null)
    }
}
