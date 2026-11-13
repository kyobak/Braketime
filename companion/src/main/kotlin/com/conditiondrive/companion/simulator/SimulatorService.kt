package com.conditiondrive.companion.simulator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.conditiondrive.companion.CompanionApp
import com.conditiondrive.companion.MainActivity
import com.conditiondrive.companion.R
import com.conditiondrive.companion.ui.Copy
import kotlinx.coroutines.launch

/** 11.3 시뮬레이터 포그라운드 서비스. 프로세스가 죽으면 재시작하지 않는다: 값이 끊긴 것을 차량이 알아야 한다 */
class SimulatorService : LifecycleService() {
    private var loopStarted = false
    private var lastStartId = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            // 알림의 끄기 버튼. 앱 화면 끄기와 같은 경로라 null 한 번을 보내고 루프가 끝나며 서비스도 내려간다
            (application as CompanionApp).controller.stopSimulator()
            if (!loopStarted) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        lastStartId = startId
        if (!loopStarted) {
            loopStarted = true
            val controller = (application as CompanionApp).controller
            lifecycleScope.launch {
                controller.runSimulatorTicks()
                loopStarted = false
                // 끄자마자 다시 켠 경우 새 시작 요청이 살아남아 다음 onStartCommand가 루프를 다시 돌린다
                stopSelfResult(lastStartId)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15의 dataSync 6시간 제한. 멈추지 않으면 앱이 강제 종료된다 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as CompanionApp).controller.stopSimulator()
        stopSelf()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, Copy.NOTIFICATION_CHANNEL, NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SimulatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // 잠금 화면에도 보이므로 수치와 "혈당" 단어를 넣지 않는다(14장 표시 규칙). 작업 버튼 문구도 같다
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Copy.NOTIFICATION_TITLE)
            .setContentIntent(open)
            .addAction(0, Copy.SIM_STOP, stop)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "simulator"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.conditiondrive.companion.STOP_SIMULATOR"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SimulatorService::class.java))
        }
    }
}
