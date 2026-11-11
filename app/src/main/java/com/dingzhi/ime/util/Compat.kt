package com.dingzhi.ime.util

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat

/**
 * 版本兼容工具集合。
 * 所有与系统版本相关的差异判断都集中在这里，方便统一维护。
 */
object Compat {

    /** dp -> px，API 19 没有现成的工具方法，这里手写一份 */
    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 震动权限判断：
     * - Android 6.0(M, 23) 起权限模型变为运行时权限，这里用 checkSelfPermission 做判断；
     * - Android 6.0 以下属于安装时权限，只要清单声明就一定授予，直接返回 true。
     * 本应用只申请 VIBRATE 这一个普通权限，系统不会弹出授权框，
     * 但部分定制 ROM 允许用户手动回收，所以仍然做一次判断并优雅降级。
     */
    fun hasVibratePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.VIBRATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 当前输入法子类型的语言（小写，例如 "zh" / "en"）。
     * 用于区分 method.xml 中的中英文子类型：英文子类型启动时直接进入英文模式。
     * getCurrentInputMethodSubtype 从 API 11 起可用；Subtype.locale 从 API 24 起可用。
     */
    fun currentSubtypeLocale(context: Context): String {
        return try {
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                ?: return ""
            val subtype = manager.currentInputMethodSubtype ?: return ""
            val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                subtype.locale
            } else {
                @Suppress("DEPRECATION")
                subtype.getLocale()
            }
            locale.lowercase()
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 是否低内存设备（API 19 起提供）。
     * 用于降低候选数量，避免 512MB 老机型在加载词库时 OOM。
     */
    fun isLowRamDevice(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        return manager.isLowRamDevice
    }
}
