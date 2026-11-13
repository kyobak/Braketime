package com.conditiondrive.vehicle.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SummaryLinkStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /** 토큰은 발급한 서버 것이다. 주소를 바꾸면 새 호스트로 보내지 않는다 */
    @Test
    fun changingServerUrlDropsToken() {
        val store = SummaryLinkStore(context)
        store.setToken("cdv")
        store.setPhonePaired(true)
        assertNotNull(store.credentials.value)
        store.setServerUrl("https://example.test")
        assertNull(store.vehicleToken)
        assertNull(store.credentials.value)
    }

    @Test
    fun sameUrlKeepsToken() {
        val store = SummaryLinkStore(context)
        store.setServerUrl("https://example.test")
        store.setToken("cdv")
        store.setServerUrl("https://example.test")
        assertEquals("cdv", store.vehicleToken)
    }

    /** 재시작한 뒤 서버에 닿기 전에도 S5가 연결을 "없음"으로 말하지 않는다 */
    @Test
    fun phoneNameSurvivesRestartUntilUnpaired() {
        val store = SummaryLinkStore(context)
        store.setToken("cdv")
        store.setPhonePaired(true)
        store.rememberPhoneName("Galaxy S25")
        assertEquals("Galaxy S25", SummaryLinkStore(context).lastPhoneName)
        store.setPhonePaired(false)
        assertNull(SummaryLinkStore(context).lastPhoneName)
    }

    /** 재시작해도 못 끝낸 삭제를 잊지 않는다 */
    @Test
    fun pendingDeletionPersists() {
        val store = SummaryLinkStore(context)
        store.pendingDeletion = "ACCOUNT"
        assertEquals("ACCOUNT", SummaryLinkStore(context).pendingDeletion)
        store.pendingDeletion = null
        assertNull(SummaryLinkStore(context).pendingDeletion)
    }
}
