package com.conditiondrive.vehicle.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import com.conditiondrive.vehicle.platform.guarded

/**
 * 10.3 시작 경로. AAOS는 시스템 사용자 0과 운전자 10이 함께 돌고 앱이 양쪽에 깔린다(실측).
 * 사용자 0의 서비스는 화면도 음성 대상도 없이 측정만 겹치므로 띄우지 않는다.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Unit
            else -> return
        }
        if (isHeadlessSystemUser(context)) return
        DriveMonitorService.startFromBackground(context)
    }

    private fun isHeadlessSystemUser(context: Context): Boolean = guarded("user check", false) {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && UserManager.isHeadlessSystemUserMode() &&
            context.getSystemService(UserManager::class.java)?.isSystemUser == true
    }
}
