package com.conditiondrive.vehicle.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.conditiondrive.core.voice.Formats
import com.conditiondrive.vehicle.BuildConfig
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.port.LinkState
import com.conditiondrive.vehicle.ui.HoldRelease
import com.conditiondrive.vehicle.ui.PairingFacts
import com.conditiondrive.vehicle.ui.SettingsActions
import com.conditiondrive.vehicle.ui.SettingsFacts
import com.conditiondrive.vehicle.ui.components.CdText
import com.conditiondrive.vehicle.ui.components.CdWindow
import com.conditiondrive.vehicle.ui.components.Hairline
import com.conditiondrive.vehicle.ui.components.InfoRow
import com.conditiondrive.vehicle.ui.components.LocalTwoPanes
import com.conditiondrive.vehicle.ui.components.LocalWindowPadding
import com.conditiondrive.vehicle.ui.components.PairingQr
import com.conditiondrive.vehicle.ui.components.SourceLine
import com.conditiondrive.vehicle.ui.components.TitleHeader
import com.conditiondrive.vehicle.ui.components.touch.CdCheckbox
import com.conditiondrive.vehicle.ui.components.touch.ConfirmButton
import com.conditiondrive.vehicle.ui.components.touch.NavRow
import com.conditiondrive.vehicle.ui.components.touch.PrimaryButton
import com.conditiondrive.vehicle.ui.components.touch.SecondaryButton
import com.conditiondrive.vehicle.ui.components.touch.TabBar
import com.conditiondrive.vehicle.ui.model.DemoScenario
import com.conditiondrive.vehicle.ui.model.DeniedPermission
import com.conditiondrive.vehicle.ui.model.HiddenTapCounter
import com.conditiondrive.vehicle.ui.model.ParkedTab
import com.conditiondrive.vehicle.ui.model.S5Page
import com.conditiondrive.vehicle.ui.model.S5Presenter
import com.conditiondrive.vehicle.ui.model.ServerUrlRule
import com.conditiondrive.vehicle.ui.model.text
import com.conditiondrive.vehicle.ui.theme.CdDimens
import com.conditiondrive.vehicle.ui.theme.CdTheme
import kotlinx.coroutines.launch

const val TAG_S5 = "S5"
const val TAG_S5_TITLE = "S5Title"

/** S5 입력 묶음. 서버 주소 초안은 Driving 전환 때도 잃지 않도록 호출자가 저장한다(8.1) */
class S5State(
    val facts: SettingsFacts,
    val link: LinkState,
    val phoneName: String?,
    val denied: Set<DeniedPermission>,
    val demoLabel: String?,
    val serverDraft: String,
    // U-30. 값을 받은 뒤 끊겨 보류 중이다(엔진 `glucoseUnavailable`). 맨 뒤 기본값이라 위치 인자로 만드는 호출이 그대로다
    val glucoseHold: Boolean = false,
    // D-33. 보류를 풀 수 있는 가장 이른 시각. 낮은 값 뒤에는 마지막 판독에서 40분이다
    val holdReleasableAtMs: Long? = null,
    // 지금 유효한 값이 있다. 지난 보류 풀기 결과 문장을 지우는 기준이다
    val glucoseLive: Boolean = false,
)

@Composable
fun S5Screen(
    state: S5State,
    actions: SettingsActions,
    onTab: (ParkedTab) -> Unit,
    onServerDraft: (String) -> Unit,
    onDemoStarted: () -> Unit,
    nowMs: () -> Long,
    modifier: Modifier = Modifier,
    onRequestPermissions: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf<S5Page?>(null) }
    var serverUnlocked by rememberSaveable { mutableStateOf(false) }
    val tapCounter = remember { HiddenTapCounter() }

    CdWindow(
        demoLabel = state.demoLabel,
        modifier = modifier.testTag(TAG_S5),
        // 창 2개면 오른쪽 기본은 연결이다. 와이어프레임의 첫 화면이고 가장 자주 찾는 항목이다
        right = { DetailPane(page ?: S5Page.DEVICES, state, actions, serverUnlocked, onServerDraft, onDemoStarted, onRequestPermissions, nowMs) },
    ) {
        val twoPanes = LocalTwoPanes.current
        val inDetail = page != null && !twoPanes
        // 창 하나에서 연 페이지는 Back으로 목록에 돌아간다. 창 2개면 오른쪽 내용이라 닫을 것이 없다(U-27)
        BackHandler(enabled = inDetail) { page = null }
        val titleInteraction = remember { MutableInteractionSource() }
        Column(
            Modifier
                .testTag(TAG_S5_TITLE)
                .clickable(interactionSource = titleInteraction, indication = null) {
                    if (tapCounter.tap(nowMs())) {
                        serverUnlocked = true
                        page = S5Page.SERVER
                    }
                },
        ) {
            TitleHeader(stringResource(R.string.s5_title))
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (inDetail) {
                Row(Modifier.padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space16)) {
                    SecondaryButton(stringResource(R.string.s5_back), onClick = { page = null })
                }
                Hairline()
                DetailPage(page ?: S5Page.DEVICES, state, actions, serverUnlocked, onServerDraft, onDemoStarted, onRequestPermissions, nowMs)
            } else {
                S5List(state, page.takeIf { twoPanes }, serverUnlocked) { page = it }
            }
        }
        TabBar(ParkedTab.SETTINGS, onTab)
    }
}

