package com.conditiondrive.vehicle.summary

import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.LatestSummaryResponse
import com.conditiondrive.core.contract.Mappers
import com.conditiondrive.core.contract.SummaryValidator
import com.conditiondrive.vehicle.port.SummarySnapshot
import java.time.OffsetDateTime

/** 실제 HTTP와 시연 소스가 같은 파서·검증기를 지나게 하려고 한곳에 둔다(15.1). */
internal object SummaryDecoder {
    sealed interface Decoded {
        data class Snapshot(val snapshot: SummarySnapshot) : Decoded
        data object Unpaired : Decoded
        data object Invalid : Decoded
    }

    // 차량은 서버가 이미 거른 값을 받는다. 제휴 허용 여부는 서버 설정(12.7 표 12번)이라 여기서 다시 판단하지 않는다
    private const val ALLOW_PARTNER = true

    fun decode(body: String, engineNowMs: Long): Decoded {
        val response = try {
            ContractJson.Lenient.decodeFromString(LatestSummaryResponse.serializer(), body)
        } catch (_: Exception) {
            return Decoded.Invalid
        }
        if (!response.paired) return Decoded.Unpaired
        val serverMs = parseMs(response.serverTime) ?: return Decoded.Invalid
        val dto = response.summary
        if (dto != null && SummaryValidator.validateReceived(dto, serverMs, ALLOW_PARTNER).isNotEmpty()) return Decoded.Invalid
        // 10.1 규칙 5. 기기 벽시계와 무관하도록 서버 시각과의 차이만 엔진 시계로 옮긴다
        val input = dto?.let { Mappers.toEngineInput(it, serverMs, engineNowMs) }
        val received = response.receivedAt?.let(::parseMs)?.let { engineNowMs + (it - serverMs) }
        return Decoded.Snapshot(SummarySnapshot(input, unpaired = false, receivedAtMs = received, phoneName = response.phoneName))
    }

    private fun parseMs(text: String): Long? = try {
        OffsetDateTime.parse(text).toInstant().toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
