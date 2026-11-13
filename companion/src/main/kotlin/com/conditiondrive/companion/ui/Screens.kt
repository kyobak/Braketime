package com.conditiondrive.companion.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import com.conditiondrive.companion.AppUi
import com.conditiondrive.companion.CompanionController
import com.conditiondrive.companion.pairing.ClaimResult
import com.conditiondrive.companion.pairing.PairingCode
import com.conditiondrive.companion.simulator.GlucoseUnit
import com.conditiondrive.companion.simulator.SimScenarios
import com.conditiondrive.companion.sleep.HealthAvailability
import com.conditiondrive.companion.sleep.HealthConnectGateway
import java.time.ZoneId
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import java.util.Locale
import kotlinx.coroutines.delay

/** 8.3 C1 → C2 → C3·C4. 저장된 상태만으로 화면이 정해져, 재시작해도 건너뛸 수 없다 */
@Composable
fun CompanionRoot(controller: CompanionController, ui: AppUi) {
    val colors = CdTheme.colors
    Box(Modifier.fillMaxSize().background(colors.bg).systemBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize()) {
            ui.notice?.let { NoticeBar(Copy.notice(it), controller::dismissNotice) }
            when {
                !ui.loaded -> Unit
                !ui.consented -> ConsentFlow(controller)
                !ui.paired -> PairScreen(controller, ui)
                ui.pair.prefilledCode != null -> PairScreen(controller, ui, replacing = true)
                else -> MainShell(controller, ui)
            }
        }
    }
}

@Composable
private fun NoticeBar(text: String, onClose: () -> Unit) {
    val colors = CdTheme.colors
    // 철회·삭제 결과는 꼭 읽어야 하는 문장이라 본문 크기로 쓰고, 결과를 말하는 첫 문장만 굵게 한다
    val (head, rest) = Copy.noticeParts(text)
    val line = buildAnnotatedString {
        withStyle(SpanStyle(fontWeight = FontWeight.W500)) { append(head) }
        if (rest != null) append(" $rest")
    }
    Column(Modifier.fillMaxWidth().background(colors.surface1)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = CdSize.gutter, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CdText(line, CdTheme.type.bodyM, colors.text1, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            SecondaryButton(Copy.CLOSE, onClose)
        }
        Hairline()
    }
}

/** 가로처럼 낮은 화면. 위 여백과 제목 아래 간격을 줄여 첫 화면에 내용이 더 들어오게 한다 */
@Composable
private fun isShort(): Boolean =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } < SHORT_HEIGHT

/** 제목 아래 간격. 낮은 화면에서는 절반 */
@Composable
private fun TitleGap() = Spacer(Modifier.height(if (isShort()) 8.dp else 16.dp))

@Composable
private fun ScrollPage(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val top = if (isShort()) 12.dp else 24.dp
    // 가로에서 버튼과 문장이 화면 끝까지 늘어나지 않게 본문 폭을 묶고 가운데 둔다. 바깥 배경은 bg 그대로
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = CdSize.gutter).padding(top = top, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = CdSize.maxContent).fillMaxWidth(), content = content)
    }
}

private val SHORT_HEIGHT = 480.dp

// ---- C1 동의

@Composable
private fun ConsentFlow(controller: CompanionController) {
    var declined by rememberSaveable { mutableStateOf(false) }
    if (declined) DeclinedScreen { declined = false } else ConsentScreen(controller::acceptConsent) { declined = true }
}

/** 체크박스는 비운 채 시작한다(14장). 상태를 저장하지 않아 다시 열면 다시 비어 있다 */
@Composable
fun ConsentContent(content: @Composable ColumnScope.() -> Unit = {}) {
    ScrollPage {
        ScreenTitle(Copy.CONSENT_TITLE)
        TitleGap()
        Copy.CONSENT_ITEMS.forEach { (label, value) ->
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                CdText(label, CdTheme.type.bodyS, CdTheme.colors.text3)
                Spacer(Modifier.height(4.dp))
                // 남는 양과 기간(최신 값 하나만, 이력 없음, 15분, 다음 새벽 4시까지)이 판단의 핵심이라 그 낱말만 굵게 한다
                CdText(emphasize(value, Copy.CONSENT_EMPHASIS), CdTheme.type.bodyL)
            }
            Hairline()
        }
        Spacer(Modifier.height(16.dp))
        CdText(Copy.CONSENT_HEART, CdTheme.type.bodyM, CdTheme.colors.text2)
        // 심박 안내와 법적 고지는 성격이 달라 선으로 나누고, 고지 두 문장도 한 문단으로 붙지 않게 띄운다
        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        CdText(LegalCopy.NOT_MEDICAL, CdTheme.type.bodyM, CdTheme.colors.text2)
        Spacer(Modifier.height(8.dp))
        CdText(LegalCopy.NOT_OFFICIAL, CdTheme.type.bodyM, CdTheme.colors.text2)
        content()
    }
}

