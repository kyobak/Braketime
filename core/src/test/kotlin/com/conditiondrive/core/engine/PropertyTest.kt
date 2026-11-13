package com.conditiondrive.core.engine

import com.conditiondrive.core.voice.VoiceId
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** test-scenarios.md G6, G8, G9, G10, G11. */
class PropertyTest {
    private val base = 1_795_000_000_000L
    private val engine = Engine()

    private fun Random.gear() = listOf(Gear.PARK, Gear.DRIVE, Gear.DRIVE, Gear.REVERSE, Gear.NEUTRAL, Gear.UNKNOWN).random(this)

    private fun randomEvent(random: Random, state: EngineState, at: Long): EngineEvent = when (random.nextInt(11)) {
        0, 1, 2 -> Tick(at)
        3, 4 -> VehicleChanged(
            at,
            random.gear(),
            listOf(0.0, 5.0, 40.0, 80.0, null).random(random),
            listOf(Ignition.ON, Ignition.ON, Ignition.ON, Ignition.OFF, Ignition.UNKNOWN).random(random),
        )
        5, 6 -> ConditionReceived(
            at,
            if (random.nextInt(6) == 0) {
                null
            } else {
                ConditionInput(
                    listOf(null, 290, 400).random(random),
                    at + 3_600_000,
                    listOf(null, 3.2, 3.9, 4.0, 4.6, 5.0, 5.2, 5.6, 6.5).random(random),
                    listOf(null, *Trend.entries.toTypedArray()).random(random),
                    at,
                    at + listOf(60_000L, 900_000L).random(random),
                    true,
                )
            },
            unpaired = random.nextInt(50) == 0,
        )
        7 -> RouteChanged(at, listOf(null, 300, 3000).random(random))
        8 -> CheckinHeard(at, state.activePromptId ?: -1, CheckinAnswer.entries.random(random))
        // S5 보류 풀기는 드물다. 주행 중에 와도 엔진이 받지 않는지 함께 본다
        10 -> if (random.nextInt(4) == 0) GlucoseHoldReleased(at) else Tick(at)
        else -> if (random.nextBoolean()) {
            PromptHeard(at, state.activePromptId ?: -1, PromptAnswer.entries.random(random))
        } else {
            WaypointArrived(at)
        }
    }

    private fun walk(seed: Int, steps: Int = 1500, onStep: (EngineState, EngineEvent, Decision) -> Unit) {
        val random = Random(seed)
        var state = engine.initial(base)
        var now = base
        repeat(steps) {
            now += random.nextLong(1, 400_000)
            // 착지한 경우와 건너뛴 경우를 함께 시험한다
            val deadline = engine.nextDeadlineMs(state)
            if (deadline != null && deadline in (state.lastEventMs + 1)..now && random.nextBoolean()) now = deadline
            val event = randomEvent(random, state, now)
            val decision = engine.reduce(state, event)
            onStep(state, event, decision)
            state = decision.state
        }
    }

    private fun voicesOf(decision: Decision) = decision.effects.mapNotNull {
        when (it) {
            is Say -> it.voice
            is AskCheckin -> it.voice
            is AskStop -> it.voice
            else -> null
        }
    }

    /** 값을 받은 적이 있는데 지금 유효한 값이 없다(D-31). 만료 처리를 기다리는 값도 무효다 */
    private fun EngineState.glucoseUnconfirmed(at: Long): Boolean =
        glucoseLastValidMs != null && (glucoseExpiresAtMs?.let { at >= it } ?: true)

    @Test
    fun g8NoRecoveryVoiceWhileGlucoseStopOrLatched() {
        for (seed in 0 until 40) walk(seed) { _, event, decision ->
            val after = decision.state
            val voices = voicesOf(decision)
            if (after.glucose == Level.STOP || after.glucoseLatched) {
                assertFalse(VoiceId.V12 in voices, "seed=$seed $voices")
                assertFalse(VoiceId.V16 in voices, "seed=$seed $voices")
                assertFalse(VoiceId.V20 in voices, "seed=$seed $voices")
            }
            // 값을 받은 뒤 끊김(D-31). V16은 40분 내내 값이 유효했을 때만 나오고 그 뒤 같은 틱에 끊길 수 있어 뺀다
            if (after.glucoseUnconfirmed(event.atMs)) {
                assertFalse(VoiceId.V12 in voices, "seed=$seed $voices")
                assertFalse(VoiceId.V20 in voices, "seed=$seed $voices")
            }
        }
    }

    /** E34는 주행 중에 받지 않는다. 받았으면 래치가 풀리고 기록 줄이 남는다 */
    @Test
    fun holdReleaseNeverActsWhileDriving() {
        for (seed in 0 until 40) walk(seed) { before, event, decision ->
            if (event !is GlucoseHoldReleased) return@walk
            val released = decision.log?.event == LogEvent.GLUCOSE_HOLD_RELEASED
            if (before.isDriving()) assertFalse(released, "seed=$seed 주행 중에 풀렸다")
            // 실제 낮은 값에서 온 보류는 마지막 판독에서 40분 안에 풀리지 않는다(D-33)
            val fromStop = before.glucose == Level.STOP || (before.glucoseLatched && !before.glucoseLatchFromCaution)
            val last = before.glucoseLastValidMs
            if (fromStop && last != null && event.atMs - last < Policy.DEFAULT.GLUCOSE_WAIT_SEC * 1000) {
                assertFalse(released, "seed=$seed STOP 보류가 40분 안에 풀렸다")
            }
            if (released) {
                assertFalse(decision.state.glucoseLatched, "seed=$seed")
                assertEquals(null, decision.state.glucoseLastValidMs, "seed=$seed")
            }
        }
    }

