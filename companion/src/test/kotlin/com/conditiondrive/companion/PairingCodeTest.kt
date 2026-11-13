package com.conditiondrive.companion

import com.conditiondrive.companion.pairing.PairingCode
import com.conditiondrive.companion.pairing.PairingLink
import com.conditiondrive.companion.pairing.QrDecoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingCodeTest {
    @Test
    fun normalizesAsTheServerDoes() {
        assertEquals("K7M2QX9T", PairingCode.normalize("k7m2-qx9t"))
        assertEquals("K7M2QX9T", PairingCode.normalize(" K7M2 QX9T "))
    }

    @Test
    fun confusingLettersFollowCrockford() {
        assertEquals("0111AAAA", PairingCode.normalize("OIL1aaaa"))
    }

    @Test
    fun validity() {
        assertTrue(PairingCode.isValid("K7M2QX9T"))
        assertFalse(PairingCode.isValid("K7M2QX9"))
        assertFalse(PairingCode.isValid("K7M2QX9TT"))
        assertFalse(PairingCode.isValid("K7M2QX9U"))  // U는 Crockford에 없다
        assertFalse(PairingCode.isValid("K7M2QX9!"))
        assertFalse(PairingCode.isValid(""))
    }

    @Test
    fun parsesServerQrPayload() {
        assertEquals("K7M2QX9T", PairingLink.parse("conditiondrive://pair?v=1&code=K7M2QX9T"))
        assertEquals("K7M2QX9T", PairingLink.parse("conditiondrive://pair?code=k7m2qx9t&v=1"))
    }

    @Test
    fun rejectsForeignLinks() {
        listOf(
            "https://evil.example/pair?v=1&code=K7M2QX9T",
            "conditiondrive://other?v=1&code=K7M2QX9T",
            "conditiondrive://pair?v=2&code=K7M2QX9T",
            "conditiondrive://pair?code=K7M2QX9T",
            "conditiondrive://pair?v=1",
            "conditiondrive://pair?v=1&code=SHORT",
            "conditiondrive://pair?v=1&code=K7M2QX9T&code=AAAAAAAA",
            "not a url at all",
            "",
        ).forEach { assertNull(PairingLink.parse(it), it) }
    }

    /** 악성 QR이 건강 요약을 다른 서버로 보내지 못한다. 코드 말고 다른 값이 결과에 담길 자리도 없다 */
    @Test
    fun extraParametersCannotInjectAnything() {
        assertEquals("K7M2QX9T", PairingLink.parse("conditiondrive://pair?v=1&code=K7M2QX9T&server=https://evil.example"))
    }

    private fun qrLuma(text: String, size: Int, rowStride: Int): ByteArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val luma = ByteArray(rowStride * size) { 0x7F }
        for (y in 0 until size) {
            for (x in 0 until size) luma[y * rowStride + x] = if (matrix[x, y]) 0 else 0xFF.toByte()
        }
        return luma
    }

    @Test
    fun decodesRealQrFromLumaPlane() {
        val payload = "conditiondrive://pair?v=1&code=K7M2QX9T"
        val decoder = QrDecoder()
        assertEquals(payload, decoder.decode(qrLuma(payload, 400, 400), 400, 400, 400))
        // 카메라 행 패딩이 있어도 읽힌다. 재사용해도 앞 프레임 상태가 남지 않는다
        assertEquals(payload, decoder.decode(qrLuma(payload, 400, 448), 400, 400, 448))
        assertEquals("K7M2QX9T", PairingLink.parse(checkNotNull(decoder.decode(qrLuma(payload, 300, 320), 300, 300, 320))))
    }

    @Test
    fun blankFrameIsNotAnError() {
        assertNull(QrDecoder().decode(ByteArray(400 * 400) { 0x40 }, 400, 400, 400))
    }
}
