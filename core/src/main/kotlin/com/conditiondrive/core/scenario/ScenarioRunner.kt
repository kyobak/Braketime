package com.conditiondrive.core.scenario

import com.conditiondrive.core.engine.AskCheckin
import com.conditiondrive.core.engine.AskStop
import com.conditiondrive.core.engine.CheckinHeard
import com.conditiondrive.core.engine.ConditionInput
import com.conditiondrive.core.engine.ConditionReceived
import com.conditiondrive.core.engine.Effect
import com.conditiondrive.core.engine.Engine
import com.conditiondrive.core.engine.EngineEvent
import com.conditiondrive.core.engine.EngineState
import com.conditiondrive.core.engine.EngineStateCodec
import com.conditiondrive.core.engine.GapEvidence
import com.conditiondrive.core.engine.GlucoseHoldReleased
import com.conditiondrive.core.engine.Gear
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.core.engine.OfferVentilation
import com.conditiondrive.core.engine.ProcessRestored
import com.conditiondrive.core.engine.PromptHeard
import com.conditiondrive.core.engine.RouteChanged
import com.conditiondrive.core.engine.Say
import com.conditiondrive.core.engine.StartGuidance
import com.conditiondrive.core.engine.Tick
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.engine.VehicleChanged
import com.conditiondrive.core.engine.WaypointArrived
import com.conditiondrive.core.voice.VoiceClassifier
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.core.voice.VoiceVars
import kotlinx.serialization.json.Json
import java.time.OffsetDateTime
import kotlin.random.Random

object ScenarioRunner {
    private val json = Json { ignoreUnknownKeys = false }

    fun load(id: String): Scenario {
        val stream = ScenarioRunner::class.java.getResourceAsStream("/scenarios/$id.json")
            ?: throw IllegalArgumentException("시나리오 없음: $id")
        return stream.use { json.decodeFromString(Scenario.serializer(), it.readBytes().decodeToString()) }
    }

    fun parse(text: String): Scenario = json.decodeFromString(Scenario.serializer(), text)

    fun run(scenario: Scenario, engine: Engine = Engine()): RunResult =
        Execution(scenario, engine, tickEveryMs = { null }, roundTrip = false).run()

    /** 배속은 틱 간격이다. 어느 배속이든 마감에 착지하므로 전이 로그가 같아야 한다(G5). */
    fun runWithSpeed(scenario: Scenario, profile: SpeedProfile, seed: Long = 0): RunResult {
        val random = Random(seed)
        val step: () -> Long? = when (profile) {
            SpeedProfile.REAL_TIME -> ({ 1_000L })
            SpeedProfile.X60 -> ({ 60_000L })
            SpeedProfile.X240 -> ({ 240_000L })
            SpeedProfile.RANDOM -> ({ random.nextLong(1, 300_001) })
        }
        return Execution(scenario, Engine(), step, roundTrip = false).run()
    }

    /** G7. 모든 줄에서 저장했다 되읽어도 진행이 같아야 한다. */
    internal fun runWithCodecRoundTrip(scenario: Scenario, engine: Engine = Engine()): RunResult =
        Execution(scenario, engine, tickEveryMs = { null }, roundTrip = true).run()
}

