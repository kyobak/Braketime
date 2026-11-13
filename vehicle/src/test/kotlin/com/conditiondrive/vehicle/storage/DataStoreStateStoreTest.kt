package com.conditiondrive.vehicle.storage

import com.conditiondrive.vehicle.port.EngineStateRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DataStoreStateStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val scopes = mutableListOf<CoroutineScope>()
    private val record = EngineStateRecord("{\"schemaVersion\":1}", 7, 123.4, "{\"s\":1}", 9000L, 42L)

    private fun store(name: String): DataStoreStateStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scopes += scope
        return DataStoreStateStore.create(temp.root.resolve("$name.preferences_pb"), scope)
    }

    @After fun tearDown() = scopes.forEach { it.cancel() }

    @Test fun emptyLoadsNull() = runBlocking { assertNull(store("a").load()) }

    @Test fun roundTrip() = runBlocking {
        val s = store("a")
        s.save(record)
        assertEquals(record, s.load())
    }

    @Test fun nullableFieldsAreRemoved() = runBlocking {
        val s = store("a")
        s.save(record)
        val cleared = record.copy(odometerKm = null, lastSummaryJson = null, lastSummaryReceivedAtMs = null)
        s.save(cleared)
        assertEquals(cleared, s.load())
    }

    @Test fun clearRemovesEverything() = runBlocking {
        val s = store("a")
        s.save(record)
        s.clear()
        assertNull(s.load())
    }

    /** R7: 시연과 실사용 파일이 섞이지 않는다 */
    @Test fun demoAndLiveAreSeparate() = runBlocking {
        val live = store(DataStoreStateStore.LIVE_FILE)
        val demo = store(DataStoreStateStore.DEMO_FILE)
        live.save(record)
        assertNull(demo.load())
        demo.save(record.copy(bootCount = 1))
        demo.clear()
        assertEquals(record, live.load())
    }

    @Test fun corruptFileStartsFresh() = runBlocking {
        val file = temp.root.resolve("broken.preferences_pb")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        val s = DataStoreStateStore.create(file, CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it })
        assertNull(s.load())
        s.save(record)
        assertEquals(record, s.load())
    }

    @Test fun surviveReopen() = runBlocking {
        val file = temp.root.resolve("re.preferences_pb")
        val first = CoroutineScope(Dispatchers.IO + SupervisorJob())
        DataStoreStateStore.create(file, first).save(record)
        first.cancel()
        // DataStore는 같은 파일에 활성 인스턴스가 있으면 거절한다. 프로세스 재시작을 흉내 내려면 앞 scope를 끝낸다
        kotlinx.coroutines.delay(100)
        assertEquals(record, store("re").load())
    }
}
