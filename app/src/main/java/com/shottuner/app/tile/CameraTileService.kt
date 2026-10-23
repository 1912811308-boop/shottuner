package com.shottuner.app.tile

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.shottuner.app.MainActivity

/**
 * 通知栏快捷磁贴：下拉通知栏点一下就进入取景器。
 *
 * 存在的理由：Android 不允许普通应用接管「双击电源键启动相机」这类系统手势，
 * 也不允许应用在别的应用前台时自动弹起。快捷磁贴是系统允许的最快入口，
 * 从「解锁手机」到「取景器就绪」两次点击，代价接近原生相机。
 */
class CameraTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (isLocked) {
            unlockAndRun { launch(intent) }
        } else {
            launch(intent)
        }
    }

    private fun launch(intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Throwable) {
            // 部分 ROM 禁止磁贴拉起 Activity，退化为普通启动
            try {
                startActivity(intent)
            } catch (_: Throwable) {
            }
        }
    }
}
