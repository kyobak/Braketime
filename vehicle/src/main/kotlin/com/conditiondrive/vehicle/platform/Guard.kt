package com.conditiondrive.vehicle.platform

import android.util.Log
import com.conditiondrive.vehicle.BuildConfig

internal const val PLATFORM_TAG = "CdPlatform"

/**
 * SDK와 차량 서비스는 차종과 이미지마다 다르게 실패한다(SecurityException, NoClassDefFoundError, DeadObjectException 실측).
 * 측정이 끊기면 복구되지 않으므로 어떤 Throwable도 앱을 죽이지 못하게 여기서 막고 대체 경로로 내려간다.
 * 메시지에 사용자 말이나 차량 식별값을 넣지 않는다.
 */
inline fun <T> guarded(what: String, fallback: T, block: () -> T): T =
    try {
        block()
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        logFailure(what, t)
        fallback
    }

/** JVM 테스트에는 Log 구현이 없다. 로그 실패로 대체 경로가 막히면 안 된다 */
fun logFailure(what: String, t: Throwable) {
    try {
        // release 로그에는 예외 메시지를 남기지 않는다. SDK 메시지에 무엇이 실릴지 보장되지 않는다
        if (BuildConfig.DEBUG) Log.w(PLATFORM_TAG, what, t) else Log.w(PLATFORM_TAG, "$what: ${t.javaClass.simpleName}")
    } catch (_: Throwable) {
    }
}

fun logInfo(message: String) {
    try {
        Log.i(PLATFORM_TAG, message)
    } catch (_: Throwable) {
    }
}

/**
 * 시연 대조 리포트를 밖으로 꺼내는 유일한 경로. release는 run-as도 외부 저장소도 막혀 있다(사용자 10).
 * 시연 전이만 담고 혈당 수치와 좌표가 없어(14장) 로그에 남겨도 된다. 실사용 로그는 여기로 보내지 않는다.
 */
fun logDemoReport(scenarioId: String, report: String) {
    try {
        report.lineSequence().forEach { Log.i(DEMO_REPORT_TAG, "$scenarioId|$it") }
    } catch (_: Throwable) {
    }
}

/** tools/vc.sh가 읽는 차량 신호 시연의 기어 대기(ConsoleCue). 시나리오 ID와 기어 이름뿐이다 */
fun logDemoConsole(line: String) {
    try {
        Log.i(DEMO_CONSOLE_TAG, line)
    } catch (_: Throwable) {
    }
}

private const val DEMO_REPORT_TAG = "CdDemoReport"
private const val DEMO_CONSOLE_TAG = "CdDemoConsole"
