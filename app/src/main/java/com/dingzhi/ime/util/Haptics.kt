package com.dingzhi.ime.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.dingzhi.ime.core.Prefs

/**
 * 按键震动（带缓存）。
 *
 * 性能说明：
 * 早期实现每按一个键都会读取 SharedPreferences、
 * 通过 checkSelfPermission 做一次跨进程权限查询、再 getSystemService，
 * 老机型上单次按键的开销就被放大。现在改为：
 * - 震动开关在创建时读一次，设置变化时由服务调用 refresh() 刷新；
 * - 权限与 Vibrator 对象只解析一次（懒加载），后续按键直接复用；
 * - 没有马达 / 权限被回收时提前返回，不做任何系统调用。
 *
 * 不同 Android 版本的震动 API：
 * - API >= 26（Android 8.0）：使用 VibrationEffect；
 * - API 21 ~ 25：使用 vibrate(long[], int) 的 pattern 写法；
 * - API < 21（Android 4.4）：只支持最老的 vibrate(long)。
 */
class Haptics(private val context: Context) {

    @Volatile
    private var enabled = Prefs.isVibrateEnabled(context)

    /** 首次使用时解析一次；无权限 / 无马达时返回 null */
    private val vibrator: Vibrator? by lazy {
        if (!Compat.hasVibratePermission(context)) return@lazy null
        val manager = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            ?: return@lazy null
        if (manager.hasVibrator()) manager else null
    }

    /** 在服务创建时调用，把权限检查与系统服务查询提前到非按键路径 */
    fun prepare() {
        vibrator
    }

    /** 设置变化后刷新开关状态 */
    fun refresh() {
        enabled = Prefs.isVibrateEnabled(context)
    }

    /** 轻微触感，接近原生键盘的按键反馈 */
    fun tick() {
        if (!enabled) return
        val manager = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.vibrate(
                    VibrationEffect.createOneShot(DURATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                manager.vibrate(longArrayOf(0L, DURATION_MS), -1)
            } else {
                manager.vibrate(DURATION_MS)
            }
        } catch (t: Throwable) {
            // 老机型可能没有震动马达 / ROM 禁用了震动，忽略即可
        }
    }

    companion object {
        private const val DURATION_MS = 18L
    }
}
