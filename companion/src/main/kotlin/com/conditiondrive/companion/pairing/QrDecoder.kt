package com.conditiondrive.companion.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** 카메라 Y 평면에서 QR 내용을 읽는다. 카메라와 분리해 JVM에서 실제 QR로 검증한다 */
class QrDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    /** rowStride는 카메라가 행 끝에 패딩을 붙이는 경우의 실제 행 길이다 */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int): String? {
        val source = PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: NotFoundException) {
            null // 대부분의 프레임에는 QR이 없다. 정상이다
        } finally {
            reader.reset()
        }
    }
}
