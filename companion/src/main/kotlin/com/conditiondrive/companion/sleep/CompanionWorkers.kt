package com.conditiondrive.companion.sleep

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.conditiondrive.companion.CompanionApp
import java.util.concurrent.TimeUnit

/** 11.3 "수면만: WorkManager 6시간" */
class SleepSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val controller = (applicationContext as CompanionApp).controller
        return if (controller.backgroundSleepSync()) Result.success() else Result.retry()
    }
}

/** 네트워크 없을 때 누른 해제·철회의 서버 삭제를 마저 한다 */
class RevokeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val controller = (applicationContext as CompanionApp).controller
        return if (controller.drainRevokes()) Result.success() else Result.retry()
    }
}

interface WorkScheduler {
    fun scheduleSleepSync()
    fun cancelSleepSync()
    fun scheduleRevokeRetry()
}

class AndroidWorkScheduler(context: Context) : WorkScheduler {
    private val manager = WorkManager.getInstance(context.applicationContext)
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun scheduleSleepSync() {
        manager.enqueueUniquePeriodicWork(
            SLEEP_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SleepSyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build(),
        )
    }

    override fun cancelSleepSync() {
        manager.cancelUniqueWork(SLEEP_WORK)
    }

    override fun scheduleRevokeRetry() {
        manager.enqueueUniqueWork(
            REVOKE_WORK,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RevokeWorker>()
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    private companion object {
        const val SLEEP_WORK = "sleep_sync"
        const val REVOKE_WORK = "revoke_retry"
    }
}
