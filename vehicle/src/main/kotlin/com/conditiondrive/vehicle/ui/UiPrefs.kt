package com.conditiondrive.vehicle.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.conditiondrive.vehicle.ui.screens.OnboardingStep

/**
 * 화면 편의 상태만 둔다. 엔진 상태와 건강 값은 여기 넣지 않는다.
 * DataStore를 쓰지 않는 까닭은 onCreate에서 동기로 읽어야 첫 프레임부터 온보딩 여부가 맞기 때문이다.
 */
class UiPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("cd_ui", Context.MODE_PRIVATE)

    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_DONE, false)
        set(value) = prefs.edit { putBoolean(KEY_DONE, value) }

    /** 온보딩이 끝나면 런타임이 미뤄 둔 V01을 묻는다. 돌려준 값을 쥐고 있어야 리스너가 살아 있다 */
    fun watchOnboarding(onChange: (Boolean) -> Unit): SharedPreferences.OnSharedPreferenceChangeListener {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_DONE) onChange(onboardingDone)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return listener
    }

    fun stopWatching(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    var onboardingStep: OnboardingStep
        get() = OnboardingStep.entries.firstOrNull { it.name == prefs.getString(KEY_STEP, null) } ?: OnboardingStep.INTRO
        set(value) = prefs.edit { putString(KEY_STEP, value.name) }

    var serverDraft: String
        get() = prefs.getString(KEY_DRAFT, "") ?: ""
        set(value) = prefs.edit { putString(KEY_DRAFT, value) }

    private companion object {
        const val KEY_DONE = "onboarding_done"
        const val KEY_STEP = "onboarding_step"
        const val KEY_DRAFT = "server_draft"
    }
}
