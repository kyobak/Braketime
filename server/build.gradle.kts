import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("com.conditiondrive.server.MainKt")
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)

    testImplementation(testFixtures(project(":core")))
    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testRuntimeOnly(libs.junit5.vintage)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
