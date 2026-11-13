package com.conditiondrive.vehicle.summary

import com.conditiondrive.core.contract.ContractJson
import com.conditiondrive.core.contract.GlucoseSourceDto
import com.conditiondrive.core.contract.GlucoseTrendDto
import com.conditiondrive.core.contract.LatestSummaryResponse
import com.conditiondrive.core.contract.SleepPolicy
import com.conditiondrive.core.contract.SummaryDto
import com.conditiondrive.core.engine.Trend
import com.conditiondrive.core.scenario.ScenarioSummary
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.port.SummarySnapshot
import com.conditiondrive.vehicle.port.SummarySource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 시연용 요약. 시뮬레이션 시각으로 12.8 응답 본문을 만들어 실제 소스와 같은 파서·검증기로 읽는다(15.1, 10.1 규칙 5).
 * 시나리오의 summary는 빠진 항목을 직전 값으로 채우는 뜻이라(9.4) 그 합치기만 여기서 한다.
 */
class ScriptedSummarySource : SummarySource {
    private class Glucose(val mmol: Double, val trend: Trend?, val source: GlucoseSourceDto, val measuredMs: Long, val ttlMs: Long)
    private class Sleep(val minutes: Int, val untilMs: Long)

    private var glucose: Glucose? = null
    private var sleep: Sleep? = null

    private val _snapshot = MutableStateFlow(SummarySnapshot(null, unpaired = false, receivedAtMs = null, phoneName = null))
    override val snapshot: StateFlow<SummarySnapshot> = _snapshot
    private val _link = MutableStateFlow(LinkState.UNPAIRED)
    override val link: StateFlow<LinkState> = _link

    override fun start() = Unit
    override fun stop() = Unit
    override suspend fun fetchNow(timeoutMs: Long) = Unit

    /** measuredAtMs는 이 줄의 측정 시각이다. 없으면 이 줄의 시각이다 */
    fun push(summary: ScenarioSummary, atMs: Long, measuredAtMs: Long?) {
        summary.glucoseMmol?.let { mmol ->
            val source = sourceOf(summary.source)
            glucose = source?.let {
                Glucose(mmol, summary.trend, it, measuredAtMs ?: atMs, (summary.ttlSec ?: DEFAULT_TTL_SEC) * 1000)
            }
        }
        summary.sleepMinutes?.let { sleep = Sleep(it, atMs + (summary.sleepTtlSec ?: DEFAULT_SLEEP_TTL_SEC) * 1000) }
        publish(atMs)
    }

    /** keepGlucoseAlive(9.4). 같은 값을 새 측정 시각으로 다시 받는다 */
    fun refreshGlucose(atMs: Long) {
        val last = glucose ?: return
        glucose = Glucose(last.mmol, last.trend, last.source, atMs, last.ttlMs)
        publish(atMs)
    }

    fun pushNull(atMs: Long) {
        glucose = null
        sleep = null
        _link.value = LinkState.FRESH
        _snapshot.value = SummarySnapshot(null, unpaired = false, receivedAtMs = atMs, phoneName = null)
    }

    fun pushUnpaired() {
        glucose = null
        sleep = null
        _link.value = LinkState.UNPAIRED
        _snapshot.value = SummarySnapshot(null, unpaired = true, receivedAtMs = null, phoneName = null)
    }

    val hasGlucose: Boolean get() = glucose != null

    private fun publish(atMs: Long) {
        val body = ContractJson.Lenient.encodeToString(LatestSummaryResponse.serializer(), responseAt(atMs))
        val decoded = SummaryDecoder.decode(body, atMs)
        if (decoded is SummaryDecoder.Decoded.Snapshot) {
            _link.value = LinkState.FRESH
            _snapshot.value = decoded.snapshot
        }
    }

    private fun responseAt(atMs: Long): LatestSummaryResponse {
        val g = glucose?.takeIf { it.measuredMs + it.ttlMs > atMs }
        val s = sleep?.takeIf { it.untilMs > atMs }
        val validUntil = g?.let { it.measuredMs + it.ttlMs } ?: (atMs + DEFAULT_TTL_SEC * 1000)
        val dto = SummaryDto(
            generatedAt = iso(atMs),
            validUntil = iso(validUntil),
            sleepMinutesLastNight = s?.minutes,
            sleepShort = s?.let { SleepPolicy.isShort(it.minutes) },
            sleepValidUntil = s?.let { iso(it.untilMs) },
            glucoseMmol = g?.mmol,
            glucoseTrend = g?.trend?.toDto(),
            glucoseSource = g?.source,
            glucoseMeasuredAt = g?.let { iso(it.measuredMs) },
        )
        return LatestSummaryResponse(paired = true, serverTime = iso(atMs), receivedAt = iso(atMs), summary = dto)
    }

    private fun sourceOf(name: String?): GlucoseSourceDto? = when (name) {
        null, "simulated" -> GlucoseSourceDto.SIMULATED
        "partner_realtime" -> GlucoseSourceDto.PARTNER_REALTIME
        else -> null    // 허용 안 되는 source는 혈당 없음이다(F7-9)
    }

    private fun Trend.toDto(): GlucoseTrendDto = when (this) {
        Trend.FALLING_FAST -> GlucoseTrendDto.FALLING_FAST
        Trend.FALLING -> GlucoseTrendDto.FALLING
        Trend.FLAT -> GlucoseTrendDto.FLAT
        Trend.RISING -> GlucoseTrendDto.RISING
    }

    private fun iso(ms: Long): String = ISO.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    private companion object {
        const val DEFAULT_TTL_SEC = 900L
        const val DEFAULT_SLEEP_TTL_SEC = 86_400L

        // OffsetDateTime.toString은 초가 0이면 생략해 RFC 3339 검사에 걸린다
        val ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX")
    }
}
