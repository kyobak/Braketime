package com.conditiondrive.server.service

import com.conditiondrive.core.time.Clock
import com.conditiondrive.server.Limits
import com.conditiondrive.server.routes.rateLimited

/** 기기별·전역 호출 제한. 재시작하면 비워진다. 재시작 후에도 유지해야 하는 문자 한도는 DB에 있다(12.14). */
class RateLimiter(private val clock: Clock) {
    private val hits = HashMap<String, Window>()

    private class Window(val windowMs: Long) { val times = ArrayDeque<Long>() }

    /** 초과면 예외. 슬라이딩 윈도. 거절된 호출은 세지 않아 계속 두드려도 복구가 늦어지지 않는다. */
    @Synchronized
    fun acquire(key: String, limit: Int, windowMs: Long) {
        val now = clock.nowMs()
        val window = hits.getOrPut(key) { Window(windowMs) }
        while (window.times.isNotEmpty() && window.times.first() <= now - windowMs) window.times.removeFirst()
        if (window.times.size >= limit) throw rateLimited(window.times.first() + windowMs - now)
        window.times.addLast(now)
    }

    /** 기기가 사라져도 키가 남으면 메모리가 계속 는다. */
    @Synchronized
    fun prune() {
        val now = clock.nowMs()
        hits.entries.removeIf { (_, w) ->
            w.times.removeAll { it <= now - w.windowMs }
            w.times.isEmpty()
        }
    }
}

/** 12.5: 코드 추측을 막는 전역 실패 제한. 성공은 세지 않아 정상 사용자는 잠금 전까지 영향이 없다. */
class ClaimGuard(private val clock: Clock) {
    private val failures = ArrayDeque<Long>()
    private var lockedUntil = 0L

    @Synchronized
    fun checkNotLocked() {
        val now = clock.nowMs()
        if (now < lockedUntil) throw rateLimited(lockedUntil - now)
    }

    /** 한도를 넘긴 실패는 잠그고 429로 바뀐다. */
    @Synchronized
    fun recordFailure() {
        val now = clock.nowMs()
        while (failures.isNotEmpty() && failures.first() <= now - Limits.CLAIM_FAILURE_WINDOW_MS) failures.removeFirst()
        failures.addLast(now)
        if (failures.size > Limits.CLAIM_FAILURES) {
            lockedUntil = now + Limits.CLAIM_LOCK_MS
            throw rateLimited(Limits.CLAIM_LOCK_MS)
        }
    }
}
