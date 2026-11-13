package com.conditiondrive.companion.simulator

import com.conditiondrive.core.voice.Formats
import kotlin.math.floor

/** 4장 mg/dL 표시. 판단 값은 mmol/L이고 mg/dL은 입력과 표시용이다 */
object GlucoseUnits {
    private const val MMOL_TO_MGDL = 18.016

    fun toMgdl(mmol: Double): Int = Formats.mgdl(mmol)

    /** 소수 둘째 자리에서 내림한다. 값이 위로 넘어가 기준을 통과하는 일을 막는다 */
    fun toHundredths(mgdl: Int): Int = floor(mgdl / MMOL_TO_MGDL * 100).toInt()
}
