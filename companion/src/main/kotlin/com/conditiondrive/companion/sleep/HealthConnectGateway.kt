package com.conditiondrive.companion.sleep

import android.content.Context
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.io.IOException
import java.time.Instant

class HealthConnectGateway(context: Context) : HealthGateway {
    private val appContext = context.applicationContext

    override fun availability(): HealthAvailability = when (HealthConnectClient.getSdkStatus(appContext)) {
        HealthConnectClient.SDK_AVAILABLE -> HealthAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthAvailability.NEEDS_PROVIDER_UPDATE
        else -> HealthAvailability.UNAVAILABLE
    }

    private fun clientOrNull(): HealthConnectClient? =
        if (availability() == HealthAvailability.AVAILABLE) HealthConnectClient.getOrCreate(appContext) else null

    override suspend fun hasSleepPermission(): Boolean {
        val client = clientOrNull() ?: return false
        return guarded { SLEEP_PERMISSION in client.permissionController.getGrantedPermissions() }
    }

    override suspend fun readSleep(fromMs: Long, toMs: Long): List<SleepSpan> {
        val client = clientOrNull() ?: return emptyList()
        return guarded {
            val spans = mutableListOf<SleepSpan>()
            var page: String? = null
            do {
                val response = client.readRecords(
                    ReadRecordsRequest(SleepSessionRecord::class, range(fromMs, toMs), pageToken = page),
                )
                response.records.mapTo(spans) { SleepSpan(it.startTime.toEpochMilli(), it.endTime.toEpochMilli()) }
                page = response.pageToken
            } while (page != null)
            spans
        }
    }

    override suspend fun latestHeartRate(fromMs: Long, toMs: Long): HeartSample? {
        val client = clientOrNull() ?: return null
        return guarded {
            if (HEART_PERMISSION !in client.permissionController.getGrantedPermissions()) return@guarded null
            val response = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, range(fromMs, toMs), ascendingOrder = false, pageSize = 1),
            )
            response.records.firstOrNull()?.samples?.maxByOrNull { it.time }?.let {
                HeartSample(it.beatsPerMinute, it.time.toEpochMilli())
            }
        }
    }

    private fun range(fromMs: Long, toMs: Long) =
        TimeRangeFilter.between(Instant.ofEpochMilli(fromMs), Instant.ofEpochMilli(toMs))

    // 백그라운드 권한이 없으면 SecurityException이 난다. 오류 내용은 값을 담을 수 있어 버리고 종류만 넘긴다
    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (_: SecurityException) {
        throw HealthReadException()
    } catch (_: RemoteException) {
        throw HealthReadException()
    } catch (_: IOException) {
        throw HealthReadException()
    } catch (_: IllegalStateException) {
        throw HealthReadException()
    }

    companion object {
        private val SLEEP_PERMISSION = HealthPermission.getReadPermission(SleepSessionRecord::class)
        private val HEART_PERMISSION = HealthPermission.getReadPermission(HeartRateRecord::class)

        /** 권한 요청 묶음. 백그라운드는 6시간 동기화용이고 거절해도 앱을 열 때 읽는다 */
        val REQUESTED: Set<String> = setOf(
            SLEEP_PERMISSION,
            HEART_PERMISSION,
            HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND,
        )
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
    }
}