@Composable
private fun S5List(state: S5State, selected: S5Page?, serverUnlocked: Boolean, onOpen: (S5Page) -> Unit) {
    val rows = S5Presenter.rows(state.link, state.phoneName, state.facts, state.glucoseHold)
    NavRow(stringResource(R.string.s5_devices), rows.devices.text(), selected == S5Page.DEVICES) { onOpen(S5Page.DEVICES) }
    NavRow(stringResource(R.string.s5_guardian), rows.guardian.text(), selected == S5Page.GUARDIAN) { onOpen(S5Page.GUARDIAN) }
    NavRow(stringResource(R.string.s5_search), rows.search.text(), selected == S5Page.SEARCH) { onOpen(S5Page.SEARCH) }
    NavRow(stringResource(R.string.s5_support), null, selected == S5Page.SUPPORT) { onOpen(S5Page.SUPPORT) }
    NavRow(stringResource(R.string.s5_sources), null, selected == S5Page.SOURCES) { onOpen(S5Page.SOURCES) }
    NavRow(stringResource(R.string.s5_data), null, selected == S5Page.DATA) { onOpen(S5Page.DATA) }
    NavRow(stringResource(R.string.s5_demo), null, selected == S5Page.DEMO) { onOpen(S5Page.DEMO) }
    if (serverUnlocked) NavRow(stringResource(R.string.s5_server), null, selected == S5Page.SERVER) { onOpen(S5Page.SERVER) }
}

@Composable
private fun ColumnScope.DetailPane(
    page: S5Page,
    state: S5State,
    actions: SettingsActions,
    advanced: Boolean,
    onServerDraft: (String) -> Unit,
    onDemoStarted: () -> Unit,
    onRequestPermissions: () -> Unit,
    nowMs: () -> Long,
) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        DetailPage(page, state, actions, advanced, onServerDraft, onDemoStarted, onRequestPermissions, nowMs)
    }
}

@Composable
private fun DetailPage(
    page: S5Page,
    state: S5State,
    actions: SettingsActions,
    advanced: Boolean,
    onServerDraft: (String) -> Unit,
    onDemoStarted: () -> Unit,
    onRequestPermissions: () -> Unit,
    nowMs: () -> Long,
) {
    when (page) {
        S5Page.DEVICES -> DevicesPage(state, actions, nowMs)
        S5Page.GUARDIAN -> Sentences(R.string.s5_guardian, listOf(stringResource(R.string.guardian_body)))
        S5Page.SEARCH -> Sentences(R.string.s5_search, listOf(stringResource(R.string.search_body)))
        S5Page.SUPPORT -> SupportPage(state, onRequestPermissions)
        S5Page.SOURCES -> Sentences(R.string.s5_sources, S5Presenter.sourceLines().map { it.text() })
        S5Page.DATA -> DataPage(state, actions)
        S5Page.DEMO -> DemoPage(state.facts, actions, advanced, onDemoStarted)
        S5Page.SERVER -> ServerPage(state.serverDraft, actions, onServerDraft)
    }
}

/** 값이 없으면 빈 칸 대신 문장을 쓴다(design-tokens 5장 "--" 금지) */
@Composable
private fun PageBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalWindowPadding.current, vertical = CdDimens.Space24),
        verticalArrangement = Arrangement.spacedBy(CdDimens.Space16),
        content = content,
    )
}

@Composable
private fun Sentences(title: Int, lines: List<String>) {
    TitleHeader(stringResource(title))
    PageBody {
        lines.forEach { CdText(it, CdTheme.type.bodyL, color = CdTheme.colors.text2) }
    }
}

