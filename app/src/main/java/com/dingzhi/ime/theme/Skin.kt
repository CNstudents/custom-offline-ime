package com.dingzhi.ime.theme

import android.content.Context
import com.dingzhi.ime.util.Compat

/**
 * 键盘皮肤。所有颜色都在代码里定义，不依赖系统暗色模式 API：
 * 用户在设置页手动切换深/浅皮肤，输入法立即生效，Android 4.4 与 Android 14 表现一致。
 */
data class Skin(
    val keyboardBg: Int,      // 键盘整体背景
    val candidateBarBg: Int,  // 候选栏背景
    val keyBg: Int,           // 普通按键背景
    val funcKeyBg: Int,       // 功能键背景
    val keyText: Int,         // 普通按键文字
    val funcText: Int,        // 功能键文字
    val accent: Int,          // 主题色
    val onAccent: Int,        // 主题色上的文字颜色
    val pressed: Int,         // 按下时的蒙层
    val divider: Int,         // 分隔线
    val keyRadiusPx: Float    // 按键圆角（像素）
)

object SkinFactory {

    fun create(context: Context, dark: Boolean, accent: Int, radiusDp: Int): Skin {
        val radius = Compat.dp(context, radiusDp).toFloat()
        return if (dark) {
            Skin(
                keyboardBg = 0xFF141519.toInt(),
                candidateBarBg = 0xFF1A1C20.toInt(),
                keyBg = 0xFF2A2C33.toInt(),
                funcKeyBg = 0xFF212329.toInt(),
                keyText = 0xFFEFF1F5.toInt(),
                funcText = 0xFFB4BAC5.toInt(),
                accent = accent,
                onAccent = 0xFFFFFFFF.toInt(),
                pressed = 0x33FFFFFF,
                divider = 0xFF2C2F36.toInt(),
                keyRadiusPx = radius
            )
        } else {
            Skin(
                keyboardBg = 0xFFE9ECF1.toInt(),
                candidateBarBg = 0xFFF7F8FA.toInt(),
                keyBg = 0xFFFFFFFF.toInt(),
                funcKeyBg = 0xFFD8DDE5.toInt(),
                keyText = 0xFF1B1D21.toInt(),
                funcText = 0xFF565D68.toInt(),
                accent = accent,
                onAccent = 0xFFFFFFFF.toInt(),
                pressed = 0x1A000000,
                divider = 0xFFE1E5EB.toInt(),
                keyRadiusPx = radius
            )
        }
    }
}
