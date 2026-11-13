package com.conditiondrive.vehicle.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.conditiondrive.core.engine.CheckinAnswer
import com.conditiondrive.core.voice.PrivacyContext
import com.conditiondrive.core.voice.VoiceCatalog
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.ui.components.CdText
import com.conditiondrive.vehicle.ui.components.CdWindow
import com.conditiondrive.vehicle.ui.components.Hairline
import com.conditiondrive.vehicle.ui.components.LocalWindowPadding
import com.conditiondrive.vehicle.ui.components.touch.PrimaryButton
import com.conditiondrive.vehicle.ui.components.touch.SecondaryButton
import com.conditiondrive.vehicle.ui.model.UiFormat
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme

const val TAG_ONBOARDING = "Onboarding"

enum class OnboardingStep { INTRO, PERMISSIONS, LISTEN }

/** Back으로 갈 이전 단계. 첫 단계면 null이고 Back은 앱을 숨긴다(U-27) */
fun OnboardingStep.previous(): OnboardingStep? = OnboardingStep.entries.getOrNull(ordinal - 1)

/** O3 상태. 듣는 중 표시 외에 애니메이션은 없다 */
sealed interface VoiceTrial {
    data object Idle : VoiceTrial
    data object Speaking : VoiceTrial
    data object Listening : VoiceTrial
    data class Heard(val answer: CheckinAnswer?) : VoiceTrial
    /** 듣지 못하는 상태. 묻지 않았으므로 Heard와 다르다(R-02) */
    data object Unavailable : VoiceTrial
}

@Composable
fun OnboardingScreen(
    step: OnboardingStep,
    trial: VoiceTrial,
    demoLabel: String?,
    onRequestPermissions: () -> Unit,
    onNext: () -> Unit,
    onListen: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    voiceBlocked: Boolean = false,
) {
    // 오른쪽에 둘 내용이 없다. 빈 회색 창은 불러오다 실패한 화면처럼 보여 창 하나만 그린다
    CdWindow(demoLabel = demoLabel, modifier = modifier.testTag(TAG_ONBOARDING), keepEmptyRight = false) {
        when (step) {
            OnboardingStep.INTRO -> IntroStep(onNext)
            OnboardingStep.PERMISSIONS -> PermissionsStep(onRequestPermissions, onNext)
            OnboardingStep.LISTEN ->
                // 대답을 들을 수 없으면 묻는 화면 자체를 내지 않는다. "말해 보기"를 누르면 질문만 듣고 "알아듣지 못했어요"가 된다
                if (voiceBlocked || trial is VoiceTrial.Unavailable) ListenOffStep(onFinish) else ListenStep(trial, onListen, onFinish)
        }
    }
}

private const val STEP_COUNT = 3

@Composable
private fun ColumnScope.StepBody(
    step: Int,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space48),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CdDimens.Space24),
        ) {
            CdText(title, CdTheme.type.headlineM, Modifier.weight(1f))
            // 몇 단계 남았는지 알면 끝까지 볼 이유가 생긴다
            CdText(stringResource(R.string.onboarding_step, step, STEP_COUNT), CdTheme.type.titleS, color = CdTheme.colors.text3, maxLines = 1)
        }
        Hairline()
        content()
    }
    Hairline()
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space24),
        content = buttons,
    )
}

@Composable
private fun BodyText(text: String) {
    CdText(
        text,
        CdTheme.type.bodyM,
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalWindowPadding.current)
            .padding(top = CdDimens.Space24),
        color = CdTheme.colors.text2,
    )
}

/**
 * O2 권한 행. 무엇을 허용하는지가 요점이라 권한 이름이 크고 진하다.
 * InfoRow(라벨-값)의 무게를 뒤집으면 다른 화면의 InfoRow와 의미가 헷갈려 따로 둔다
 */
@Composable
private fun PermissionRow(name: String, purpose: String) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = CdDimens.ListRow)
                .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space16),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CdText(name, CdTheme.type.titleM, maxLines = 1)
            CdText(purpose, CdTheme.type.titleS, color = CdTheme.colors.text2, maxLines = 1)
        }
        Hairline()
    }
}

@Composable
private fun ColumnScope.IntroStep(onNext: () -> Unit) {
    StepBody(
        step = 1,
        title = stringResource(R.string.o1_title),
        content = {
            // 규칙이 이 화면의 요점이다. 고지 두 줄은 약하게 만들지 않고 본문 무게를 유지한다
            CdText(
                stringResource(R.string.o1_rule),
                CdTheme.type.titleM,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalWindowPadding.current)
                    .padding(top = CdDimens.Space32),
            )
            BodyText(stringResource(R.string.o1_body))
            BodyText(stringResource(R.string.legal_not_official))
        },
        buttons = { PrimaryButton(stringResource(R.string.o1_start), onNext, Modifier.fillMaxWidth()) },
    )
}

