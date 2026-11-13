package com.conditiondrive.server.db

import com.conditiondrive.server.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException

class Database private constructor(private val conn: Connection) : Closeable {
    // 12.15: SQLite 쓰기 경합을 없애려고 커넥션 하나를 한 스레드씩만 쓴다. block이 suspend가 아니라 트랜잭션이 섞이지 않는다
    @OptIn(ExperimentalCoroutinesApi::class)
    private val writer = Dispatchers.IO.limitedParallelism(1)

    /** 모든 트랜잭션은 BEGIN IMMEDIATE다. 호출 제한 슬롯을 읽고 쓰는 사이에 다른 요청이 끼면 한도가 뚫린다(12.14). */
    suspend fun <T> tx(block: Connection.() -> T): T = withContext(writer) { conn.inTransaction(block) }

    suspend fun ping(): Boolean = try {
        withContext(writer) { conn.query("SELECT 1") { it.getInt(1) }.single() == 1 }
    } catch (_: SQLException) {
        false
    }

    /** 트랜잭션 안에서 실행할 수 없다. */
    suspend fun checkpoint() = withContext(writer) {
        conn.createStatement().use { it.execute("PRAGMA wal_checkpoint(TRUNCATE)") }
        Unit
    }

    override fun close() {
        runBlocking { withContext(writer) { conn.close() } }
    }

    companion object {
        fun open(path: Path): Database {
            path.toAbsolutePath().parent?.let(Files::createDirectories)
            // SQLite는 -wal, -shm을 본 파일 권한으로 만든다. WAL에도 요약이 있으므로 첫 연결 전에 권한을 잠근다
            if (Files.notExists(path)) Files.createFile(path)
            Config.ownerOnly(path)
            val conn = DriverManager.getConnection("jdbc:sqlite:$path")
            conn.createStatement().use { st ->
                st.execute("PRAGMA secure_delete=ON")
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA journal_mode=WAL")
            }
            Migrations.apply(conn)
            return Database(conn)
        }
    }
}

internal fun <T> Connection.inTransaction(block: Connection.() -> T): T {
    createStatement().use { it.execute("BEGIN IMMEDIATE") }
    return try {
        val result = block()
        createStatement().use { it.execute("COMMIT") }
        result
    } catch (e: Throwable) {
        // 원래 예외를 지키려고 ROLLBACK 실패는 무시한다
        runCatching { createStatement().use { it.execute("ROLLBACK") } }
        throw e
    }
}

private fun PreparedStatement.bind(args: Array<out Any?>) {
    args.forEachIndexed { i, arg ->
        val index = i + 1
        when (arg) {
            null -> setObject(index, null)
            is Long -> setLong(index, arg)
            is Int -> setInt(index, arg)
            is String -> setString(index, arg)
            is ByteArray -> setBytes(index, arg)
            else -> error("지원하지 않는 바인딩 타입: ${arg::class.simpleName}")
        }
    }
}

fun Connection.exec(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { st ->
        st.bind(args)
        st.executeUpdate()
    }

fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { st ->
        st.bind(args)
        st.executeQuery().use { rs ->
            buildList { while (rs.next()) add(map(rs)) }
        }
    }

fun <T> Connection.queryOne(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    query(sql, *args, map = map).firstOrNull()

fun Connection.count(sql: String, vararg args: Any?): Int =
    query(sql, *args) { it.getInt(1) }.single()

fun ResultSet.longOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}
