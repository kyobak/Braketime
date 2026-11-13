package com.conditiondrive.companion.storage

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "companion_state")

class DataStoreCompanionStore(context: Context) : CompanionStore {
    private val store = context.applicationContext.dataStore
    private val key = stringPreferencesKey("state")
    private val json = Json { ignoreUnknownKeys = true }

    override val state: Flow<StoredState> = store.data.map { prefs -> decode(prefs[key]) }

    override suspend fun read(): StoredState = state.first()

    override suspend fun update(transform: (StoredState) -> StoredState) {
        store.edit { prefs ->
            prefs[key] = json.encodeToString(StoredState.serializer(), transform(decode(prefs[key])))
        }
    }

    // 깨진 저장값으로 앱이 못 뜨는 것보다 미연결로 돌아가는 편이 낫다. 다시 연결하면 복구된다
    private fun decode(text: String?): StoredState {
        if (text == null) return StoredState()
        return try {
            json.decodeFromString(StoredState.serializer(), text)
        } catch (_: SerializationException) {
            StoredState()
        } catch (_: IllegalArgumentException) {
            StoredState()
        }
    }
}
