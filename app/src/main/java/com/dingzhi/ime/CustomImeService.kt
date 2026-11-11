package com.dingzhi.ime

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.dingzhi.ime.core.PinyinEngine
import com.dingzhi.ime.core.Prefs
import com.dingzhi.ime.core.UserDictStore
import com.dingzhi.ime.input.Key
import com.dingzhi.ime.input.KeyType
import com.dingzhi.ime.input.KeyboardLayouts
import com.dingzhi.ime.input.LayoutId
import com.dingzhi.ime.theme.Skin
import com.dingzhi.ime.theme.SkinFactory
import com.dingzhi.ime.util.Compat
import com.dingzhi.ime.util.Haptics
import com.dingzhi.ime.util.SoundFx
import com.dingzhi.ime.view.KeyboardView

/**
 * 定制版输入法核心服务。
 *
 * 数据流：
 *   按键 -> buffer（拼音缓冲） -> PinyinEngine 查候选 -> 候选栏展示
 *        -> 选择候选 / 空格 -> InputConnection 上屏
 *
 * 隐私：
 * 输入内容只存在于内存中的 buffer 与 InputConnection，
 * 不写文件、不发网络、不做统计埋点。
 */
class CustomImeService : InputMethodService(), KeyboardView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    /** 本地拼音引擎（懒加载，首次进入输入法时后台线程初始化） */
    private val engine: PinyinEngine by lazy { PinyinEngine(applicationContext) }

    /** 当前拼音缓冲，例如 "nihao" */
    private val buffer = StringBuilder()

    /** 当前候选列表 */
    private var candidates: ArrayList<String> = ArrayList()
    /** 当前候选消耗的拼音字母数（用于处理剩余拼音） */
    private var consumed = 0
    /** 当前候选是否来自「联想」 */
    private var assocMode = false

    /** 当前是否中文（拼音）模式 */
    private var chinese = true
    /** 当前布局 */
    private var layoutId = LayoutId.LETTERS
    /** 大写状态（只对英文模式有意义） */
    private var caps = false
    /** 大写锁定（双击 Shift） */
    private var capsLock = false
    private var lastShiftTapTime = 0L

    /** 编辑框类型：数字键盘 / 密码框（密码框强制英文，不做拼音候选） */
    private var numericEditor = false
    private var passwordEditor = false

    // 视图引用
    private var rootView: LinearLayout? = null
    private var candidateBar: HorizontalScrollView? = null
    private var candidateBox: LinearLayout? = null
    private var keyboardView: KeyboardView? = null

    // ==================================================================
    // 生命周期
    // ==================================================================
    override fun onCreate() {
        super.onCreate()
        chinese = Prefs.isChineseMode(this)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this)
        // 后台加载词库；加载完成后刷新一次候选，避免首次输入时没有候选
        engine.loadAsync {
            engine.reloadUserData()
            refreshCandidates()
        }
    }

    override fun onCreateInputView(): View {
        val skin = currentSkin()

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setBackgroundColor(skin.keyboardBg)

        // 候选栏（可横向滚动）
        val bar = HorizontalScrollView(this)
        bar.isHorizontalScrollBarEnabled = false
        bar.isFillViewport = true
        bar.setBackgroundColor(skin.candidateBarBg)
        bar.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Compat.dp(this, CANDIDATE_BAR_HEIGHT_DP)
        )

        val box = LinearLayout(this)
        box.orientation = LinearLayout.HORIZONTAL
        box.gravity = Gravity.CENTER_VERTICAL
        box.setPadding(Compat.dp(this, 6), 0, Compat.dp(this, 6), 0)
        bar.addView(
            box,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        // 键盘本体
        val keyboard = KeyboardView(this)
        keyboard.listener = this
        keyboard.skin = skin
        keyboard.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Compat.dp(this, Prefs.keyboardHeightDp(this))
        )

        container.addView(bar)
        container.addView(keyboard)

        rootView = container
        candidateBar = bar
        candidateBox = box
        keyboardView = keyboard

        applyLayout()
        renderCandidates()
        return container
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)

        buffer.setLength(0)
        candidates = ArrayList()
        assocMode = false
        consumed = 0
        caps = false
        capsLock = false

        detectEditorType(info)
        applyLayout()
        renderCandidates()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 离开输入框时清空缓冲，避免内容残留在下一个输入框
        buffer.setLength(0)
        candidates = ArrayList()
        assocMode = false
        renderCandidates()
    }

    override fun onDestroy() {
        try {
            Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(this)
        } catch (ignored: Exception) {
            // 忽略反注册异常
        }
        super.onDestroy()
    }

    /**
     * 关闭全屏编辑模式。
     * Android 4.4 在横屏 + 全屏编辑框时会让输入法进入 ExtractView，
     * 这里统一关闭，保证各版本都使用同一套内联键盘。
     */
    override fun onEvaluateFullscreenMode(): Boolean = false

    // ==================================================================
    // 编辑框类型识别
    // ==================================================================
    private fun detectEditorType(info: EditorInfo?) {
        numericEditor = false
        passwordEditor = false

        val inputType = info?.inputType ?: InputType.TYPE_CLASS_TEXT
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass == InputType.TYPE_CLASS_NUMBER ||
            inputClass == InputType.TYPE_CLASS_PHONE ||
            inputClass == InputType.TYPE_CLASS_DATETIME
        ) {
            numericEditor = true
            layoutId = LayoutId.NUMBERS
        } else {
            layoutId = LayoutId.LETTERS
        }

        // 密码框：禁用拼音与候选，避免把明文拼音留在输入框
        if (inputClass == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        ) {
            passwordEditor = true
        }

        chinese = Prefs.isChineseMode(this) && !numericEditor && !passwordEditor
    }

    // ==================================================================
    // 键盘回调
    // ==================================================================
    override fun onKey(key: Key) {
        if (key.type == KeyType.NONE) return

        // 反馈：震动 / 音效都受设置开关控制，且各自做了版本与权限兼容
        Haptics.tick(this)
        SoundFx.tick(this)

        when (key.type) {
            KeyType.CHAR -> handleChar(key.value)
            KeyType.DELETE -> handleBackspace()
            KeyType.ENTER -> handleEnter()
            KeyType.SPACE -> handleSpace()
            KeyType.SHIFT -> handleShift()
            KeyType.LANG -> toggleLanguage()
            KeyType.LAYOUT -> switchLayout(key.value)
            KeyType.NONE -> {
                // 占位键不响应
            }
        }
    }

    // ==================================================================
    // 字符输入
    // ==================================================================
    private fun handleChar(rawValue: String) {
        if (rawValue.isEmpty()) return

        var value = rawValue
        // 中文模式下，逗号/句号直接上屏中文标点
        if (chinese && !numericEditor) {
            if (value == ",") value = "，"
            if (value == ".") value = "。"
        }

        val isLetter = value.length == 1 &&
            ((value[0] in 'a'..'z') || (value[0] in 'A'..'Z'))

        if (chinese && !numericEditor && isLetter) {
            // 拼音缓冲上限，防止极端情况占用过多内存
            if (buffer.length >= MAX_PINYIN_LENGTH) return
            buffer.append(value.lowercase())
            assocMode = false
            applyComposing()
            refreshCandidates()
            resetOneShotCaps()
            return
        }

        val ic = currentInputConnection ?: return

        // 上屏标点/数字前，先把未完成的拼音原样提交，避免内容丢失
        if (buffer.isNotEmpty()) {
            ic.commitText(buffer.toString(), 1)
            buffer.setLength(0)
        }

        ic.commitText(value, 1)
        clearCandidates()
        resetOneShotCaps()
    }

    /** 把 buffer 写入编辑框（带下划线的 composing 状态） */
    private fun applyComposing() {
        if (buffer.isEmpty()) return
        val ic = currentInputConnection ?: return
        try {
            ic.setComposingText(buffer.toString(), 1)
        } catch (t: Throwable) {
            // 极少数输入框不支持 composing，忽略即可
        }
    }

    // ==================================================================
    // 候选
    // ==================================================================
    private fun refreshCandidates() {
        if (buffer.isEmpty()) {
            if (!assocMode) {
                candidates = ArrayList()
                renderCandidates()
            }
            return
        }
        val limit = if (Compat.isLowRamDevice(this)) LOW_RAM_CANDIDATE_LIMIT else CANDIDATE_LIMIT
        val result = engine.query(buffer.toString(), limit)
        candidates = result.candidates
        consumed = if (result.consumed > 0) result.consumed else buffer.length
        assocMode = false
        renderCandidates()
    }

    private fun chooseCandidate(index: Int) {
        if (index < 0 || index >= candidates.size) return
        val ic = currentInputConnection ?: return
        val word = candidates[index]

        // 联想候选：直接上屏
        if (assocMode) {
            ic.commitText(word, 1)
            buffer.setLength(0)
            showAssociation(word)
            return
        }

        val consumedLength = if (consumed in 1..buffer.length) consumed else buffer.length
        val rest = if (consumedLength < buffer.length) buffer.substring(consumedLength) else ""

        try {
            // commitText 会替换掉当前整段 composing 的拼音
            ic.commitText(word, 1)
        } catch (t: Throwable) {
            return
        }

        buffer.setLength(0)
        if (rest.isNotEmpty()) {
            // 例如输入 nihaoma，选了「你好」后剩余 ma 继续参与候选
            buffer.append(rest)
            try {
                ic.setComposingText(rest, 1)
            } catch (ignored: Throwable) {
                // 忽略不支持 composing 的输入框
            }
            refreshCandidates()
        } else {
            candidates = ArrayList()
            showAssociation(word)
        }
    }

    private fun showAssociation(word: String) {
        val list = engine.assocFor(word)
        candidates = list
        assocMode = list.isNotEmpty()
        consumed = 0
        renderCandidates()
    }

    private fun clearCandidates() {
        candidates = ArrayList()
        assocMode = false
        consumed = 0
        renderCandidates()
    }

    /** 候选栏渲染：直接用 TextView 横向排列，API 19 完全兼容 */
    private fun renderCandidates() {
        val box = candidateBox ?: return
        val skin = currentSkin()
        box.removeAllViews()

        if (candidates.isEmpty()) return

        val density = resources.displayMetrics.density
        val horizontalPadding = (11 * density).toInt()

        for (i in candidates.indices) {
            val item = TextView(this)
            item.text = candidates[i]
            item.textSize = 16f
            item.gravity = Gravity.CENTER
            item.setSingleLine(true)
            item.setTextColor(if (i == 0) skin.accent else skin.keyText)
            item.setPadding(horizontalPadding, 0, horizontalPadding, 0)
            item.isClickable = true
            val position = i
            item.setOnClickListener { chooseCandidate(position) }
            box.addView(
                item,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    // ==================================================================
    // 空格 / 回车 / 退格
    // ==================================================================
    private fun handleSpace() {
        val ic = currentInputConnection ?: return
        if (buffer.isNotEmpty()) {
            if (candidates.isNotEmpty()) {
                chooseCandidate(0)
            } else {
                // 词库里没有匹配时，空格直接上屏原始拼音，避免用户输入丢失
                try {
                    ic.commitText(buffer.toString(), 1)
                } catch (ignored: Throwable) {
                    // 忽略
                }
                buffer.setLength(0)
                clearCandidates()
            }
            return
        }
        ic.commitText(" ", 1)
        clearCandidates()
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return

        // 有未上屏拼音时，回车直接提交原始拼音
        if (buffer.isNotEmpty()) {
            ic.commitText(buffer.toString(), 1)
            buffer.setLength(0)
            clearCandidates()
            return
        }

        // 优先执行输入框声明的动作（搜索 / 发送 / 完成 等）
        val info = currentInputEditorInfo
        if (info != null) {
            val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
            val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            if (!noEnterAction &&
                action != EditorInfo.IME_ACTION_NONE &&
                action != EditorInfo.IME_ACTION_UNSPECIFIED
            ) {
                try {
                    ic.performEditorAction(action)
                } catch (ignored: Throwable) {
                    // 部分定制 ROM 不支持，回退到换行
                }
                return
            }
        }

        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        clearCandidates()
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return

        // 1. 拼音缓冲优先
        if (buffer.isNotEmpty()) {
            buffer.deleteCharAt(buffer.length - 1)
            if (buffer.isEmpty()) {
                try {
                    ic.commitText("", 1) // 清掉 composing 下划线
                } catch (ignored: Throwable) {
                    // 忽略
                }
                clearCandidates()
            } else {
                try {
                    ic.setComposingText(buffer.toString(), 1)
                } catch (ignored: Throwable) {
                    // 忽略
                }
                refreshCandidates()
            }
            return
        }

        // 2. 没有拼音时删除编辑框内容
        clearCandidates()
        try {
            val selected = ic.getSelectedText(0)
            if (selected != null && selected.isNotEmpty()) {
                ic.commitText("", 1)
                return
            }
            // API 19+ 大多数输入框支持 deleteSurroundingText；
            // 不支持的旧输入框回退成手动发送 DEL 的 down/up 按键事件
            if (!ic.deleteSurroundingText(1, 0)) {
                sendDelKey(ic)
            }
        } catch (t: Throwable) {
            try {
                sendDelKey(ic)
            } catch (ignored: Throwable) {
                // 完全无法删除时保持原样，避免崩溃
            }
        }
    }

    /**
     * 手动构造 DEL 键的按下/抬起事件发送给输入框。
     * 老系统（Android 4.4~6.0）上部分自定义输入框不支持 deleteSurroundingText，
     * 只能靠按键事件删除；InputConnection.sendKeyEvent 从 API 3 就存在，兼容性最好。
     */
    private fun sendDelKey(ic: InputConnection) {
        val now = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, 0))
        ic.sendKeyEvent(KeyEvent(now, now + 1, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
    }

    // ==================================================================
    // 大小写 / 中英切换 / 布局切换
    // ==================================================================
    private fun handleShift() {
        val now = System.currentTimeMillis()
        val doubleTap = now - lastShiftTapTime < 350L
        lastShiftTapTime = now

        if (capsLock) {
            capsLock = false
            caps = false
        } else if (caps) {
            if (doubleTap) {
                capsLock = true
                caps = true
            } else {
                caps = false
            }
        } else {
            caps = true
        }
        applyLayout()
    }

    /** 单次大写输入完成后自动复位 */
    private fun resetOneShotCaps() {
        if (caps && !capsLock) {
            caps = false
            applyLayout()
        }
    }

    private fun toggleLanguage() {
        if (buffer.isNotEmpty()) {
            val ic = currentInputConnection
            if (ic != null) {
                try {
                    ic.commitText(buffer.toString(), 1)
                } catch (ignored: Throwable) {
                    // 忽略
                }
            }
            buffer.setLength(0)
        }
        chinese = !chinese
        Prefs.setChineseMode(this, chinese)
        caps = false
        capsLock = false
        layoutId = LayoutId.LETTERS
        clearCandidates()
        applyLayout()
    }

    private fun switchLayout(target: String) {
        layoutId = when (target) {
            "NUMBERS" -> LayoutId.NUMBERS
            "SYMBOLS" -> LayoutId.SYMBOLS
            else -> LayoutId.LETTERS
        }
        caps = false
        capsLock = false
        applyLayout()
    }

    private fun applyLayout() {
        val keyboard = keyboardView ?: return
        keyboard.skin = currentSkin()
        keyboard.setRows(KeyboardLayouts.build(layoutId, caps, chinese))
    }

    // ==================================================================
    // 皮肤 / 设置变化
    // ==================================================================
    private fun currentSkin(): Skin = SkinFactory.create(
        this,
        Prefs.isDarkSkin(this),
        Prefs.accentColor(this),
        Prefs.keyRadiusDp(this)
    )

    private fun applySkin() {
        val skin = currentSkin()
        rootView?.setBackgroundColor(skin.keyboardBg)
        candidateBar?.setBackgroundColor(skin.candidateBarBg)
        applyLayout()
        renderCandidates()
    }

    private fun updateKeyboardHeight() {
        val keyboard = keyboardView ?: return
        val params = keyboard.layoutParams as? LinearLayout.LayoutParams ?: return
        params.height = Compat.dp(this, Prefs.keyboardHeightDp(this))
        keyboard.layoutParams = params
        keyboard.requestLayout()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_DARK_SKIN,
            Prefs.KEY_ACCENT_COLOR,
            Prefs.KEY_KEY_RADIUS -> applySkin()

            Prefs.KEY_KEYBOARD_HEIGHT -> updateKeyboardHeight()

            Prefs.KEY_CHINESE_MODE -> {
                chinese = Prefs.isChineseMode(this) && !numericEditor && !passwordEditor
                applyLayout()
            }

            UserDictStore.KEY_WORDS,
            UserDictStore.KEY_PHRASES -> {
                engine.reloadUserData()
                refreshCandidates()
            }

            else -> {
                // 其它设置（震动 / 音效）在按键时实时读取，不需要刷新界面
            }
        }
    }

    companion object {
        /** 候选栏高度 */
        private const val CANDIDATE_BAR_HEIGHT_DP = 38
        /** 普通机型候选数量 */
        private const val CANDIDATE_LIMIT = 28
        /** 低内存机型候选数量，减少 View 创建与内存占用 */
        private const val LOW_RAM_CANDIDATE_LIMIT = 14
        /** 拼音缓冲最大长度 */
        private const val MAX_PINYIN_LENGTH = 24
    }
}