@Composable
private fun DevicesPage(state: S5State, actions: SettingsActions, nowMs: () -> Long) {
    TitleHeader(stringResource(R.string.pair_title))
    PageBody {
        // U-30. 폰 쪽 해제 뒤에는 연결 해제 버튼이 없어 차에서 보류를 풀 길이 이것뿐이다. 연결이 있어도 끊길 수 있어 양쪽에 둔다.
        // 세로 창에서 QR 아래로 밀리면 찾지 못하니 맨 위에 둔다. S5는 정차 중에만 열리고, 엔진도 주행 중과 실제 낮은 값을 다시 거른다(E34)
        HoldBlock(state, actions, nowMs)
        if (state.link != LinkState.UNPAIRED) {
            val linked = state.phoneName?.let { stringResource(R.string.pair_linked, it) } ?: stringResource(R.string.pair_linked_offline)
            CdText(linked, CdTheme.type.titleM)
            ConfirmButton(stringResource(R.string.pair_unlink), onConfirmed = actions::unpair)
        } else {
            PairingBlock(state.facts.pairing, state.facts.pairingFailed, actions)
        }
    }
}

/**
 * 결과는 이 화면에만 둔다. S5를 떠나면 사라지고, 새 값이 오면 지난 결과는 사실이 아니라 지운다.
 * 두 번째 누름 뒤에는 결과가 올 때까지 버튼을 숨겨 다시 누를 수 없게 하고 진행 중임을 보인다(0.25초 원칙).
 */
@Composable
private fun HoldBlock(state: S5State, actions: SettingsActions, nowMs: () -> Long) {
    var result by remember { mutableStateOf<HoldRelease?>(null) }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.glucoseLive) { if (state.glucoseLive) result = null }

    val released = result == HoldRelease.RELEASED
    if (state.glucoseHold && !released) {
        CdText(stringResource(R.string.hold_body), CdTheme.type.bodyL, color = CdTheme.colors.text2)
        // D-33. 낮은 값 뒤의 보류는 회복 대기 40분 전에는 엔진이 받지 않는다. 눌러도 안 되는 버튼은 두지 않는다
        val waitMs = state.holdReleasableAtMs?.let { it - nowMs() }?.takeIf { it > 0 }
        when {
            sending -> CdText(stringResource(R.string.hold_sending), CdTheme.type.titleS)
            waitMs != null -> CdText(stringResource(R.string.hold_wait, Formats.remaining(waitMs / 1000)), CdTheme.type.titleS)
            else -> ConfirmButton(
                stringResource(R.string.hold_release),
                onConfirmed = {
                    sending = true
                    scope.launch {
                        result = actions.releaseGlucoseHold()
                        sending = false
                    }
                },
            )
        }
        if (!sending && (result == HoldRelease.REFUSED || result == HoldRelease.UNAVAILABLE)) {
            CdText(stringResource(R.string.hold_failed), CdTheme.type.titleS)
        }
        Spacer(Modifier.height(CdDimens.Space8))
    }
    if (released && !state.glucoseLive) {
        CdText(stringResource(R.string.data_hold_released), CdTheme.type.titleS)
    }
}

@Composable
private fun ColumnScope.PairingBlock(pairing: PairingFacts?, failed: Boolean, actions: SettingsActions) {
    if (pairing != null) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PairingQr(pairing.qrPayload)
        }
        CdText(pairing.code, CdTheme.type.headlineM, Modifier.align(Alignment.CenterHorizontally))
    } else if (failed) {
        CdText(stringResource(R.string.pair_no_code), CdTheme.type.bodyL, color = CdTheme.colors.text2)
    }
    CdText(stringResource(R.string.pair_hint), CdTheme.type.bodyL, color = CdTheme.colors.text2)
    if (pairing == null) PrimaryButton(stringResource(R.string.pair_request), onClick = actions::requestPairingCode, Modifier.fillMaxWidth())
}

/** 온보딩에서 "나중에"를 고르면 다시 물을 길이 여기뿐이다. S5는 정차 중에만 열리므로 주행 중에는 보이지 않는다 */
@Composable
private fun SupportPage(state: S5State, onRequestPermissions: () -> Unit) {
    val metrics = LocalContext.current.resources.displayMetrics
    TitleHeader(stringResource(R.string.s5_support))
    // 항목과 상태를 나눠야 지원되는 것과 안 되는 것을 훑어 비교할 수 있다. 상태 칸은 긴 문장이 라벨과 겹치지 않게 titleS다
    S5Presenter.supportRows(state.facts.support, state.denied).forEach { row ->
        InfoRow(row.label.text(), row.value.text(), valueStyle = CdTheme.type.titleS)
    }
    if (state.denied.isNotEmpty()) {
        PageBody {
            PrimaryButton(stringResource(R.string.perm_request), onRequestPermissions, Modifier.fillMaxWidth())
        }
    }
    SourceLine(S5Presenter.screenLine(metrics.density, metrics.ydpi).text())
}

