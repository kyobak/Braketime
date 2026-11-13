plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/** 소스 한 줄에 금지 패턴이 나타나면 빌드를 깨는 규칙. 설계서 2장 의존 규칙을 사람 대신 지킨다. */
class ArchRule(val name: String, val pattern: Regex, val appliesTo: (String) -> Boolean = { true })

val archRules: Map<String, List<ArchRule>> = mapOf(
    ":core" to listOf(
        ArchRule("Android 금지", Regex("""^\s*import\s+androidx?\.""")),
        ArchRule("Ktor·OkHttp 금지", Regex("""^\s*import\s+(io\.ktor|okhttp3)\.""")),
        ArchRule("벽시계 직접 호출 금지", Regex("""System\.currentTimeMillis|Clock\.systemUTC""")),
        ArchRule("demo 심볼 금지", Regex("""demo""", RegexOption.IGNORE_CASE)),
        ArchRule(
            "engine은 contract를 import하지 않음",
            Regex("""^\s*import\s+com\.conditiondrive\.core\.contract\b"""),
        ) { "/core/engine/" in it },
    ),
    ":vehicle" to listOf(
        ArchRule("Material 컴포넌트 금지", Regex("""^\s*import\s+androidx\.compose\.material3?\.""")),
        ArchRule("다른 앱 모듈 참조 금지", Regex("""^\s*import\s+com\.conditiondrive\.(server|companion)\b""")),
        ArchRule(
            "Pleos SDK·Car API는 vehicle.platform에서만",
            Regex("""^\s*import\s+(ai\.pleos|android\.car)\b"""),
        ) { "/vehicle/platform/" !in it },
    ),
    ":companion" to listOf(
        ArchRule("다른 앱 모듈 참조 금지", Regex("""^\s*import\s+com\.conditiondrive\.(server|vehicle)\b""")),
        ArchRule("Pleos SDK 금지", Regex("""^\s*import\s+ai\.pleos\b""")),
        ArchRule("Health Connect 혈당 읽기 금지", Regex("""BloodGlucoseRecord""")),
    ),
    ":server" to listOf(
        ArchRule("engine import 금지", Regex("""^\s*import\s+com\.conditiondrive\.core\.engine\b""")),
    ),
)

// 문구와 표, 고지 항목을 문서에서 읽어 대조하는 시험이 있다. 문서만 고쳐도 다시 돌아야 한다
subprojects {
    tasks.withType<Test>().configureEach {
        inputs.dir(rootProject.file("docs")).withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.file(rootProject.file("README.md")).withPathSensitivity(PathSensitivity.RELATIVE)
        // 제출 파일명 시험(ReadmeTest)이 팀명 속성, 두 앱의 파일명 규칙과 앱 라벨을 읽는다
        inputs.files(
            rootProject.file("gradle.properties"),
            rootProject.file("vehicle/build.gradle.kts"),
            rootProject.file("companion/build.gradle.kts"),
            rootProject.file("vehicle/src/main/res/values/strings.xml"),
            rootProject.file("companion/src/main/res/values/strings.xml"),
            // 내장 POI 시험(PlaceCatalogTest, PoiBuildScriptTest)이 다른 모듈의 파일과 생성 스크립트를 읽는다
            rootProject.file("vehicle/src/main/assets/poi.json"),
            rootProject.file("tools/poi_build.py"),
            rootProject.file("tools/poi_build_test.py"),
        ).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

archRules.forEach { (path, rules) ->
    project(path) {
        val srcDir = layout.projectDirectory.dir("src")
        val checkArchitecture = tasks.register("checkArchitecture") {
            group = "verification"
            description = "설계서 2장 의존 규칙을 소스에서 검사한다"
            inputs.dir(srcDir).optional().withPathSensitivity(PathSensitivity.RELATIVE)
            val root = srcDir.asFile
            doLast {
                // 테스트는 금지 문자열을 일부러 담을 수 있으므로 제품 코드와 픽스처만 본다
                val violations = root.walkTopDown()
                    .filter { it.isFile && it.extension in setOf("kt", "java") }
                    .map { it to it.invariantSeparatorsPath }
                    .filterNot { (_, p) -> "/src/test/" in p || "/src/androidTest/" in p }
                    .flatMap { (file, p) ->
                        file.readLines().asSequence().withIndex().flatMap { (i, line) ->
                            rules.asSequence()
                                .filter { it.appliesTo(p) && it.pattern.containsMatchIn(line) }
                                .map { "${file.relativeTo(root)}:${i + 1} [${it.name}] ${line.trim()}" }
                        }
                    }
                    .toList()
                check(violations.isEmpty()) {
                    "의존 규칙 위반 ${violations.size}건:\n" + violations.joinToString("\n")
                }
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(checkArchitecture) }
    }
}