@Composable
private fun ConsentScreen(onAccept: () -> Unit, onDecline: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    var missing by remember { mutableStateOf(false) }
    ConsentContent {
        Spacer(Modifier.height(16.dp))
        CdCheckbox(checked, Copy.CONSENT_CHECK) {
            checked = it
            missing = false
        }
        if (missing) CdText(Copy.CONSENT_NEED_CHECK, CdTheme.type.bodyM)
        Spacer(Modifier.height(16.dp))
        // 비활성 회색 버튼을 쓰지 않는다(토큰 2.3). 누르면 무엇이 빠졌는지 문장으로 알려 준다
        PrimaryButton(Copy.CONSENT_ACCEPT, { if (checked) onAccept() else missing = true })
        Spacer(Modifier.height(16.dp))
        // 민감정보 동의는 거부도 똑같이 쉬워야 한다. 높이와 글자는 주 버튼과 같고 위계는 면 채움으로만 나눈다
        SecondaryButton(Copy.CONSENT_DECLINE, onDecline, Modifier.fillMaxWidth(), large = true)
    }
}

@Composable
private fun DeclinedScreen(onBack: () -> Unit) {
    // 화면 안 상태로 바꾼 하위 화면이라 시스템 뒤로 가기를 직접 받지 않으면 앱이 닫힌다. 버튼과 같은 곳(동의 내용)으로 돌아간다
    BackHandler(onBack = onBack)
    ScrollPage {
        ScreenTitle(Copy.DECLINED_TITLE)
        TitleGap()
        CdText(Copy.DECLINED_BODY, CdTheme.type.bodyL, CdTheme.colors.text2)
        Spacer(Modifier.height(24.dp))
        SecondaryButton(Copy.DECLINED_BACK, onBack, Modifier.fillMaxWidth())
    }
}

// ---- C2 연결

