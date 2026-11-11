package com.dingzhi.ime.core

import android.content.Context
import android.content.SharedPreferences
import com.dingzhi.ime.util.Compat

/**
 * 全局设置读写。
 *
 * 兼容性说明：
 * - 全部数据写入应用私有的 SharedPreferences（/data/data/包名/shared_prefs），
 *   属于应用内部存储，Android 4.4 到 Android 14 都不需要任何存储权限，
 *   也不会受 Android 10+ 分区存储（Scoped Storage）限制影响。
 * - 使用 apply() 异步落盘（API 9+ 可用），不阻塞输入法主线程。
 * - 深色/浅色完全由本应用的开关决定，不读取系统暗色模式 API。
 */
object Prefs {

    const val FILE_NAME = "ime_prefs"

    // ---------------- 设置项 Key ----------------
    const val KEY_DARK_SKIN = "dark_skin"               // 深色皮肤
    const val KEY_KEYBOARD_HEIGHT = "keyboard_height_dp" // 键盘高度（dp）
    const val KEY_KEY_RADIUS = "key_radius_dp"          // 按键圆角（dp）
    const val KEY_ACCENT_COLOR = "accent_color"         // 主题色（ARGB）
    const val KEY_VIBRATE = "key_vibrate"               // 按键震动
    const val KEY_SOUND = "key_sound"                   // 按键音效
    const val KEY_CHINESE_MODE = "chinese_mode"         // 上次使用的中/英状态

    // ---------------- 取值范围 ----------------
    const val MIN_HEIGHT_DP = 190
    const val MAX_HEIGHT_DP = 360
    const val DEFAULT_HEIGHT_DP = 252

    /** 默认键盘高度占屏幕高度的比例 */
    private const val DEFAULT_SCREEN_RATIO = 0.36f
    /** 键盘高度相对屏幕高度的上下限，避免横屏 / 平板上比例失衡 */
    private const val MIN_SCREEN_RATIO = 0.24f
    private const val MAX_SCREEN_RATIO = 0.52f

    const val MIN_RADIUS_DP = 0
    const val MAX_RADIUS_DP = 16
    const val DEFAULT_RADIUS_DP = 8

    /** 默认主题色：低饱和蓝 */
    val DEFAULT_ACCENT: Int = 0xFF2F6BFF.toInt()

    fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    // ---------------- 皮肤 ----------------
    fun isDarkSkin(context: Context): Boolean =
        sp(context).getBoolean(KEY_DARK_SKIN, false)

    fun setDarkSkin(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_DARK_SKIN, value).apply()
    }

    fun accentColor(context: Context): Int =
        sp(context).getInt(KEY_ACCENT_COLOR, DEFAULT_ACCENT)

    fun setAccentColor(context: Context, color: Int) {
        sp(context).edit().putInt(KEY_ACCENT_COLOR, color).apply()
    }

    fun keyRadiusDp(context: Context): Int =
        sp(context).getInt(KEY_KEY_RADIUS, DEFAULT_RADIUS_DP)
            .coerceIn(MIN_RADIUS_DP, MAX_RADIUS_DP)

    fun setKeyRadiusDp(context: Context, value: Int) {
        sp(context).edit().putInt(KEY_KEY_RADIUS, value.coerceIn(MIN_RADIUS_DP, MAX_RADIUS_DP)).apply()
    }

    // ---------------- 键盘高度 ----------------
    fun keyboardHeightDp(context: Context): Int =
        sp(context).getInt(KEY_KEYBOARD_HEIGHT, DEFAULT_HEIGHT_DP)
            .coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP)

    fun setKeyboardHeightDp(context: Context, value: Int) {
        sp(context).edit()
            .putInt(KEY_KEYBOARD_HEIGHT, value.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP))
            .apply()
    }

    /**
     * 实际键盘高度（像素）。
     *
     * 默认高度不再写死为固定 dp，而是按屏幕高度百分比计算，
     * 这样在 4.7 寸小屏与 6.7 寸大屏、平板上都能获得接近的视觉比例；
     * 用户手动拖动过高度后使用其固定值，并仍然按屏幕高度做上下限保护，
     * 避免横屏时键盘占满屏幕。
     */
    fun keyboardHeightPx(context: Context): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        val stored = keyboardHeightDp(context)
        val raw = if (stored == DEFAULT_HEIGHT_DP) {
            (screenHeight * DEFAULT_SCREEN_RATIO).toInt()
        } else {
            Compat.dp(context, stored)
        }
        val minPx = (screenHeight * MIN_SCREEN_RATIO).toInt()
        val maxPx = (screenHeight * MAX_SCREEN_RATIO).toInt()
        return raw.coerceIn(minPx, maxPx)
    }

    // ---------------- 按键反馈 ----------------
    fun isVibrateEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_VIBRATE, true)

    fun setVibrateEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_VIBRATE, value).apply()
    }

    fun isSoundEnabled(context: Context): Boolean =
        sp(context).getBoolean(KEY_SOUND, false)

    fun setSoundEnabled(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_SOUND, value).apply()
    }

    // ---------------- 中/英状态 ----------------
    fun isChineseMode(context: Context): Boolean =
        sp(context).getBoolean(KEY_CHINESE_MODE, true)

    fun setChineseMode(context: Context, value: Boolean) {
        sp(context).edit().putBoolean(KEY_CHINESE_MODE, value).apply()
    }
}
