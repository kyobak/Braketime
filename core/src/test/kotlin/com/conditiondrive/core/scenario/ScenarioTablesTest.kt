package com.conditiondrive.core.scenario

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** test-scenarios.md의 A, B, C, D, E 시나리오 표와 SC1~SC3(R1). */
class ScenarioTablesTest {
    private val tableIds = listOf(
        "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A7b", "A7c", "A8", "A9", "A10", "A11", "A12", "A13", "A14",
        "A20", "A21", "A22", "A22b", "A23", "A24", "A25", "A30", "A31", "A32", "A33",
        "B1", "B2", "B3", "B4", "B5", "B6", "B7", "B8", "B9a", "B9b", "B10",
        "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9", "C10", "C11", "C12", "C13",
        "D1", "D2", "D3", "D5", "D6", "D7", "D8", "D9", "D10", "D11", "D12", "D13", "D13b",
        "D14", "D15", "D16", "D17", "D18", "D20", "D21", "D22", "D23", "D24", "D25", "D26", "D27", "D28", "D29", "D30",
        "E2", "E3", "E4", "E5",
        "sc1", "sc2", "sc3",
    )

    @TestFactory
    fun everyTablePasses(): List<DynamicTest> = tableIds.map { id ->
        dynamicTest(id) {
            val result = ScenarioRunner.run(ScenarioRunner.load(id))
            assertEquals(emptyList(), result.failures, id)
        }
    }

    @Test
    fun noUntestedScenarioFile() {
        // Gradle은 모듈 폴더에서 테스트를 돌린다. 클래스패스는 jar일 수 있어 소스 폴더를 본다.
        val files = java.io.File("src/main/resources/scenarios").list().orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .toSet()
        assertEquals(tableIds.toSet(), files)
        assertTrue(files.containsAll(listOf("sc1", "sc2", "sc3")))
    }
}