    @Test
    fun g9NoMicrophoneWhileLowAndDriving() {
        for (seed in 0 until 40) walk(seed) { _, _, decision ->
            val after = decision.state
            if (after.isDriving() && after.isGlucoseLow(Policy.DEFAULT)) {
                val asks = decision.effects.filter { it is AskCheckin || it is AskStop }
                assertTrue(asks.isEmpty(), "seed=$seed $asks")
                assertEquals(null, after.activePromptId, "seed=$seed 열린 마이크가 남았다")
            }
        }
    }

    @Test
    fun deadlineNeverStaysInThePast() {
        for (seed in 0 until 20) walk(seed) { _, event, decision ->
            val deadline = engine.nextDeadlineMs(decision.state)
            if (deadline != null) assertTrue(deadline > event.atMs, "seed=$seed $event $deadline")
        }
    }

    @Test
    fun stateNeverLeavesFormula() {
        for (seed in 0 until 20) walk(seed) { _, _, decision ->
            val s = decision.state
            if (s.glucoseLatched) assertEquals(Level.NONE, s.glucose, "seed=$seed 래치는 G=NONE으로만 존재한다")
            if (s.glucoseLatchFromCaution) assertTrue(s.glucoseLatched, "seed=$seed 래치 출처는 래치 중에만 있다")
            assertTrue(s.driveMs >= 0)
        }
    }

    @Test
    fun g6BackwardClockAddsNothing() {
        for (seed in 0 until 20) {
            val random = Random(seed)
            var state = engine.initial(base)
            state = engine.reduce(state, VehicleChanged(base, Gear.DRIVE, 80.0, Ignition.ON)).state
            state = engine.reduce(state, Tick(base + 3_600_000)).state
            repeat(200) {
                val at = state.lastEventMs - random.nextLong(1, 1_000_000)
                val before = state.driveMs
                val rest = state.restStartMs?.let { start -> state.lastEventMs - start }
                val decision = engine.reduce(state, randomEvent(random, state, at))
                state = decision.state
                assertTrue(state.driveMs <= before, "cds가 늘었다 $before -> ${state.driveMs}")
                val restAfter = state.restStartMs?.let { start -> at - start }
                if (rest != null && restAfter != null) assertTrue(restAfter <= rest, "휴식이 늘었다")
            }
        }
    }

    @Test
    fun g6BackwardThenForwardOnlyCountsRealGap() {
        var state = engine.initial(base)
        state = engine.reduce(state, VehicleChanged(base, Gear.DRIVE, 80.0, Ignition.ON)).state
        state = engine.reduce(state, Tick(base + 1_000_000)).state
        state = engine.reduce(state, Tick(base + 500_000)).state
        assertEquals(1_000_000, state.driveMs)
        assertEquals(base + 500_000, state.lastEventMs)
        state = engine.reduce(state, Tick(base + 510_000)).state
        assertEquals(1_010_000, state.driveMs)
    }

    @Test
    fun g10StalePromptIgnored() {
        val d = Driver()
        d.startDriving()
        d.advance(6300)
        d.answerCheckin(CheckinAnswer.GOOD)
        d.advance(900)
        val active = d.state.activePromptId!!
        d.answerPrompt(PromptAnswer.NO_SPEECH)
        d.advance(600)
        val before = d.state
        d.send(PromptHeard(d.now, active, PromptAnswer.ACCEPT))
        assertEquals(before, d.state.copy(lastEventMs = before.lastEventMs))
        assertFalse(d.effects.any { it is StartGuidance })
    }

    @Test
    fun g10StaleCheckinIgnored() {
        val d = Driver()
        d.park()
        d.advance(5)
        val old = d.state.activePromptId!!
        d.answerCheckin(CheckinAnswer.UNCLASSIFIED)
        d.send(CheckinHeard(d.now, old, CheckinAnswer.TIRED))
        assertEquals(Level.NONE, d.state.checkin)
    }

    @Test
    fun answerToWrongPromptKindIgnored() {
        val d = Driver()
        d.park()
        d.advance(5)
        d.send(PromptHeard(d.now, d.state.activePromptId!!, PromptAnswer.ACCEPT))
        assertTrue(d.effects.none { it is StartGuidance })
    }

    @Test
    fun g11NoForbiddenReferenceInCore() {
        val forbidden = listOf(
            Regex("""^\s*import\s+androidx?\."""),
            Regex("""System\.currentTimeMillis"""),
            Regex("""Clock\.systemUTC"""),
            Regex("""(?i)demo"""),
        )
        val root = File("src/main/kotlin/com/conditiondrive/core")
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.any { it.name == "Engine.kt" }, "소스를 찾지 못했다: ${root.absolutePath}")
        val hits = sources.flatMap { file ->
            file.readLines().filter { line -> forbidden.any { it.containsMatchIn(line) } }.map { "${file.name}: $it" }
        }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun g11EngineDoesNotImportContract() {
        val hits = File("src/main/kotlin/com/conditiondrive/core/engine").walkTopDown()
            .filter { it.extension == "kt" }
            .flatMap { file -> file.readLines().filter { "core.contract" in it }.asSequence() }
            .toList()
        assertEquals(emptyList(), hits)
    }
}
