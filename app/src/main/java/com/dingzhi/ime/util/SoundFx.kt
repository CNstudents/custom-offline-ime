package com.dingzhi.ime.util

import android.content.Context
import android.media.AudioManager
import com.dingzhi.ime.core.Prefs

/**
 * 按键音效（带缓存）。
 *
 * 与 Haptics 一样：开关与 AudioManager 都只解析一次，
 * 设置变化时由服务调用 refresh()，按键路径上不再读取 SharedPreferences。
 *
 * 使用系统自带的按键音（STREAM_SYSTEM），不打包任何音频文件：
 * 1. 包体更小，老机型内存占用更低；
 * 2. 音量跟随系统「触摸音效」设置，符合用户习惯；
 * 3. AudioManager.FX_KEY_CLICK 从 API 1 就存在，Android 4.4 与 Android 14 都可用。
 */
class SoundFx(private val context: Context) {

    @Volatile
    private var enabled = Prefs.isSoundEnabled(context)

    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    fun prepare() {
        audioManager
    }

    fun refresh() {
        enabled = Prefs.isSoundEnabled(context)
    }

    fun tick() {
        if (!enabled) return
        val manager = audioManager ?: return
        try {
            manager.playSoundEffect(AudioManager.FX_KEY_CLICK)
        } catch (t: Throwable) {
            // 部分老机型音频服务异常，忽略
        }
    }
}
