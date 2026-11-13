package com.conditiondrive.vehicle.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.conditiondrive.core.engine.Ignition
import com.conditiondrive.vehicle.platform.PlatformPorts
import com.conditiondrive.vehicle.platform.guarded
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 10.3 상주 근거: 연속 운전 시간은 측정을 멈추면 복구되지 않는다.
 * 시동 ON일 때만 유지한다. OFF면 음성을 멈추고 포커스를 포기하고 저장한 뒤 내려간다.
 */
class DriveMonitorService : Service() {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> com.conditiondrive.vehicle.platform.logFailure("drive service", t) },
    )
    private lateinit var notifier: DriveNotifier
    private var assembly: DriveAssembly? = null
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = DriveNotifier(applicationContext)
        notifier.ensureChannels()
        if (!promote()) {
            // 13장 자동 실행 실패: 앱 실행 때 복원하고 실행 안내를 낸다
            DriveServiceState.markGap(applicationContext)
            notifier.showLaunchHint()
            stopSelf()
            return
        }
        running = true
        val built = DriveAssembly(applicationContext, scope, notifier)
        assembly = built
        built.start()
        watchIgnitionOff()
        IgnitionRestarterHolder.ensure(applicationContext)
    }

    /** 앱 실행 경로는 startService다. 바로 승격하지 않으면 시스템이 곧 죽인다 */
    private fun promote(): Boolean = guarded("foreground", false) {
        val notification = notifier.statusNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(DriveNotifier.ID_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(DriveNotifier.ID_STATUS, notification)
        }
        true
    }

    private fun watchIgnitionOff() {
        scope.launch {
            PlatformPorts.get(applicationContext).signals.ignition.first { it == Ignition.OFF }
            shutDown()
        }
    }

    private suspend fun shutDown() {
        if (stopping) return
        stopping = true
        withContext(NonCancellable) { assembly?.stop() }
        assembly = null
        guarded("stop foreground", Unit) { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        guarded("permission refresh", Unit) { PlatformPorts.get(applicationContext).refreshPermissions() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        val offWasIntended = stopping
        // 시스템이 내리는 경로에서도 저장을 잃지 않는다. 프로세스가 곧바로 끝나면 주기 저장본이 남는다
        val leftover = assembly
        assembly = null
        if (leftover != null) {
            CoroutineScope(Dispatchers.Main.immediate).launch {
                withContext(NonCancellable) { leftover.stop() }
                scope.cancel()
            }
        } else {
            scope.cancel()
        }
        // 시동을 끈 직후 다시 켰으면 재시작 신호를 놓쳤다. 시스템이 내린 경우는 START_STICKY가 맡는다
        if (offWasIntended) IgnitionRestarterHolder.onServiceStopped()
        super.onDestroy()
    }

    companion object {
        @Volatile var running: Boolean = false
            private set

        /** 부팅·갱신 브로드캐스트와 시동 콜백 경로. 거부되면 false다 */
        fun startFromBackground(context: Context): Boolean = guarded("start service", false) {
            ContextCompat.startForegroundService(context, Intent(context, DriveMonitorService::class.java))
            true
        }
    }
}

/** 프로세스에 하나. 서비스가 내려간 뒤에도 시동 ON을 받아야 한다 */
private object IgnitionRestarterHolder {
    private var restarter: IgnitionRestarter? = null

    @Synchronized
    fun onServiceStopped() {
        restarter?.onServiceStopped()
    }

    @Synchronized
    fun ensure(context: Context) {
        if (restarter != null) return
        val app = context.applicationContext
        restarter = IgnitionRestarter(
            ignition = PlatformPorts.get(app).signals.ignition,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            isRunning = { DriveMonitorService.running },
            startService = { DriveMonitorService.startFromBackground(app) },
            onStartFailed = {
                DriveServiceState.markGap(app)
                DriveNotifier(app).showLaunchHint()
            },
        ).also { it.watch() }
    }
}
