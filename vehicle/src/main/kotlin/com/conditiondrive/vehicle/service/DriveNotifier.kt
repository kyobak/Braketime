package com.conditiondrive.vehicle.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.conditiondrive.core.engine.DriverState
import com.conditiondrive.core.engine.TransitionEntry
import com.conditiondrive.core.voice.VoiceId
import com.conditiondrive.vehicle.R
import com.conditiondrive.vehicle.platform.guarded
import com.conditiondrive.vehicle.port.TransitionLogPort
import java.io.File

/**
 * 10.3 헤드업. 소리는 TTS가 맡으므로 채널에 소리가 없다.
 * POST_NOTIFICATIONS가 거부돼도 notify는 조용히 무시되므로 음성 경로에 영향이 없다.
 */
class DriveNotifier(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        guarded("notification channels", Unit) {
            val status = NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
            val critical = NotificationChannel(CHANNEL_CRITICAL, context.getString(R.string.channel_critical), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
            manager?.createNotificationChannels(listOf(status, critical))
        }
    }

    fun statusNotification(): Notification = Notification.Builder(context, CHANNEL_STATUS)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle(context.getString(R.string.notify_status_title))
        .setContentText(context.getString(R.string.notify_status_text))
        .setOngoing(true)
        .setContentIntent(openApp())
        .build()

    /**
     * 재안내 때 같은 id로 다시 내도 헤드업이 뜨게 setOnlyAlertOnce를 쓰지 않는다.
     * highway는 말하지 못한 음성이 나들목 변형(V09H, V10H)일 때다. 음성의 "빠져나간 뒤"가 눈으로도 닿아야 한다(V-07)
     */
    fun showStopAdvice(highway: Boolean = false) {
        guarded("stop advice", Unit) {
            ensureChannels()
            manager?.notify(
                ID_STOP,
                Notification.Builder(context, CHANNEL_CRITICAL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(context.getString(R.string.notify_stop_title))
                    .setContentText(context.getString(if (highway) R.string.notify_stop_text_highway else R.string.notify_stop_text))
                    .setCategory(Notification.CATEGORY_NAVIGATION)
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    fun clearStopAdvice() {
        guarded("stop advice clear", Unit) { manager?.cancel(ID_STOP) }
    }

    /** V17 대신. 혈당 단어 없이 출발 보류만 말한다(14장). 앱이 화면에 없으면 띠를 볼 수 없다 */
    fun showHoldAdvice() {
        guarded("hold advice", Unit) {
            ensureChannels()
            manager?.notify(
                ID_HOLD,
                Notification.Builder(context, CHANNEL_CRITICAL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(context.getString(R.string.hold_band))
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    fun clearHoldAdvice() {
        guarded("hold advice clear", Unit) { manager?.cancel(ID_HOLD) }
    }

    /** 13장 자동 실행 실패 */
    fun showLaunchHint() {
        guarded("launch hint", Unit) {
            ensureChannels()
            manager?.notify(
                ID_HINT,
                Notification.Builder(context, CHANNEL_CRITICAL)
                    .setSmallIcon(R.drawable.ic_launcher)
                    .setContentTitle(context.getString(R.string.notify_launch_title))
                    .setContentText(context.getString(R.string.notify_launch_text))
                    .setCategory(Notification.CATEGORY_NAVIGATION)
                    .setContentIntent(openApp())
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }

    /**
     * 프로세스 중요도로는 알 수 없다. 앱 영역에 다른 앱이 올라와도 FGS가 있으면 TOP으로 나온다(실측).
     * 그래서 앱 태스크가 보이는지 직접 묻는다.
     */
    fun appOnScreen(): Boolean = guarded("app state", false) {
        val activities = context.getSystemService(ActivityManager::class.java) ?: return false
        // TaskInfo.isVisible은 API 32부터다. minSdk는 28이고 lint의 "current min 31"은 예전 S 분기 안의 값이었다
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S_V2) {
            activities.appTasks.any { it.taskInfo.isVisible }
        } else {
            val state = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(state)
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun openApp(): PendingIntent? = guarded("open app intent", null) {
        val launch: Intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_STATUS = "cd_status"
        const val CHANNEL_CRITICAL = "cd_critical"
        const val ID_STATUS = 1
        const val ID_STOP = 2
        const val ID_HINT = 3
        const val ID_HOLD = 4
    }
}

/**
 * 8.1: 앱이 화면에 없으면 정차 권고만 헤드업으로 낸다.
 * 음성을 낸 사건은 전이 로그에 한 줄씩 남으므로 재안내까지 그대로 잡힌다. 엔진 효과를 다시 해석하지 않아도 된다.
 */
internal class StopAdviceLog(
    private val delegate: TransitionLogPort,
    private val onStopAdvice: () -> Unit,
    private val onLeftStop: () -> Unit,
    private val onHoldAdvice: () -> Unit = {},
    private val onLeftHold: () -> Unit = {},
) : TransitionLogPort {
    override fun append(entry: TransitionEntry) {
        delegate.append(entry)
        if (entry.voices.any { it in STOP_ADVICE }) onStopAdvice()
        if (entry.from == DriverState.STOP && entry.to != DriverState.STOP) onLeftStop()
        if (VoiceId.V17 in entry.voices) onHoldAdvice() else if (entry.to != DriverState.RECOVER) onLeftHold()
    }

    override suspend fun export(): File = delegate.export()

    companion object {
        /** 6.3 ①②의 정차 권고 음성. 체크인과 예고는 헤드업을 내지 않는다 */
        val STOP_ADVICE: Set<VoiceId> = setOf(
            VoiceId.V04, VoiceId.V04N, VoiceId.V05, VoiceId.V06, VoiceId.V06N,
            VoiceId.V09, VoiceId.V09N, VoiceId.V09H, VoiceId.V10, VoiceId.V10N, VoiceId.V10U, VoiceId.V10H,
        )

        /** 큐가 고른 고속 변형. 이 음성을 대신하는 헤드업은 갓길로 읽히지 않게 "나간 뒤"를 적는다 */
        val HIGHWAY_STOP: Set<VoiceId> = setOf(VoiceId.V09H, VoiceId.V10H)
    }
}