internal fun parseOffsetMs(t: String): Long {
    val negative = t.startsWith("-")
    val parts = t.removePrefix("-").split(":").map { it.toLong() }
    require(parts.size == 3) { "시각 형식 오류: $t" }
    val ms = (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
    return if (negative) -ms else ms
}

internal fun scenarioBaseMs(scenario: Scenario): Long =
    OffsetDateTime.parse(scenario.startLocal).toInstant().toEpochMilli()

private class Execution(
    private val scenario: Scenario,
    private val engine: Engine,
    private val tickEveryMs: () -> Long?,
    private val roundTrip: Boolean,
) {
    private val base = scenarioBaseMs(scenario)
    private val entries = mutableListOf<TransitionEntry>()
    private val failures = mutableListOf<String>()
    private val stepEffects = mutableListOf<Effect>()
    private val stepEvents = mutableListOf<com.conditiondrive.core.engine.LogEvent>()

    private var now = base + parseOffsetMs(scenario.steps.first().t)
    private var state: EngineState = engine.initial(now)
    private var killed: String? = null

    private var gear = Gear.UNKNOWN
    private var speed: Double? = null
    private var ignition = Ignition.OFF

    // 혈당 유지 입력(9.4). 명시한 줄이 오면 그 값으로 바뀌고, 끊김 표시 뒤에는 멈춘다.
    private var lastSummary: ConditionInput? = null
    private var lastGlucoseTtlMs = DEFAULT_TTL_MS
    private var keepAliveAt: Long? = null

    fun run(): RunResult {
        for (step in scenario.steps) {
            val target = base + parseOffsetMs(step.t)
            if (target < now) {
                failures += "${step.t}: 스텝 시각이 뒤로 간다"
                continue
            }
            advanceTo(target)
            applyStep(step, target)
            verify(step)
            stepEffects.clear()
            stepEvents.clear()
            if (roundTrip && killed == null) {
                val restored = EngineStateCodec.decode(EngineStateCodec.encode(state))
                if (restored != state) failures += "${step.t}: 코덱 왕복 결과가 다르다"
                state = restored ?: state
            }
        }
        return RunResult(entries.toList(), failures.toList())
    }

    private fun apply(event: EngineEvent) {
        val decision = engine.reduce(state, event)
        state = decision.state
        stepEffects += decision.effects
        decision.log?.let { entries += it }
        decision.log?.event?.let { stepEvents += it }
    }

    /** 다음 스텝 직전까지 마감과 유지 입력에 착지한다. 같은 시각의 스텝 입력은 마감보다 먼저다(3.4). */
    private fun advanceTo(target: Long) {
        if (killed != null) {
            now = target
            return
        }
        var guard = 0
        while (now < target) {
            check(++guard < MAX_LANDINGS) { "${scenario.id}: 마감이 풀리지 않는다 at=$now" }
            val deadline = engine.nextDeadlineMs(state)
            check(deadline == null || deadline > now) { "${scenario.id}: 지난 마감이 남았다 at=$now deadline=$deadline" }
            val keepAlive = keepAliveAt
            val tick = tickEveryMs()?.let { now + it }
            val next = listOfNotNull(deadline, keepAlive, tick, target).min()
            if (next >= target) break
            now = next
            if (keepAlive != null && keepAlive == next) injectKeepAlive()
            apply(Tick(now))
        }
        now = target
    }

    private fun injectKeepAlive() {
        val last = lastSummary ?: return
        if (ignition == Ignition.OFF) {
            // 시동이 꺼지면 폴링도 멈춘다(V16). 켜진 뒤 첫 주기에 이어진다.
            keepAliveAt = now + KEEP_ALIVE_MS
            return
        }
        val input = last.copy(glucoseMeasuredAtMs = now, glucoseExpiresAtMs = now + lastGlucoseTtlMs)
        lastSummary = input
        keepAliveAt = now + KEEP_ALIVE_MS
        apply(ConditionReceived(now, input))
    }

    private fun applyStep(step: ScenarioStep, at: Long) {
        val input = step.input
        var explicitSummary = false
        if (input != null) {
            if (input.killProcess == true) {
                // 표의 종료 시각까지 저장됐다고 본다. 실제 저장 주기(C-38) 오차는 시나리오 밖이다.
                apply(Tick(at))
                killed = EngineStateCodec.encode(state)
                return
            }
            input.restoreGap?.let { restore(it, at) }
            input.routeRemainingSec?.let { apply(RouteChanged(at, it)) }
            // 차량 입력이 요약보다 먼저다. 실제로도 시동이 걸린 뒤에 요약을 받고, D20은 V14 다음 V15를 기대한다.
            if (input.ignition != null || input.gear != null || input.speedKmh != null) {
                input.ignition?.let { ignition = it }
                input.gear?.let { gear = it }
                input.speedKmh?.let { speed = it }
                apply(VehicleChanged(at, gear, speed, ignition))
            }
            if (input.unpaired == true) {
                apply(ConditionReceived(at, null, unpaired = true))
                lastSummary = null
                keepAliveAt = null
            }
            if (input.summaryNull == true) {
                apply(ConditionReceived(at, null))
                lastSummary = null
                keepAliveAt = null
            }
            input.summary?.let {
                applySummary(it, at)
                explicitSummary = true
            }
            if (input.glucoseCut == true) keepAliveAt = null
            if (input.glucoseHoldRelease == true) apply(GlucoseHoldReleased(at))
            input.checkinText?.let {
                apply(CheckinHeard(at, state.activePromptId ?: NO_PROMPT, VoiceClassifier.checkin(it)))
            }
            input.promptText?.let {
                apply(PromptHeard(at, state.activePromptId ?: NO_PROMPT, VoiceClassifier.stopPrompt(it)))
            }
            if (input.waypointArrived == true) apply(WaypointArrived(at))
        }
        if (killed != null) return
        if (!explicitSummary && keepAliveAt == at) injectKeepAlive()
        apply(Tick(at))
    }

    private fun restore(gap: GapEvidence, at: Long) {
        val saved = killed
        if (saved == null) {
            failures += "복원 입력인데 종료된 적이 없다"
            return
        }
        val decoded = EngineStateCodec.decode(saved)
        if (decoded == null) {
            failures += "저장 상태를 읽지 못했다"
            return
        }
        state = decoded
        killed = null
        apply(ProcessRestored(at, gap))
    }

    private fun applySummary(summary: ScenarioSummary, at: Long) {
        val previous = lastSummary
        val measured = summary.measuredAt?.let { base + parseOffsetMs(it) } ?: at
        val ttlMs = summary.ttlSec?.let { it * 1000 } ?: DEFAULT_TTL_MS
        val allowed = summary.source == null || summary.source in ALLOWED_SOURCES

        val glucosePart: ConditionInput? = when {
            summary.glucoseMmol == null -> previous
            !allowed -> null
            else -> ConditionInput(
                sleepMinutes = null,
                sleepExpiresAtMs = null,
                glucoseMmol = summary.glucoseMmol,
                glucoseTrend = summary.trend,
                glucoseMeasuredAtMs = measured,
                glucoseExpiresAtMs = measured + ttlMs,
                glucoseSimulated = (summary.source ?: "simulated") == "simulated",
            )
        }
        val sleepMinutes = summary.sleepMinutes ?: previous?.sleepMinutes
        val sleepExpires = summary.sleepMinutes
            ?.let { at + (summary.sleepTtlSec ?: DEFAULT_SLEEP_TTL_SEC) * 1000 }
            ?: previous?.sleepExpiresAtMs
        val merged = ConditionInput(
            sleepMinutes = sleepMinutes,
            sleepExpiresAtMs = sleepExpires,
            glucoseMmol = glucosePart?.glucoseMmol,
            glucoseTrend = glucosePart?.glucoseTrend,
            glucoseMeasuredAtMs = glucosePart?.glucoseMeasuredAtMs,
            glucoseExpiresAtMs = glucosePart?.glucoseExpiresAtMs,
            glucoseSimulated = glucosePart?.glucoseSimulated ?: false,
        )
        lastSummary = merged
        if (summary.glucoseMmol != null) lastGlucoseTtlMs = ttlMs
        keepAliveAt = if (scenario.keepGlucoseAlive && merged.glucoseMmol != null) at + KEEP_ALIVE_MS else keepAliveAt
        apply(ConditionReceived(at, merged))
    }

    private fun verify(step: ScenarioStep) {
        val t = step.t
        val expect = step.expect
        val spoken = stepEffects.mapNotNull(::spokenVoice)
        val expectedVoices = expect?.voices ?: emptyList()
        if (spoken != expectedVoices) failures += "$t: 음성 기대 $expectedVoices, 실제 $spoken"
        // 기록 줄의 사건도 음성처럼 생략하면 없어야 한다. 엔진이 E34를 거른 줄은 기록이 없어야 한다
        val expectedEvents = expect?.events ?: emptyList()
        if (stepEvents != expectedEvents) failures += "$t: 기록 사건 기대 $expectedEvents, 실제 $stepEvents"
        if (expect == null) return
        if (killed != null) return

        val view = engine.view(state, now)
        fun <T> compare(name: String, wanted: T?, actual: T) {
            if (wanted != null && wanted != actual) failures += "$t: $name 기대 $wanted, 실제 $actual"
        }
        compare("상태", expect.state, view.state)
        compare("사유", expect.reasons, view.reasons)
        compare("cds", expect.cdsSec, view.continuousDriveSec)
        compare("ignored", expect.ignored, view.ignoredCount)
        compare("hold", expect.hold, view.preDepartureHold)
        compare("advanced", expect.advanced, view.advanced)
        compare("휴식", expect.restElapsedSec, view.restElapsedSec)
        compare("타이머", expect.timerRunning, view.glucoseRecoveryRemainingSec != null)
        compare("주의 임계", expect.cautionSec, view.cautionThresholdSec)
        compare("정차 임계", expect.stopSec, view.stopThresholdSec)
        compare("streak", expect.streak, state.tiredStreak)
        compare("환기 제안", expect.ventilationOffer, stepEffects.any { it is OfferVentilation })
        compare("보류 풀기 가능", expect.holdReleasable, view.glucoseHoldReleasableAtMs?.let { it <= now } == true)

        expect.guidanceAuto?.let { wanted ->
            val guidance = stepEffects.filterIsInstance<StartGuidance>()
            if (guidance.none { it.auto == wanted }) failures += "$t: 안내 시작(auto=$wanted) 없음, 실제 $guidance"
        }
        expect.voiceVars?.forEach { wanted -> checkVars(t, wanted) }
    }

    private fun checkVars(t: String, wanted: ScenarioVoiceVars) {
        val vars = stepEffects.firstNotNullOfOrNull { varsOf(it, wanted.voice) }
        if (vars == null) {
            failures += "$t: ${wanted.voice} 변수를 볼 음성이 없다"
            return
        }
        fun <T> compare(name: String, expected: T?, actual: T?) {
            if (expected != null && expected != actual) failures += "$t: ${wanted.voice}.$name 기대 $expected, 실제 $actual"
        }
        compare("driveSec", wanted.driveSec, vars.driveSec)
        compare("remainingSec", wanted.remainingSec, vars.remainingSec)
        compare("routeMinutes", wanted.routeMinutes, vars.routeMinutes)
        compare("advance", wanted.advance, vars.advance)
        if (wanted.noAdvance == true && vars.advance != null) failures += "$t: ${wanted.voice}에 앞당김 문장이 붙었다"
    }

    private fun spokenVoice(effect: Effect): VoiceId? = when (effect) {
        is Say -> effect.voice
        is AskCheckin -> effect.voice
        is AskStop -> effect.voice
        else -> null
    }

    private fun varsOf(effect: Effect, voice: VoiceId): VoiceVars? = when {
        effect is Say && effect.voice == voice -> effect.vars
        effect is AskCheckin && effect.voice == voice -> effect.vars
        effect is AskStop && effect.voice == voice -> effect.vars
        else -> null
    }

    private companion object {
        const val KEEP_ALIVE_MS = 5 * 60 * 1000L
        const val DEFAULT_TTL_MS = 900 * 1000L
        const val DEFAULT_SLEEP_TTL_SEC = 86_400L
        const val MAX_LANDINGS = 2_000_000
        const val NO_PROMPT = -1L
        val ALLOWED_SOURCES = setOf("simulated", "partner_realtime")
    }
}

object ScenarioCompare {
    private const val TOLERANCE_MS = 1_000L

    /** 실제 전이 로그와 시나리오 기대를 시뮬레이션 시각 ±1초로 대조. 시연 후 리포트 생성 */
    fun compare(scenario: Scenario, log: List<TransitionEntry>): List<String> {
        val base = scenarioBaseMs(scenario)
        val failures = mutableListOf<String>()
        val sorted = log.sortedBy { it.atMs }

        val actual = sorted.flatMap { entry -> entry.voices.map { VoiceAt(entry.atMs, it) } }.toMutableList()
        for (step in scenario.steps) {
            val at = base + parseOffsetMs(step.t)
            for (voice in step.expect?.voices.orEmpty()) {
                val index = actual.indexOfFirst { it.voice == voice && kotlin.math.abs(it.atMs - at) <= TOLERANCE_MS }
                if (index < 0) failures += "${step.t}: $voice 없음" else actual.removeAt(index)
            }
        }
        actual.forEach { failures += "${clock(it.atMs - base)}: 기대에 없는 음성 ${it.voice}" }

        for (step in scenario.steps) {
            val at = base + parseOffsetMs(step.t)
            val wanted = step.expect?.state ?: continue
            // 전이 시각이 ±1초 흔들려도 통과하도록 구간 양 끝과 가운데 상태를 모두 본다
            val candidates = (-TOLERANCE_MS..TOLERANCE_MS step TOLERANCE_MS).map { stateAt(sorted, at + it) }
            if (wanted !in candidates) failures += "${step.t}: 상태 기대 $wanted, 실제 ${candidates[1]}"
        }
        return failures
    }

    /** docs/evidence/에 둘 대조 리포트 본문. */
    fun report(scenario: Scenario, log: List<TransitionEntry>): String {
        val base = scenarioBaseMs(scenario)
        val failures = compare(scenario, log)
        return buildString {
            appendLine("# ${scenario.id} ${scenario.title}")
            appendLine()
            appendLine("대조 결과: ${if (failures.isEmpty()) "일치" else "불일치 ${failures.size}건"} (시뮬레이션 시각 ±1초)")
            failures.forEach { appendLine("- $it") }
            appendLine()
            appendLine("| 시각 | 전 | 후 | 사유 | 음성 |")
            appendLine("| --- | --- | --- | --- | --- |")
            log.forEach {
                appendLine("| ${clock(it.atMs - base)} | ${it.from} | ${it.to} | ${it.reasons.joinToString()} | ${it.voices.joinToString()} |")
            }
        }
    }

    private data class VoiceAt(val atMs: Long, val voice: VoiceId)

    private fun stateAt(log: List<TransitionEntry>, at: Long) =
        log.lastOrNull { it.atMs <= at }?.to ?: com.conditiondrive.core.engine.DriverState.NORMAL

    private fun clock(offsetMs: Long): String {
        val total = kotlin.math.abs(offsetMs) / 1000
        val sign = if (offsetMs < 0) "-" else ""
        return "%s%02d:%02d:%02d".format(sign, total / 3600, total / 60 % 60, total % 60)
    }
}
