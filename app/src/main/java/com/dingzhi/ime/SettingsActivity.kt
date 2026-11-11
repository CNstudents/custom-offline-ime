package com.dingzhi.ime

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.dingzhi.ime.core.Prefs
import com.dingzhi.ime.core.UserDictStore
import com.dingzhi.ime.util.Compat

/**
 * 设置页。
 *
 * 兼容要点：
 * 1. 深色模式：使用 AppCompatDelegate.setDefaultNightMode 手动切换，
 *    AppCompat 在 API 19 上通过资源限定符实现，不依赖系统暗色 API；
 *    Android 10+ 在 values-v29 中关闭了 forceDark，避免颜色被系统二次处理。
 * 2. 系统栏：5.0+ 才支持 statusBarColor，6.0+ 才支持浅色状态栏图标，逐个版本判断。
 * 3. 启用页跳转：ACTION_INPUT_METHOD_SETTINGS 在部分老 ROM 上可能不存在，
 *    做了 ACTION_SETTINGS 兜底。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCurrent: TextView
    private lateinit var tvHeight: TextView
    private lateinit var tvRadius: TextView
    private lateinit var tvDictCount: TextView
    private lateinit var colorBox: LinearLayout

    /** 可选主题色（低饱和，深浅皮肤下都清晰） */
    private val accentPalette = intArrayOf(
        0xFF2F6BFF.toInt(),
        0xFF12A150.toInt(),
        0xFFF5A524.toInt(),
        0xFFE5484D.toInt(),
        0xFF7C5CFF.toInt(),
        0xFF00A6C0.toInt(),
        0xFF5B6472.toInt()
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前设置，避免先按旧主题创建再重建
        AppCompatDelegate.setDefaultNightMode(
            if (Prefs.isDarkSkin(this)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        tvStatus = findViewById(R.id.tv_status)
        tvCurrent = findViewById(R.id.tv_current)
        tvHeight = findViewById(R.id.tv_height)
        tvRadius = findViewById(R.id.tv_radius)
        tvDictCount = findViewById(R.id.tv_dict_count)
        colorBox = findViewById(R.id.box_colors)

        bindStatusCard()
        bindAppearance()
        bindFeedback()
        bindDictCard()
        bindAbout()
        applySystemBars()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshDictCount()
    }

    // ==================================================================
    // 启用状态
    // ==================================================================
    private fun bindStatusCard() {
        findViewById<TextView>(R.id.btn_enable).setOnClickListener { openImeSettings() }
        findViewById<TextView>(R.id.btn_picker).setOnClickListener { showInputMethodPicker() }
    }

    private fun refreshStatus() {
        val enabled = isImeEnabled()
        tvStatus.setText(if (enabled) R.string.status_enabled else R.string.status_disabled)
        tvStatus.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.success else R.color.warning)
        )
        tvCurrent.text = getString(R.string.status_current, currentImeName())
    }

    /** 读取系统设置中的已启用输入法列表，判断本输入法是否启用 */
    private fun isImeEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_INPUT_METHODS
        ) ?: return false
        return enabled.contains(packageName + "/")
    }

    /** 读取系统设置中的默认输入法 */
    private fun currentImeName(): String {
        val current = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: ""
        return if (current.startsWith(packageName)) {
            getString(R.string.ime_name)
        } else {
            getString(R.string.status_other_ime)
        }
    }

    private fun openImeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (t: Throwable) {
            // 部分老 ROM 没有该页面，退回到系统设置首页
            try {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            } catch (ignored: Throwable) {
                Toast.makeText(this, R.string.toast_invalid, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showInputMethodPicker() {
        try {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            manager?.showInputMethodPicker()
        } catch (t: Throwable) {
            // 极少数 ROM 不支持切换弹窗，忽略
        }
    }

    // ==================================================================
    // 外观
    // ==================================================================
    private fun bindAppearance() {
        // 深色皮肤
        val switchDark = findViewById<SwitchCompat>(R.id.sw_dark)
        switchDark.isChecked = Prefs.isDarkSkin(this)
        switchDark.setOnCheckedChangeListener { _, checked ->
            Prefs.setDarkSkin(this, checked)
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
            )
            // 模式变化后 AppCompat 会自动重建 Activity，无需手动刷新
        }

        // 键盘高度
        val seekHeight = findViewById<SeekBar>(R.id.sb_height)
        seekHeight.max = Prefs.MAX_HEIGHT_DP - Prefs.MIN_HEIGHT_DP
        seekHeight.progress = Prefs.keyboardHeightDp(this) - Prefs.MIN_HEIGHT_DP
        tvHeight.text = getString(R.string.unit_dp, Prefs.keyboardHeightDp(this))
        seekHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = Prefs.MIN_HEIGHT_DP + progress
                Prefs.setKeyboardHeightDp(this@SettingsActivity, value)
                tvHeight.text = getString(R.string.unit_dp, value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // 按键圆角
        val seekRadius = findViewById<SeekBar>(R.id.sb_radius)
        seekRadius.max = Prefs.MAX_RADIUS_DP - Prefs.MIN_RADIUS_DP
        seekRadius.progress = Prefs.keyRadiusDp(this) - Prefs.MIN_RADIUS_DP
        tvRadius.text = getString(R.string.unit_dp, Prefs.keyRadiusDp(this))
        seekRadius.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val value = Prefs.MIN_RADIUS_DP + progress
                Prefs.setKeyRadiusDp(this@SettingsActivity, value)
                tvRadius.text = getString(R.string.unit_dp, value)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        renderSwatches()
    }

    /** 绘制主题色圆点，选中的加一圈描边 */
    private fun renderSwatches() {
        colorBox.removeAllViews()
        val size = dp(32)
        val current = Prefs.accentColor(this)
        val borderColor = ContextCompat.getColor(this, R.color.text_primary)

        for (color in accentPalette) {
            val view = View(this)
            val drawable = GradientDrawable()
            drawable.shape = GradientDrawable.OVAL
            drawable.setColor(color)
            if (color == current) {
                drawable.setStroke(dp(2), borderColor)
            }
            view.background = drawable

            val params = LinearLayout.LayoutParams(size, size)
            params.rightMargin = dp(14)
            view.layoutParams = params
            view.setOnClickListener {
                Prefs.setAccentColor(this, color)
                renderSwatches()
            }
            colorBox.addView(view)
        }
    }

    // ==================================================================
    // 按键反馈
    // ==================================================================
    private fun bindFeedback() {
        val switchVibrate = findViewById<SwitchCompat>(R.id.sw_vibrate)
        switchVibrate.isChecked = Prefs.isVibrateEnabled(this)
        switchVibrate.setOnCheckedChangeListener { _, checked ->
            Prefs.setVibrateEnabled(this, checked)
        }

        val switchSound = findViewById<SwitchCompat>(R.id.sw_sound)
        switchSound.isChecked = Prefs.isSoundEnabled(this)
        switchSound.setOnCheckedChangeListener { _, checked ->
            Prefs.setSoundEnabled(this, checked)
        }
    }

    // ==================================================================
    // 自定义词库
    // ==================================================================
    private fun bindDictCard() {
        findViewById<TextView>(R.id.btn_add_word).setOnClickListener { showAddWordDialog() }
        findViewById<TextView>(R.id.btn_add_phrase).setOnClickListener { showAddPhraseDialog() }
        findViewById<TextView>(R.id.btn_clear_dict).setOnClickListener { showClearDialog() }
    }

    private fun refreshDictCount() {
        tvDictCount.text = getString(
            R.string.dict_count,
            UserDictStore.words(this).size,
            UserDictStore.phrases(this).size
        )
    }

    private fun showAddWordDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(20), dp(6), dp(20), 0)

        val inputPinyin = createInput(getString(R.string.hint_pinyin), 30)
        val inputWord = createInput(getString(R.string.hint_word), 20)
        container.addView(inputPinyin)
        container.addView(inputWord)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_word_title)
            .setView(container)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val pinyin = inputPinyin.text.toString().trim()
                val word = inputWord.text.toString().trim()
                if (UserDictStore.addWord(this, pinyin, word)) {
                    refreshDictCount()
                    Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                } else {
                    // 区分「条数已满」与「内容不合法」两种失败原因
                    val full = UserDictStore.words(this).size >= UserDictStore.MAX_ITEMS
                    Toast.makeText(
                        this,
                        if (full) R.string.toast_full else R.string.toast_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showAddPhraseDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(20), dp(6), dp(20), 0)

        val inputCode = createInput(getString(R.string.hint_code), 10)
        val inputText = createInput(getString(R.string.hint_text), 200)
        container.addView(inputCode)
        container.addView(inputText)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_add_phrase_title)
            .setView(container)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_save) { _, _ ->
                val code = inputCode.text.toString().trim()
                val text = inputText.text.toString().trim()
                if (UserDictStore.addPhrase(this, code, text)) {
                    refreshDictCount()
                    Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                } else {
                    // 区分「条数已满」与「内容不合法」两种失败原因
                    val full = UserDictStore.phrases(this).size >= UserDictStore.MAX_ITEMS
                    Toast.makeText(
                        this,
                        if (full) R.string.toast_full else R.string.toast_invalid,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
    }

    private fun showClearDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_clear_title)
            .setMessage(R.string.dialog_clear_msg)
            .setNegativeButton(R.string.btn_cancel, null)
            .setPositiveButton(R.string.btn_confirm_clear) { _, _ ->
                UserDictStore.clearAll(this)
                refreshDictCount()
                Toast.makeText(this, R.string.toast_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ==================================================================
    // 版本说明
    // ==================================================================
    private fun bindAbout() {
        val versionText = findViewById<TextView>(R.id.tv_version)
        versionText.text = getString(R.string.version_fmt, BuildConfig.VERSION_NAME)

        findViewById<TextView>(R.id.btn_version_note).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.version_note_title)
                .setMessage(R.string.version_note_text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // ==================================================================
    // 工具
    // ==================================================================
    private fun createInput(hint: String, maxLength: Int): EditText {
        val editText = EditText(this)
        editText.hint = hint
        editText.textSize = 15f
        editText.setSingleLine(true)
        editText.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(maxLength))
        editText.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        editText.setHintTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        editText.setBackgroundResource(R.drawable.bg_input)
        editText.setPadding(dp(12), dp(10), dp(12), dp(10))

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(10)
        editText.layoutParams = params
        return editText
    }

    private fun dp(value: Int): Int = Compat.dp(this, value)

    /**
     * 系统栏适配：
     * - API 21+ 才能设置状态栏颜色；
     * - API 23+ 才有浅色状态栏图标（LIGHT_STATUS_BAR）；
     * - API 26+ 才有浅色导航栏图标（LIGHT_NAVIGATION_BAR）。
     * 深色皮肤下使用浅色图标，浅色皮肤下使用深色图标。
     */
    private fun applySystemBars() {
        val dark = Prefs.isDarkSkin(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.page_bg)
            window.navigationBarColor = ContextCompat.getColor(this, R.color.page_bg)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var flags = window.decorView.systemUiVisibility
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            window.decorView.systemUiVisibility = flags
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            var flags = window.decorView.systemUiVisibility
            flags = if (dark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = flags
        }
    }
}
