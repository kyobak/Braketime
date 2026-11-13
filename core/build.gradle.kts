import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit5.bom))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.jupiter)
    testImplementation(libs.junit4)
    testRuntimeOnly(libs.junit5.vintage)
    testRuntimeOnly(libs.junit5.launcher)
}

tasks.test {
    useJUnitPlatform()
}
