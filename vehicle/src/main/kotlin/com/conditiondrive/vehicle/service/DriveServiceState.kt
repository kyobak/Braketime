package com.conditiondrive.vehicle.service

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

/** S4 "측정 안 된 구간 있음"(10.3)의 재료. 그날 안에서만 의미가 있다 */
internal object DriveServiceState {
    private const val PREFS = "cd_service"
    private const val KEY_GAP_AT = "gap_at_ms"
    private val zone = ZoneId.of("Asia/Seoul")
    private val _gap = MutableStateFlow(false)
    val gap: StateFlow<Boolean> = _gap.asStateFlow()

    fun markGap(context: Context, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(KEY_GAP_AT, nowMs) }
        _gap.value = true
    }

    fun load(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_GAP_AT, -1)
        _gap.value = at >= 0 && sameDay(at, nowMs)
    }

    internal fun sameDay(a: Long, b: Long): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
