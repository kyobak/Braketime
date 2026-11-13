package com.conditiondrive.core.time

fun interface Clock { fun nowMs(): Long }

class ManualClock(private var now: Long) : Clock {
    override fun nowMs(): Long = now
    fun set(ms: Long) { now = ms }
    fun advance(ms: Long) { now += ms }
}
