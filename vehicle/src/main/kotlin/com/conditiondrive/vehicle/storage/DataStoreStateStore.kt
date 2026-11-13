package com.conditiondrive.vehicle.storage

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.conditiondrive.vehicle.port.EngineStateRecord
import com.conditiondrive.vehicle.port.StateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * 엔진 상태 저장. 시연과 실사용은 파일을 나눈다(15.2, R7).
 * 저장이 실패해도 측정을 멈추지 않는다. 다음 저장에서 다시 쓰고 복원은 null이면 새 시작이다.
 */
class DataStoreStateStore internal constructor(private val store: DataStore<Preferences>) : StateStore {
    override suspend fun load(): EngineStateRecord? = try {
        val prefs = store.data.first()
        prefs[ENGINE_STATE]?.let { json ->
            EngineStateRecord(
                engineStateJson = json,
                bootCount = prefs[BOOT_COUNT] ?: UNKNOWN_BOOT_COUNT,
                odometerKm = prefs[ODOMETER_KM],
                lastSummaryJson = prefs[LAST_SUMMARY_JSON],
                lastSummaryReceivedAtMs = prefs[LAST_SUMMARY_AT_MS],
                tripLogCursor = prefs[TRIP_LOG_CURSOR] ?: 0L,
            )
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Exception) {
        null
    }

    override suspend fun save(record: EngineStateRecord) {
        try {
            store.edit { p ->
                p[ENGINE_STATE] = record.engineStateJson
                p[BOOT_COUNT] = record.bootCount
                p[TRIP_LOG_CURSOR] = record.tripLogCursor
                setOrRemove(p, ODOMETER_KM, record.odometerKm)
                setOrRemove(p, LAST_SUMMARY_JSON, record.lastSummaryJson)
                setOrRemove(p, LAST_SUMMARY_AT_MS, record.lastSummaryReceivedAtMs)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    override suspend fun clear() {
        try {
            store.edit { it.clear() }
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
        }
    }

    private fun <T> setOrRemove(prefs: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<T>, value: T?) {
        if (value == null) prefs.remove(key) else prefs[key] = value
    }

    companion object {
        const val LIVE_FILE = "engine_state"
        const val DEMO_FILE = "demo_state"
        const val UNKNOWN_BOOT_COUNT = -1

        private val ENGINE_STATE = stringPreferencesKey("engine_state_json")
        private val BOOT_COUNT = intPreferencesKey("boot_count")
        private val ODOMETER_KM = doublePreferencesKey("odometer_km")
        private val LAST_SUMMARY_JSON = stringPreferencesKey("last_summary_json")
        private val LAST_SUMMARY_AT_MS = longPreferencesKey("last_summary_received_at_ms")
        private val TRIP_LOG_CURSOR = longPreferencesKey("trip_log_cursor")

        private val open = mutableMapOf<String, DataStoreStateStore>()

        /** 같은 파일에 DataStore를 둘 만들면 예외가 난다. 프로세스에서 이름당 하나만 둔다 */
        fun get(context: Context, name: String = LIVE_FILE): DataStoreStateStore = synchronized(open) {
            open.getOrPut(name) { create(File(context.applicationContext.filesDir, "datastore/$name.preferences_pb")) }
        }

        internal fun create(file: File, scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())): DataStoreStateStore =
            DataStoreStateStore(
                PreferenceDataStoreFactory.create(
                    // 깨진 파일 하나로 앱이 못 뜨면 안 된다. 복원을 포기하고 새로 시작한다(UNKNOWN 공백은 운전으로 친다)
                    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                    scope = scope,
                    produceFile = { file },
                ),
            )

        /** 13장 재부팅 판별. AAOS 사용자 10에서도 읽힌다(실측) */
        fun currentBootCount(context: Context): Int =
            try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, UNKNOWN_BOOT_COUNT)
            } catch (_: Exception) {
                UNKNOWN_BOOT_COUNT
            }
    }
}