@Composable
private fun DataPage(state: S5State, actions: SettingsActions) {
    TitleHeader(stringResource(R.string.s5_data))
    PageBody {
        CdText(stringResource(R.string.data_location), CdTheme.type.bodyL, color = CdTheme.colors.text2)
        CdText(stringResource(R.string.data_health), CdTheme.type.bodyL, color = CdTheme.colors.text2)
        // U-31. 철회 버튼은 두지 않는다. 동의를 받은 곳에서 철회해야 폰의 전송과 기록이 함께 멈춘다
        CdText(stringResource(R.string.data_withdraw), CdTheme.type.bodyL, color = CdTheme.colors.text2)
        Spacer(Modifier.height(CdDimens.Space8))
        ConfirmButton(stringResource(R.string.data_clear_trips), onConfirmed = actions::clearTrips)
        // 연결이 없으면 서버에 지울 것이 있는 것처럼 보이지 않게 한다
        if (state.link != LinkState.UNPAIRED) {
            ConfirmButton(stringResource(R.string.pair_unlink), onConfirmed = actions::unpair)
            ConfirmButton(stringResource(R.string.data_delete_server), onConfirmed = actions::deleteServerData)
        }
        state.facts.dataNotice?.let {
            CdText(stringResource(S5Presenter.dataNotice(it)), CdTheme.type.titleS)
        }
    }
}

@Composable
private fun DemoPage(facts: SettingsFacts, actions: SettingsActions, advanced: Boolean, onStarted: () -> Unit) {
    var realSignals by rememberSaveable { mutableStateOf(false) }
    TitleHeader(stringResource(R.string.s5_demo))
    PageBody {
        CdText(stringResource(R.string.demo_body), CdTheme.type.bodyL, color = CdTheme.colors.text2)
        if (facts.demo.running) {
            // 돌고 있는 시연을 멈출 수 없으면 강제 종료밖에 없다. 다른 시나리오 버튼은 눌러도 아무 일이 없으니 보이지 않는다
            CdText(stringResource(R.string.demo_running), CdTheme.type.titleM)
            PrimaryButton(
                stringResource(R.string.demo_restart),
                onClick = {
                    actions.restartDemo()
                    onStarted()
                },
                Modifier.fillMaxWidth(),
            )
            SecondaryButton(stringResource(R.string.demo_stop), onClick = actions::stopDemo, Modifier.fillMaxWidth())
        } else {
            DemoScenario.entries.forEach { scenario ->
                PrimaryButton(
                    stringResource(scenario.label),
                    onClick = {
                        actions.startDemo(scenario.id, realSignals)
                        onStarted()
                    },
                    Modifier.fillMaxWidth(),
                )
            }
            // 일반 사용자가 켜고 출발하면 실제 운전 측정이 멈춘 채 가짜 안내가 나온다. 숨김 설정 뒤에만 둔다
            if (advanced && BuildConfig.REAL_SIGNAL_DEMO) {
                CdCheckbox(stringResource(R.string.demo_real_signals), realSignals, onChange = { realSignals = it })
            }
        }
        facts.demo.pendingPlace?.let { place ->
            Spacer(Modifier.height(CdDimens.Space8))
            CdText(stringResource(R.string.demo_send_note, place), CdTheme.type.bodyL, color = CdTheme.colors.text2)
            SecondaryButton(stringResource(R.string.demo_send_place), onClick = actions::sendDemoPlace, Modifier.fillMaxWidth())
            if (facts.demo.sendOffline) CdText(stringResource(R.string.demo_send_offline), CdTheme.type.bodyL, color = CdTheme.colors.text2)
        }
    }
}

@Composable
private fun ServerPage(draft: String, actions: SettingsActions, onDraft: (String) -> Unit) {
    val c = CdTheme.colors
    var result by remember { mutableStateOf<Int?>(null) }
    TitleHeader(stringResource(R.string.s5_server))
    PageBody {
        CdText(stringResource(R.string.server_body), CdTheme.type.bodyL, color = c.text2)
        BasicTextField(
            value = draft,
            onValueChange = {
                result = null
                onDraft(it)
            },
            singleLine = true,
            textStyle = CdTheme.type.bodyL.copy(color = c.text1),
            cursorBrush = SolidColor(c.text1),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = CdDimens.SecondaryButton)
                .background(c.surface2)
                .border(CdDimens.Line, c.text3)
                .padding(CdDimens.Space16),
        )
        SecondaryButton(
            stringResource(R.string.server_save),
            onClick = {
                val ok = ServerUrlRule.accepts(draft)
                if (ok) actions.setServerUrl(draft.trim())
                result = if (ok) R.string.server_saved else R.string.server_rejected
            },
        )
        result?.let { CdText(stringResource(it), CdTheme.type.bodyL, color = c.text2) }
    }
}
