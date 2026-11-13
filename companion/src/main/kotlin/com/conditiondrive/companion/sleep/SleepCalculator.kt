package com.conditiondrive.companion.sleep

import com.conditiondrive.companion.upload.SleepValue

data class SleepSpan(val startMs: Long, val endMs: Long)

/** F6 수면 계산. 여기서 세션 목록이 분 하나로 줄어든다. 목록은 저장하지도 내보내지도 않는다 */
object SleepCalculator {
    const val WINDOW_MS = 24 * 60 * 60 * 1000L
    private const val MINUTE_MS = 60_000L

    /**
     * 지난 24시간 수면 세션 합계. 세션이 없으면 null이다. 시계를 안 차고 잔 밤을 수면 부족으로 만들지 않는다.
     * 시계와 폰이 같은 밤을 각각 기록하면 겹치므로 더하지 않고 합집합을 잰다. 안 그러면 수면이 2배로 나간다.
     */
    fun minutesLast24h(spans: List<SleepSpan>, nowMs: Long): Int? {
        val windowStart = nowMs - WINDOW_MS
        val clipped = spans
            .map { SleepSpan(maxOf(it.startMs, windowStart), minOf(it.endMs, nowMs)) }
            .filter { it.endMs > it.startMs }
            .sortedBy { it.startMs }
        if (clipped.isEmpty()) return null
        var total = 0L
        var coveredUntil = Long.MIN_VALUE
        for (span in clipped) {
            val from = maxOf(span.startMs, coveredUntil)
            if (span.endMs > from) {
                total += span.endMs - from
                coveredUntil = span.endMs
            }
        }
        return (total / MINUTE_MS).toInt()
    }

    fun valueFrom(spans: List<SleepSpan>, nowMs: Long): SleepValue? =
        minutesLast24h(spans, nowMs)?.let { SleepValue(it, nowMs) }
}
