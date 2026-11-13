import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val cameraxVersion = "1.5.3"

val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.conditiondrive.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.conditiondrive.companion"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // 뒤로 가기 시험(Robolectric Compose)이 글꼴 리소스와 라이선스 asset을 읽는다
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val team = providers.gradleProperty("cd.apkTeam").getOrElse("hyunkyo")
        val copyApk = tasks.register<Copy>("copyReleaseApk") {
            description = "공모전 제출 규칙 팀명_앱이름_release.apk(설계서 18.3)의 파일명으로 apk/에 복사한다"
            from(variant.artifacts.get(SingleArtifact.APK)) { include("*.apk") }
            into(rootProject.layout.projectDirectory.dir("apk"))
            rename { "${team}_BraketimeCompanion_release.apk" }
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyApk) }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.health.connect)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)
    // 앱 안 QR 스캔. Play 서비스 없는 기기에서도 돌도록 ML Kit 대신 CameraX + ZXing으로 간다
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    testImplementation(testFixtures(project(":core")))
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.compose.ui.test.manifest)
}
