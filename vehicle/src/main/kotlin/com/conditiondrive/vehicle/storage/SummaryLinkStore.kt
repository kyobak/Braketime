package com.conditiondrive.vehicle.storage

import android.content.Context
import androidx.core.content.edit
import com.conditiondrive.vehicle.summary.SummaryCredentials
import com.conditiondrive.vehicle.summary.SummaryLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 차량 토큰과 서버 주소. 평문 저장은 공모전 범위의 한계다(18.3 알려진 한계에 적는다).
 * 토큰이 있어도 폰 연결이 끊기면 credentials를 null로 내려 요약 호출을 멈춘다(12.1, V16).
 */
class SummaryLinkStore(context: Context) : SummaryLink {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _credentials = MutableStateFlow(read())
    override val credentials: StateFlow<SummaryCredentials?> = _credentials.asStateFlow()

    private val _serverUrl = MutableStateFlow(prefs.getString(KEY_URL, null))
    val serverUrl: StateFlow<String?> = _serverUrl.asStateFlow()

    override val lastPhoneName: String? get() = prefs.getString(KEY_PHONE_NAME, null)

    /** 아직 서버에 전하지 못한 삭제. 재시작해도 잊지 않는다 */
    var pendingDeletion: String?
        get() = prefs.getString(KEY_PENDING_DELETION, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_PENDING_DELETION) else putString(KEY_PENDING_DELETION, value) }

    val baseUrl: String get() = prefs.getString(KEY_URL, null) ?: DEFAULT_URL
    val vehicleToken: String? get() = prefs.getString(KEY_TOKEN, null)

    private fun read(): SummaryCredentials? {
        val token = prefs.getString(KEY_TOKEN, null) ?: return null
        if (!prefs.getBoolean(KEY_PHONE_PAIRED, false)) return null
        return SummaryCredentials(prefs.getString(KEY_URL, null) ?: DEFAULT_URL, token)
    }

    /** 토큰은 그 서버가 발급한 것이다. 주소를 바꾸면 새 호스트로 보내지 않게 지우고 다시 연결하게 한다 */
    fun setServerUrl(url: String) {
        val changed = baseUrl != url
        prefs.edit {
            putString(KEY_URL, url)
            if (changed) {
                remove(KEY_TOKEN)
                remove(KEY_PHONE_PAIRED)
                remove(KEY_PHONE_NAME)
                remove(KEY_PENDING_DELETION)
            }
        }
        _serverUrl.value = url
        _credentials.value = read()
    }

    fun setToken(token: String) {
        prefs.edit { putString(KEY_TOKEN, token) }
        _credentials.value = read()
    }

    fun setPhonePaired(paired: Boolean) {
        prefs.edit {
            putBoolean(KEY_PHONE_PAIRED, paired)
            if (!paired) remove(KEY_PHONE_NAME)
        }
        _credentials.value = read()
    }

    fun clearAll() {
        prefs.edit {
            remove(KEY_TOKEN)
            remove(KEY_PHONE_PAIRED)
            remove(KEY_PHONE_NAME)
        }
        _credentials.value = null
    }

    override fun rememberPhoneName(name: String) {
        if (name != lastPhoneName) prefs.edit { putString(KEY_PHONE_NAME, name) }
    }

    override suspend fun onTokenRevoked() = clearAll()

    override suspend fun onPhoneUnpaired() = setPhonePaired(false)

    private companion object {
        const val PREFS = "cd_link"
        const val KEY_URL = "server_url"
        const val KEY_TOKEN = "vehicle_token"
        const val KEY_PHONE_PAIRED = "phone_paired"
        const val KEY_PHONE_NAME = "phone_name"
        const val KEY_PENDING_DELETION = "pending_deletion"
        const val DEFAULT_URL = "http://10.0.2.2:8080"   // 12장: 에뮬레이터에서 본 호스트 루프백
    }
}
