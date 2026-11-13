package com.conditiondrive.vehicle.ui.model

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource

/**
 * 프레젠터가 문구를 고르고 화면이 글자로 바꾼다.
 * 화면 분기를 Compose 없이 테스트하고, 문구 출처를 strings 또는 :core로 한정하려는 구조다.
 */
@Immutable
sealed interface UiText {
    /** :core Formats 결과나 장소 이름처럼 리소스가 아닌 값 */
    data class Plain(val value: String) : UiText

    /** 인자는 String, Int, 또는 다른 UiText */
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())
    }
}

@Composable
fun UiText.text(): String = when (this) {
    is UiText.Plain -> value
    is UiText.Res -> {
        val resolved = args.map { if (it is UiText) it.text() else it }
        stringResource(id, *resolved.toTypedArray())
    }
}