@Composable
private fun PairScreen(controller: CompanionController, ui: AppUi, replacing: Boolean = false) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    var titleTaps by remember { mutableIntStateOf(0) }
    val prefilled = ui.pair.prefilledCode
    LaunchedEffect(prefilled) {
        if (prefilled != null) {
            code = prefilled
            scanning = false
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
        cameraDenied = !granted
    }
    // 뒤로 가기는 열린 것부터 닫는다: 카메라 → 연결 바꾸기 취소(지금 연결 유지). 처음 연결 화면 자체는 앱의 첫 화면이라 그대로 닫힌다
    BackHandler(enabled = replacing) { controller.cancelReplace() }
    BackHandler(enabled = scanning) { scanning = false }
    ScrollPage {
        // 8.3 숨김 설정. 연결 전에만 열어, 연결된 뒤에는 토큰이 다른 서버로 가지 않는다
        ScreenTitle(
            if (replacing) Copy.PAIR_REPLACE_TITLE else Copy.PAIR_TITLE,
            Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { titleTaps++ },
        )
        TitleGap()
        CdText(if (replacing) Copy.PAIR_REPLACE_BODY else Copy.PAIR_GUIDE, CdTheme.type.bodyL, CdTheme.colors.text2)
        Spacer(Modifier.height(24.dp))
        if (scanning) {
            LocalQrScanner.current(
                { scanned ->
                    code = scanned
                    scanning = false
                },
                {
                    // 권한은 있어도 쓸 카메라가 없을 때. 거부와 같은 문장으로 코드 입력을 안내한다
                    scanning = false
                    cameraDenied = true
                },
                Modifier.fillMaxWidth().aspectRatio(1f),
            )
            Spacer(Modifier.height(16.dp))
        }
        SecondaryButton(
            if (scanning) Copy.PAIR_SCAN_CLOSE else Copy.PAIR_SCAN,
            {
                when {
                    scanning -> scanning = false
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> scanning = true
                    else -> cameraPermission.launch(Manifest.permission.CAMERA)
                }
            },
            Modifier.fillMaxWidth(),
        )
        if (cameraDenied) {
            Spacer(Modifier.height(8.dp))
            CdText(Copy.PAIR_NO_CAMERA, CdTheme.type.bodyM)
        }
        Spacer(Modifier.height(24.dp))
        CdText(Copy.PAIR_CODE_LABEL, CdTheme.type.bodyS, CdTheme.colors.text3)
        Spacer(Modifier.height(8.dp))
        LineField(
            value = code,
            onChange = {
                // 차량 화면의 코드는 대문자다. 저장값 자체를 바꿔 보이는 값과 보내는 값이 같다(VisualTransformation 미사용)
                code = it.uppercase(Locale.ROOT).take(PairingCode.LENGTH + 4)
                controller.clearPairResult()
            },
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Password,
            placeholder = Copy.PAIR_CODE_HINT,
            tracking = CODE_TRACKING_EM,
        )
        val error = ui.pair.result?.let(Copy::claimResult)
        if (error != null) {
            // 오류가 있으면 "확인한 뒤 연결" 안내를 겹쳐 띄우지 않는다. 고칠 것 하나만 보인다
            Spacer(Modifier.height(8.dp))
            CdText(error, CdTheme.type.bodyM.copy(fontWeight = FontWeight.W500))
        } else if (PairingCode.isValid(PairingCode.normalize(code))) {
            Spacer(Modifier.height(8.dp))
            CdText(Copy.PAIR_CONFIRM, CdTheme.type.bodyM, CdTheme.colors.text2)
        }
        Spacer(Modifier.height(24.dp))
        PrimaryButton(if (ui.pair.busy) Copy.PAIR_BUSY else Copy.PAIR_SUBMIT, { controller.claim(code) })
        if (replacing) {
            Spacer(Modifier.height(16.dp))
            SecondaryButton(Copy.PAIR_REPLACE_CANCEL, controller::cancelReplace, Modifier.fillMaxWidth())
        }
        if (!replacing && titleTaps >= HIDDEN_TAPS) ServerField(controller, ui)
        Spacer(Modifier.height(24.dp))
        CdText(LegalCopy.NOT_OFFICIAL, CdTheme.type.bodyS, CdTheme.colors.text3)
    }
}

@Composable
private fun LineField(
    value: String,
    onChange: (String) -> Unit,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    keyboardType: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    tracking: Float = 0f,
) {
    val colors = CdTheme.colors
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val textStyle = CdTheme.type.titleL.copy(color = colors.text1, letterSpacing = tracking.em)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.text1),
        interactionSource = source,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            autoCorrectEnabled = false,
            keyboardType = keyboardType,
            imeAction = ImeAction.Done,
        ),
        // hairline은 장식 선이라 입력 칸 표시로 약하다(토큰 2.1). 평소 text3 1dp, 입력 중 text1 2dp. 높이는 2dp로 고정해 흔들리지 않는다
        modifier = Modifier.fillMaxWidth().drawBehind {
            val line = (if (focused) 2.dp else CdSize.hairline).toPx()
            drawRect(if (focused) colors.text1 else colors.text3, Offset(0f, size.height - line), Size(size.width, line))
        },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 14.dp)) {
                if (value.isEmpty() && placeholder != null) CdText(placeholder, CdTheme.type.titleL, colors.text3)
                inner()
            }
        },
    )
}

@Composable
private fun ServerField(controller: CompanionController, ui: AppUi) {
    // 저장한 적이 없어도 실제로 쓰는 주소가 있다. 빈 칸으로 열면 어디로 가는지 알 수 없다
    var address by rememberSaveable { mutableStateOf(ui.serverUrl) }
    var saveResult by remember { mutableStateOf<Boolean?>(null) }
    Spacer(Modifier.height(24.dp))
    CdText(Copy.SERVER_LABEL, CdTheme.type.bodyS, CdTheme.colors.text3)
    LineField(address, { address = it; saveResult = null }, keyboardType = KeyboardType.Uri)
    Copy.serverSaveNote(saveResult)?.let { CdText(it, CdTheme.type.bodyM) }
    Spacer(Modifier.height(8.dp))
    SecondaryButton(Copy.SERVER_SAVE, { saveResult = controller.setServerUrl(address) }, Modifier.fillMaxWidth())
}

private const val HIDDEN_TAPS = 5

// ---- 연결 후 탭