@Composable
private fun ColumnScope.PermissionsStep(onRequest: () -> Unit, onNext: () -> Unit) {
    StepBody(
        step = 2,
        title = stringResource(R.string.o2_title),
        content = {
            PermissionRow(stringResource(R.string.perm_speed), stringResource(R.string.perm_speed_why))
            PermissionRow(stringResource(R.string.perm_location), stringResource(R.string.perm_location_why))
            PermissionRow(stringResource(R.string.perm_mic), stringResource(R.string.perm_mic_why))
            PermissionRow(stringResource(R.string.perm_notify), stringResource(R.string.perm_notify_why))
        },
        buttons = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CdDimens.Space24), verticalAlignment = Alignment.CenterVertically) {
                // 나란한 버튼은 높이를 맞춘다. 기본 높이가 달라 어긋나 보인다
                PrimaryButton(stringResource(R.string.o2_request), onRequest, Modifier.weight(1f).height(CdDimens.PrimaryButton))
                SecondaryButton(stringResource(R.string.o2_later), onNext, Modifier.weight(1f).height(CdDimens.PrimaryButton))
            }
        },
    )
}

@Composable
private fun ColumnScope.ListenStep(trial: VoiceTrial, onListen: () -> Unit, onFinish: () -> Unit) {
    // 문구를 따로 두면 실제 음성과 어긋나므로 체크인 문장을 그대로 읽는다
    val prompt = VoiceCatalog.render(VoiceId.V03, VoiceVars(), PrivacyContext(passengerPresentOrUnknown = true))
    StepBody(
        step = 3,
        title = stringResource(R.string.o3_title),
        content = {
            CdText(
                prompt,
                CdTheme.type.titleL,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalWindowPadding.current)
                    .padding(top = CdDimens.Space32),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LocalWindowPadding.current)
                    .padding(top = CdDimens.Space24),
            ) {
                when (trial) {
                    // 앱이 말하는 동안에는 질문 문장만 둔다. 마이크가 열린 뒤에 "듣고 있어요"다
                    VoiceTrial.Speaking -> Unit
                    VoiceTrial.Listening -> CdText(stringResource(R.string.o3_listening), CdTheme.type.titleM, color = CdTheme.colors.text2)
                    else -> SecondaryButton(stringResource(R.string.o3_listen), onListen, Modifier.fillMaxWidth())
                }
            }
            BodyText(stringResource(R.string.o3_examples))
            if (trial is VoiceTrial.Heard) {
                val text = trial.answer?.takeIf { it != CheckinAnswer.NO_SPEECH && it != CheckinAnswer.UNCLASSIFIED }
                    ?.let { stringResource(R.string.o3_result, stringResource(UiFormat.checkin(it))) }
                    ?: stringResource(R.string.o3_no_result)
                BodyText(text)
            }
            BodyText(stringResource(R.string.o3_mute_hint))
        },
        buttons = {
            // 결과가 같은 버튼 둘은 고민만 늘린다. 해 봤으면 끝내기, 아니면 건너뛰기 하나다
            if (trial is VoiceTrial.Heard) {
                PrimaryButton(stringResource(R.string.o3_done), onFinish, Modifier.fillMaxWidth())
            } else {
                SecondaryButton(stringResource(R.string.o3_skip), onFinish, Modifier.fillMaxWidth())
            }
        },
    )
}

@Composable
private fun ColumnScope.ListenOffStep(onFinish: () -> Unit) {
    StepBody(
        step = 3,
        title = stringResource(R.string.o3_off_title),
        content = { BodyText(stringResource(R.string.o3_off_body)) },
        buttons = { PrimaryButton(stringResource(R.string.o3_done), onFinish, Modifier.fillMaxWidth()) },
    )
}

/** SetupActivity 콜드 스타트 대기 화면. 게이트가 정해지기 전이라 터치 요소를 두지 않는다 */
@Composable
fun SetupWaitingScreen(demoLabel: String?, modifier: Modifier = Modifier) {
    CdWindow(demoLabel = demoLabel, modifier = modifier) {
        Spacer(Modifier.weight(1f))
        CdText(
            stringResource(R.string.setup_waiting),
            CdTheme.type.titleM,
            Modifier.padding(horizontal = LocalWindowPadding.current),
            color = CdTheme.colors.text2,
        )
        Spacer(Modifier.weight(1f))
    }
}
