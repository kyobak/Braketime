package com.conditiondrive.vehicle.service

import com.conditiondrive.core.contract.PairingCodeResponse
import com.conditiondrive.core.contract.PairingStatusResponse
import com.conditiondrive.core.contract.PhoneInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServerDeletionTest {
    private class Store(override var token: String? = "cdv") : DeletionStore {
        override var pending: DeletionKind? = null
        override val baseUrl = "http://10.0.2.2:8080"
        var accountDeleted = 0
        override fun onAccountDeleted() {
            accountDeleted++
            token = null
        }
    }

    private class Rig(scope: TestScope, val store: Store = Store()) {
        var online = false
        val calls = mutableListOf<DeletionKind>()
        val deletion = ServerDeletion(
            { kind, _, _ ->
                calls += kind
                online
            },
            store,
            scope.backgroundScope,
        )
    }

    /** 터널에서 눌러도 토큰을 잃지 않는다. 토큰이 사라지면 다시 지울 수단이 없고 폰은 계속 올린다 */
    @Test
    fun `실패하면 토큰을 지키고 재시도한다`() = runTest {
        val r = Rig(this)
        r.deletion.request(DeletionKind.ACCOUNT)
        runCurrent()
        assertEquals("cdv", r.store.token)
        assertEquals(DeletionKind.ACCOUNT, r.store.pending)
        assertEquals(DeletionState.Pending(DeletionKind.ACCOUNT), r.deletion.state.value)
        assertEquals(0, r.store.accountDeleted)

        r.online = true
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(2, r.calls.size)
        assertNull(r.store.token)
        assertNull(r.store.pending)
        assertEquals(DeletionState.Done(DeletionKind.ACCOUNT), r.deletion.state.value)
    }

    @Test
    fun `재시도 간격은 15 30 60 60초다`() = runTest {
        val r = Rig(this)
        r.deletion.request(DeletionKind.UNPAIR)
        runCurrent()
        assertEquals(1, r.calls.size)
        advanceTimeBy(15_001); runCurrent()
        assertEquals(2, r.calls.size)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, r.calls.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(4, r.calls.size)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(5, r.calls.size)
    }

    @Test
    fun `연결 해제는 토큰을 남긴다`() = runTest {
        val r = Rig(this)
        r.online = true
        r.deletion.request(DeletionKind.UNPAIR)
        runCurrent()
        assertEquals("cdv", r.store.token)
        assertNull(r.store.pending)
        assertEquals(DeletionState.Done(DeletionKind.UNPAIR), r.deletion.state.value)
    }

    @Test
    fun `계정 삭제 요청은 대기 중인 해제를 덮고 그 반대는 아니다`() = runTest {
        val r = Rig(this)
        r.deletion.request(DeletionKind.UNPAIR)
        runCurrent()
        r.deletion.request(DeletionKind.ACCOUNT)
        runCurrent()
        assertEquals(DeletionKind.ACCOUNT, r.store.pending)
        r.deletion.request(DeletionKind.UNPAIR)
        runCurrent()
        assertEquals(DeletionKind.ACCOUNT, r.store.pending)
    }

    /** 재시작 전에 끝내지 못한 삭제를 잊지 않는다 */
    @Test
    fun `서비스 시작 때 남은 삭제를 잇는다`() = runTest {
        val store = Store().apply { pending = DeletionKind.ACCOUNT }
        val r = Rig(this, store)
        r.online = true
        r.deletion.resume()
        runCurrent()
        assertEquals(listOf(DeletionKind.ACCOUNT), r.calls)
        assertNull(store.token)
    }

    @Test
    fun `토큰이 없으면 호출 없이 끝난다`() = runTest {
        val store = Store(token = null).apply { pending = DeletionKind.UNPAIR }
        val r = Rig(this, store)
        assertTrue(r.deletion.drain())
        assertTrue(r.calls.isEmpty())
    }

    private fun code(replaces: Boolean) = PairingCodeResponse("K7M2QX9T", "q", "2026-11-19T23:17:05Z", 300, replaces, "2026-11-19T23:12:05Z")

    private fun status(paired: Boolean, at: String = "2026-11-19T23:13:10Z") = PairingStatusResponse(
        paired,
        if (paired) PhoneInfo("d", "폰", at, null) else null,
        null,
        null,
        "2026-11-19T23:15:04Z",
    )

    /** 코드를 스캔하지 않았는데 옛 폰의 paired=true로 연결된 것처럼 되면 안 된다 */
    @Test
    fun `옛 폰이 남아 있으면 이번 코드로 연결됐을 때만 연결로 본다`() {
        assertFalse(PairingRule.consumed(code(replaces = false), status(false)))
        assertTrue(PairingRule.consumed(code(replaces = false), status(true)))
        assertFalse(PairingRule.consumed(code(replaces = true), status(true, at = "2026-11-18T23:13:10Z")))
        assertTrue(PairingRule.consumed(code(replaces = true), status(true, at = "2026-11-19T23:13:10Z")))
        assertFalse(PairingRule.consumed(code(replaces = true), status(true, at = "깨진 값")))
    }
}