@Composable
private fun ColumnScope.MainShell(controller: CompanionController, ui: AppUi) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // 시연 표지와 같은 규칙: 켜져 있는 동안 어느 탭에서도 시뮬레이션임이 보인다. 혈당이 나가는지는 문구가 상태대로 말한다
    Copy.runningMarker(ui.simulator)?.let { SimulationMarker(it) }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        when (tab) {
            0 -> StatusScreen(controller, ui)
            1 -> SimulatorScreen(controller, ui)
            else -> DataScreen(controller, ui)
        }
    }
    Tabs(listOf(Copy.TAB_STATUS, Copy.TAB_SIMULATOR, Copy.TAB_DATA), tab) { tab = it }
}

// ---- C3 상태

@Composable
private fun StatusScreen(controller: CompanionController, ui: AppUi) {
    val zone = ZoneId.systemDefault()
    val context = LocalContext.current
    val permissions = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) {
        controller.syncSleepNow()
    }
    ScrollPage {
        ScreenTitle(Copy.STATUS_TITLE)
        Spacer(Modifier.height(8.dp))
        val sleep = Copy.sleepValue(ui)
        CdRow(Copy.SLEEP_LABEL, sleep.text, Copy.sleepNote(ui), sleep.present) {
            when {
                ui.health.availability == HealthAvailability.NEEDS_PROVIDER_UPDATE ->
                    SecondaryButton(Copy.SLEEP_UPDATE_PROVIDER, {
                        val uri = "market://details?id=${HealthConnectGateway.PROVIDER_PACKAGE}&url=healthconnect%3A%2F%2Fonboarding"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage("com.android.vending"))
                    }, Modifier.fillMaxWidth())
                ui.health.availability == HealthAvailability.AVAILABLE && !ui.health.sleepPermission ->
                    SecondaryButton(Copy.SLEEP_ALLOW, { permissions.launch(HealthConnectGateway.REQUESTED) }, Modifier.fillMaxWidth())
                ui.health.availability == HealthAvailability.AVAILABLE ->
                    SecondaryButton(Copy.SLEEP_SYNC, controller::syncSleepNow, Modifier.fillMaxWidth())
            }
        }
        val heart = Copy.heartValue(ui)
        CdRow(Copy.HEART_LABEL, heart.text, Copy.HEART_NOTE, heart.present)
        val glucose = Copy.glucoseValue(ui.simulator)
        CdRow(Copy.GLUCOSE_LABEL, glucose.text, Copy.glucoseNote(ui.simulator), glucose.present)
        // CGM 알람 고지는 혈당 값 바로 아래에 둔다. 맨 아래 회색 본문이면 당뇨 운전자가 지나치기 쉽다
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().background(CdTheme.colors.surface1).padding(16.dp)) {
            CdText(Copy.NOTICE_ALARM, CdTheme.type.bodyM.copy(fontWeight = FontWeight.W500))
            Spacer(Modifier.height(8.dp))
            CdText(Copy.NOTICE_HIGH, CdTheme.type.bodyM, CdTheme.colors.text2)
        }
        Spacer(Modifier.height(8.dp))
        val upload = Copy.lastUpload(ui, zone)
        CdRow(Copy.UPLOAD_LABEL, upload.text, Copy.uploadNote(ui), upload.present)
        Spacer(Modifier.height(24.dp))
        if (ui.stored.uploadPaused) {
            PrimaryButton(Copy.RESUME, controller::resumeUploads)
        } else {
            SecondaryButton(Copy.PAUSE, controller::pauseUploads, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
        CdText(Copy.SENT_SCOPE, CdTheme.type.bodyM, CdTheme.colors.text2)
    }
}

@Composable
private fun Disclosures() {
    Spacer(Modifier.height(24.dp))
    CdText(Copy.NOTICE_ALARM, CdTheme.type.bodyM, CdTheme.colors.text2)
    Spacer(Modifier.height(8.dp))
    CdText(Copy.NOTICE_HIGH, CdTheme.type.bodyM, CdTheme.colors.text2)
}

