package com.conditiondrive.vehicle.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * 지침서 선행 묵음 우선순위(① 데이터 패딩 > ② 엔진 큐 묵음 > ③ 재생 지연)에서 엔진마다 쓸 수 있는 가장 높은 방식(U-27).
 * ChainSpeakerTest의 가짜 엔진은 방식을 스스로 정하므로, 실제 엔진의 배정은 여기서 고정한다.
 * 생성자는 SDK에 바인딩하지 않아 JVM에서 만들 수 있다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EngineLeadSilenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun androidTtsUsesInEngineSilence() {
        assertEquals(LeadSilence.IN_ENGINE, AndroidTtsEngine(context).leadSilence)
    }

    /** Pleos speak(text)는 Speech Service가 직접 재생해 앱이 오디오를 받지 못한다. ③만 남는다 */
    @Test
    fun pleosTtsUsesPlayerDelay() {
        assertEquals(LeadSilence.PLAYER_DELAY, PleosTtsEngine(context).leadSilence)
    }
}
