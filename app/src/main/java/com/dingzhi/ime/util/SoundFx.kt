package com.dingzhi.ime.util

import android.content.Context
import android.media.AudioManager
import com.dingzhi.ime.core.Prefs

/**
 * 按键音效。
 *
 * 使用系统自带的按键音（STREAM_SYSTEM），不打包任何音频文件：
 * 1. 包体更小，老机型内存占用更低；
 * 2. 音量跟随系统「触摸音效」设置，符合用户习惯；
 * 3. AudioManager.FX_KEY_CLICK 从 API 1 就存在，Android 4.4 与 Android 14 都可用，
 *    因此这里不写版本分支（对比 SoundEffectConstants.CLICK 需要 API 21+）。
 */
object SoundFx {

    fun tick(context: Context) {
        if (!Prefs.isSoundEnabled(context)) return
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK)
        } catch (t: Throwable) {
            // 部分老机型音频服务异常，忽略
        }
    }
}