// ---- C4 시뮬레이터

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SimulatorScreen(controller: CompanionController, ui: AppUi) {
    val context = LocalContext.current
    val sim = ui.simulator
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // 거절해도 서비스는 돈다. 알림만 안 보인다
        controller.startSimulator()
    }
    ScrollPage {
        ScreenTitle(Copy.SIM_TITLE)
        Spacer(Modifier.height(8.dp))
        CdText(Copy.SIM_HONEST, CdTheme.type.bodyM, CdTheme.colors.text2)
        Spacer(Modifier.height(16.dp))
        // 큰 숫자는 꺼져 있어도 켤 때 보낼 값이라 남긴다. 지금 나가는지는 바로 위 한 줄이 말한다(흐린 색 금지, 토큰 2.3)
        CdText(Copy.simStatus(sim), CdTheme.type.titleS.copy(fontWeight = FontWeight.W600))
        val big = Copy.bigValue(sim)
        Row {
            CdText(big.number, CdTheme.type.display, modifier = Modifier.alignByBaseline())
            Spacer(Modifier.width(8.dp))
            CdText(big.unit, CdTheme.type.titleM, CdTheme.colors.text2, Modifier.alignByBaseline())
            Spacer(Modifier.width(12.dp))
            // 토큰 5장: 수치 바로 옆에 항상 붙인다. 꺼져 있을 때도 뺄 이유가 없다
            CdText(Copy.SIM_ALWAYS, CdTheme.type.bodyL, CdTheme.colors.text2, Modifier.alignByBaseline())
        }
        CdText(big.line, CdTheme.type.bodyM, CdTheme.colors.text2)
        Spacer(Modifier.height(16.dp))
        if (sim.running) {
            SecondaryButton(Copy.SIM_STOP, controller::stopSimulator, Modifier.fillMaxWidth())
        } else {
            PrimaryButton(Copy.SIM_START, {
                val needsAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsAsk) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else controller.startSimulator()
            })
        }

        SectionLabel(Copy.SIM_VALUE)
        // 조금씩 바꾸는 버튼과 바로 넣는 기준 값은 성격이 달라 줄을 나눈다
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val mmol = sim.unit == GlucoseUnit.MMOL
            SecondaryButton(Copy.nudgeLabel(sim, up = false), { if (mmol) controller.nudgeMmol(-10) else controller.nudgeMgdl(-1) })
            SecondaryButton(Copy.nudgeLabel(sim, up = true), { if (mmol) controller.nudgeMmol(10) else controller.nudgeMgdl(1) })
        }
        CdText(Copy.SIM_PRESETS, CdTheme.type.bodyS, CdTheme.colors.text3, Modifier.padding(top = 16.dp, bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Copy.PRESET_HUNDREDTHS.forEach { preset ->
                SecondaryButton(Copy.value(preset / 100.0), { controller.setMmol(preset) }, selected = sim.hundredths == preset)
            }
        }

        SectionLabel(Copy.SIM_UNIT)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(Copy.MMOL, { controller.simulator.setUnit(GlucoseUnit.MMOL) }, selected = sim.unit == GlucoseUnit.MMOL)
            SecondaryButton(Copy.MGDL, { controller.simulator.setUnit(GlucoseUnit.MGDL) }, selected = sim.unit == GlucoseUnit.MGDL)
        }

        SectionLabel(Copy.SIM_TREND)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Copy.TREND_CHOICES.forEach { trend ->
                SecondaryButton(Copy.trend(trend), { controller.setTrend(trend) }, selected = sim.trend == trend)
            }
        }
        Spacer(Modifier.height(16.dp))
        SecondaryButton(
            if (sim.sensorCut) Copy.SIM_CUT_OFF else Copy.SIM_CUT_ON,
            { controller.setSensorCut(!sim.sensorCut) },
            Modifier.fillMaxWidth(),
        )

        SectionLabel(Copy.SIM_SCENARIO)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 재생 중인지와 남은 시간이 먼저 읽히게 진행 줄을 버튼 위에 둔다. tnum이라 초가 바뀌어도 폭이 같다
            Copy.scenarioLine(sim)?.let { line -> CdText(line, CdTheme.type.titleM) }
            SecondaryButton(Copy.SIM_SC1, { controller.playScenario(SimScenarios.SC1) }, Modifier.fillMaxWidth(), selected = sim.scenarioId == SimScenarios.SC1)
            SecondaryButton(Copy.SIM_SC3, { controller.playScenario(SimScenarios.SC3) }, Modifier.fillMaxWidth(), selected = sim.scenarioId == SimScenarios.SC3)
            if (sim.inScenario && sim.scenarioNextInMs != null) SecondaryButton(Copy.SIM_SKIP, controller::skipScenarioPoint, Modifier.fillMaxWidth())
        }

        SectionLabel(Copy.SIM_SLEEP)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Copy.SLEEP_CHOICES.forEach { minutes ->
                SecondaryButton(Copy.sleepChoice(minutes), { controller.setSleepOverride(minutes) }, selected = sim.sleepOverrideMinutes == minutes)
            }
        }
        Disclosures()
    }
}

