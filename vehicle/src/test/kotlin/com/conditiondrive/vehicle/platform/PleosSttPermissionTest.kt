package com.conditiondrive.vehicle.platform

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

/** SDK는 RECORD_AUDIO 없이도 request를 받아들인다(실측). 거부한 권한을 무시하고 듣거나 듣는 척하면 안 된다 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PleosSttPermissionTest {
    @Test
    fun deniedMicNeverReachesSdk() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        // 권한 확인이 먼저라 SDK 객체를 만들지 않는다. 만들면 이 JVM에서는 예외가 난다
        assertFalse(PleosSttEngine(context, CoroutineScope(Dispatchers.Unconfined)).prepare())
    }
}
