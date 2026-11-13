package com.conditiondrive.vehicle.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.conditiondrive.core.engine.TripEvent
import com.conditiondrive.vehicle.port.TripLogPort
import com.conditiondrive.vehicle.port.TripRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

internal interface TripEventStore {
    fun insert(row: TripEventRow)
    fun since(atMs: Long): List<TripEventRow>
    fun deleteBefore(atMs: Long)
    fun deleteAll()
}

/** F8 로컬 기록. 보존은 30일(C-54)이고 사건을 쓸 때 오래된 것을 지운다 */
class TripLogDb internal constructor(
    private val store: TripEventStore,
    private val nowMs: () -> Long,
    private val zone: ZoneId = ZoneId.of("Asia/Seoul"),
    private val retentionDays: Int = 30,
    private val version: MutableStateFlow<Long> = MutableStateFlow(0L),
) : TripLogPort {

    /** 시연은 시뮬레이션 날짜로 쓰고 읽어야 한다. 벽시계로 읽으면 기록이 있어도 "오늘 기록이 없어요"가 된다 */
    fun viewedAt(nowMs: () -> Long): TripLogDb = TripLogDb(store, nowMs, zone, retentionDays, version)

    override suspend fun record(event: TripEvent, atMs: Long) {
        withContext(Dispatchers.IO) {
            try {
                store.insert(TripEventRow(event, atMs))
                store.deleteBefore(atMs - retentionDays * DAY_MS)
                version.value = version.value + 1
            } catch (_: Exception) {
                // 기록이 빠져도 운전 판단과 음성에는 영향이 없다
            }
        }
    }

    override fun today(): Flow<List<TripRecord>> = version.map {
        withContext(Dispatchers.IO) {
            try {
                readToday()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override fun recent(days: Int): Flow<List<TripRecord>> = version.map {
        withContext(Dispatchers.IO) {
            try {
                // 창 시작(오늘 포함 N일째 0시)보다 하루 더 읽어야 그 전날 시작한 운행이 창 안 운행으로 잘못 쪼개지지 않는다
                TripFold.fold(store.since(nowMs() - (days + 1) * DAY_MS), zone)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    private fun readToday(): List<TripRecord> {
        val day = Instant.ofEpochMilli(nowMs()).atZone(zone).toLocalDate()
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        // 어제 시작한 운전이 자정을 넘길 수 있어 하루 더 읽어 접는다
        return TripFold.onDay(TripFold.fold(store.since(start - DAY_MS), zone), start, end)
    }

    override suspend fun deleteAll() {
        withContext(Dispatchers.IO) {
            try {
                store.deleteAll()
                version.value = version.value + 1
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        const val LIVE_DB = "trip_log.db"
        const val DEMO_DB = "demo_trip_log.db"

        fun open(context: Context, nowMs: () -> Long, name: String = LIVE_DB): TripLogDb =
            TripLogDb(SqliteTripEventStore(context.applicationContext, name), nowMs)
    }
}

internal class SqliteTripEventStore(context: Context, name: String) :
    SQLiteOpenHelper(context, name, null, 1), TripEventStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE trip_events (id INTEGER PRIMARY KEY AUTOINCREMENT, at_ms INTEGER NOT NULL, event TEXT NOT NULL)")
        db.execSQL("CREATE INDEX trip_events_at ON trip_events (at_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    override fun insert(row: TripEventRow) {
        writableDatabase.insert(
            "trip_events", null,
            ContentValues().apply {
                put("at_ms", row.atMs)
                put("event", row.event.name)
            },
        )
    }

    override fun since(atMs: Long): List<TripEventRow> =
        readableDatabase.query("trip_events", arrayOf("at_ms", "event"), "at_ms >= ?", arrayOf(atMs.toString()), null, null, "at_ms, id").use { c ->
            buildList {
                while (c.moveToNext()) {
                    // 이후 버전에서 사건 이름이 바뀌어도 읽기가 죽지 않게 한다
                    val event = TripEvent.entries.firstOrNull { it.name == c.getString(1) } ?: continue
                    add(TripEventRow(event, c.getLong(0)))
                }
            }
        }

    override fun deleteBefore(atMs: Long) {
        writableDatabase.delete("trip_events", "at_ms < ?", arrayOf(atMs.toString()))
    }

    override fun deleteAll() {
        writableDatabase.delete("trip_events", null, null)
    }
}
