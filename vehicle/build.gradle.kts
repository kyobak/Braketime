import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 서명 키는 저장소에 넣지 않는다. 파일이 없어도 항상 설치 가능한 release가 나오도록 debug 키로 대체한다
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) }
}

android {
    namespace = "com.conditiondrive.vehicle"
    compileSdk = 36
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.conditiondrive.vehicle"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 실제 차량 신호 시연(15.1). 공모전 빌드에서만 켠다. 출시 빌드는 -Pcd.realSignalDemo=false
        buildConfigField("boolean", "REAL_SIGNAL_DEMO", providers.gradleProperty("cd.realSignalDemo").getOrElse("true"))
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
            // 시연 안정성이 우선이고 SDK 내부 리플렉션을 건드리지 않으려고 축소하지 않는다
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Compose 화면 테스트(Robolectric)가 문자열과 글꼴 리소스를 읽는다
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
            rename { "${team}_Braketime_release.apk" }
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyApk) }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.fusedlocation)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.text)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // S5 페어링 QR을 그리는 데만 쓴다
    implementation(libs.zxing.core)

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
    androidTestImplementation(libs.kotlin.test.junit)
    debugImplementation(libs.compose.ui.test.manifest)
}
