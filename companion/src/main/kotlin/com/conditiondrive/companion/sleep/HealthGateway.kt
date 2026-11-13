package com.conditiondrive.companion.sleep

enum class HealthAvailability { AVAILABLE, NEEDS_PROVIDER_UPDATE, UNAVAILABLE }

data class HeartSample(val bpm: Long, val atMs: Long)

/** 일시적 오류만 표현한다. 권한 없음은 hasSleepPermission으로 먼저 거른다 */
class HealthReadException : Exception()

/**
 * 수면과 표시용 심박만 있다. 혈당을 읽는 메서드를 두지 않는다.
 * 심박은 화면 표시 전용이다. 저장·판단·요약 어디에도 쓰지 않는다(D-07).
 */
interface HealthGateway {
    fun availability(): HealthAvailability
    suspend fun hasSleepPermission(): Boolean
    suspend fun readSleep(fromMs: Long, toMs: Long): List<SleepSpan>
    suspend fun latestHeartRate(fromMs: Long, toMs: Long): HeartSample?
}