// ---- 데이터

@Composable
private fun DataScreen(controller: CompanionController, ui: AppUi) {
    var armed by remember { mutableStateOf<String?>(null) }
    // 라이선스는 읽고 닫는 화면이라 탭을 옮기거나 회전해 닫혀도 잃는 것이 없다. armed처럼 이 탭의 임시 상태로 두고 저장하지 않는다
    var license by remember { mutableStateOf(false) }
    // 확인 대기를 오래 두면 나중에 무심코 한 번 눌러 실행될 수 있다. 5초 뒤 풀린다
    LaunchedEffect(armed) {
        if (armed != null) {
            delay(ARM_TIMEOUT_MS)
            armed = null
        }
    }
    if (license) {
        LicenseScreen { license = false }
        return
    }
    // 되돌릴 수 없는 삭제는 두 번 눌러야 실행된다. 대화상자 없이 같은 자리에서 끝낸다. 다른 행을 누르면 앞의 대기는 풀린다
    fun twoStep(key: String, action: () -> Unit) {
        if (armed == key) {
            armed = null
            action()
        } else {
            armed = key
        }
    }
    ScrollPage {
        ScreenTitle(Copy.DATA_TITLE)
        Spacer(Modifier.height(8.dp))
        ActionRow(Copy.DATA_DELETE_SUMMARY, Copy.DATA_DELETE_SUMMARY_NOTE, armed == Copy.DATA_DELETE_SUMMARY, Copy.DATA_CONFIRM) {
            twoStep(Copy.DATA_DELETE_SUMMARY, controller::deleteServerSummary)
        }
        ActionRow(Copy.DATA_UNPAIR, Copy.DATA_UNPAIR_NOTE, armed == Copy.DATA_UNPAIR, Copy.DATA_CONFIRM) {
            twoStep(Copy.DATA_UNPAIR, controller::unpair)
        }
        NavRow(Copy.DATA_LICENSE) {
            armed = null
            license = true
        }
        // 가장 큰 삭제(철회)는 다른 행과 떨어뜨려 맨 아래에 둔다. 선을 하나 더 두면 빈 행처럼 읽혀 여백만 둔다
        Spacer(Modifier.height(24.dp))
        ActionRow(Copy.DATA_WITHDRAW, Copy.DATA_WITHDRAW_NOTE, armed == Copy.DATA_WITHDRAW, Copy.DATA_CONFIRM) {
            twoStep(Copy.DATA_WITHDRAW, controller::withdrawConsent)
        }
        Spacer(Modifier.height(24.dp))
        CdText(Copy.DATA_SOURCE, CdTheme.type.bodyS, CdTheme.colors.text3)
        Spacer(Modifier.height(8.dp))
        CdText(LegalCopy.NOT_MEDICAL, CdTheme.type.bodyS, CdTheme.colors.text3)
        CdText(LegalCopy.NOT_OFFICIAL, CdTheme.type.bodyS, CdTheme.colors.text3)
    }
}

private const val ARM_TIMEOUT_MS = 5_000L

@Composable
private fun LicenseScreen(onClose: () -> Unit) {
    // DataScreen 안의 상태로 연 화면이다. 받지 않으면 시스템 뒤로 가기가 액티비티를 닫아 데이터 탭이 아니라 앱 밖으로 나간다
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val text = remember {
        Copy.reflow(context.assets.open(LICENSE_ASSET).bufferedReader().use { it.readText() })
    }
    ScrollPage {
        ScreenTitle(Copy.DATA_LICENSE)
        Spacer(Modifier.height(4.dp))
        // 어떤 글꼴의 라이선스인지 원문을 읽기 전에 알 수 있게 한다
        CdText(Copy.DATA_LICENSE_NAME, CdTheme.type.bodyM, CdTheme.colors.text2)
        TitleGap()
        SecondaryButton(Copy.CLOSE, onClose, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        CdText(text, CdTheme.type.bodyS, CdTheme.colors.text2)
    }
}

const val LICENSE_ASSET = "licenses/Pretendard-OFL.txt"
