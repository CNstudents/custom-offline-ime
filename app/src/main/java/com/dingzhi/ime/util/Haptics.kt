package com.dingzhi.ime.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.dingzhi.ime.core.Prefs

/**
 * 按键震动。
 *
 * 不同 Android 版本的震动 API 差异：
 * - API >= 26（Android 8.0）：必须使用 VibrationEffect，否则在部分机型上无效；
 * - API 21 ~ 25（Android 5.0 ~ 7.1）：使用 vibrate(long[], int) 的 pattern 写法，
 *   比单纯的 vibrate(long) 兼容性更好；
 * - API < 21（Android 4.4）：只支持最老的 vibrate(long)。
 *
 * 另外：没有马达、系统关闭震动、权限被回收时都静默失败，绝不崩溃。
 */
object Haptics {

    /** 轻微触感，接近原生键盘的按键反馈 */
    private const val DURATION_MS = 18L

    fun tick(context: Context) {
        if (!Prefs.isVibrateEnabled(context)) return
        if (!Compat.hasVibratePermission(context)) return

        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (vibrator.hasVibrator()) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                vibrator.vibrate(longArrayOf(0L, DURATION_MS), -1)
            } else {
                vibrator.vibrate(DURATION_MS)
            }
        } catch (t: Throwable) {
            // 老机型可能没有震动马达 / ROM 禁用了震动，忽略即可
        }
    }
}
