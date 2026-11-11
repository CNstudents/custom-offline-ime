package com.dingzhi.ime

import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
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
 *   按键 -> buffer（拼音缓冲 / 九键数字串） -> PinyinEngine 查候选 -> 候选栏展示
 *        -> 选择候选 / 空格 -> InputConnection 上屏
 *
 * 性能：
 * - 候选栏使用 TextView 复用池，按键时不再反复创建 / 销毁 View；
 * - 震动、音效、皮肤对象都做了缓存，按键路径上不读 SharedPreferences；
 * - 候选栏支持翻页，长拼音串不再被截断丢弃。
 *
 * 隐私：
 * 输入内容只存在于内存中的 buffer 与 InputConnection，
 * 不写文件、不发网络、不做统计埋点。
 */
class CustomImeService : InputMethodService(), KeyboardView.Listener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    /** 本地拼音引擎（懒加载，首次进入输入法时后台线程初始化） */
    private val engine: PinyinEngine by lazy { PinyinEngine(applicationContext) }

    /** 按键反馈（内部缓存开关、权限与系统服务） */
    private val haptics by lazy { Haptics(this) }
    private val soundFx by lazy { SoundFx(this) }

    /** 当前拼音缓冲，例如 "nihao"；九键模式下是数字串 */
    private val buffer = StringBuilder()

    /** 当前候选列表 */
    private var candidates: ArrayList<String> = ArrayList()
    /** 当前候选消耗的拼音字母数（用于处理剩余拼音） */
    private var consumed = 0
    /** 当前候选是否来自「联想」 */
    private var assocMode = false
    /** 候选栏当前页码 */
    private var candidatePage = 0

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

    /** 上一次的光标位置，用于区分「用户移动光标」与「输入法自身 composing 变化」 */
    private var lastSelStart = -1
    private var lastSelEnd = -1
    /** 自身 commitText 会触发 onUpdateSelection，短时间内忽略，避免刚显示的联想词被立刻清掉 */
    private var selectionGuardUntil = 0L

    // 视图引用
    private var rootView: LinearLayout? = null
    private var candidateBar: HorizontalScrollView? = null
    private var candidateBox: LinearLayout? = null
    private var keyboardView: KeyboardView? = null

    /** 候选 TextView 复用池 */
    private val candidateViews = ArrayList<TextView>()

    /** 皮肤缓存，设置变化时才重建 */
    private var skinCache: Skin? = null

    // ==================================================================
    // 生命周期
    // ==================================================================
    override fun onCreate() {
        super.onCreate()
        chinese = Prefs.isChineseMode(this)
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(this)
        // 提前解析震动 / 音效的系统服务与权限，避免第一次按键时才做跨进程查询
        haptics.prepare()
        soundFx.prepare()
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
            resources.getDimensionPixelSize(R.dimen.candidate_bar_height)
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
            Prefs.keyboardHeightPx(this)
        )

        container.addView(bar)
        container.addView(keyboard)

        rootView = container
        candidateBar = bar
        candidateBox = box
        keyboardView = keyboard

        // 输入法视图可能因系统回收而重建，旧池中的 View 已不可用
        candidateViews.clear()
        candidatePage = 0

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
        candidatePage = 0
        caps = false
        capsLock = false
        lastSelStart = -1
        lastSelEnd = -1

        detectEditorType(info)
        applyLayout()
        renderCandidates()
        if (!chinese) updateAutoCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // 离开输入框时清空缓冲，避免内容残留在下一个输入框
        buffer.setLength(0)
        candidates = ArrayList()
        assocMode = false
        candidatePage = 0
        renderCandidates()
    }

    /**
     * 光标 / 选区变化。
     *
     * 用户在输入框里移动光标、选中文字或点击别处时，如果还保留拼音缓冲，
     * 后续的字会拼接到错误的位置，因此这里主动清空。
     * 只有 composing 区域与 buffer 完全对应（输入法自己写入的拼音）或光标没动时才保留。
     */
    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )

        val composingMatches = buffer.isNotEmpty() &&
            candidatesStart >= 0 && (candidatesEnd - candidatesStart) == buffer.length
        val userMoved = newSelStart != lastSelStart || newSelEnd != lastSelEnd
        lastSelStart = newSelStart
        lastSelEnd = newSelEnd

        // 输入法自身 commitText 引起的光标变化不算用户操作
        if (SystemClock.uptimeMillis() < selectionGuardUntil) return
        if (buffer.isEmpty() && !assocMode) return
        if (composingMatches) return
        // 九键模式不写入 composing 文本，只有用户真正移动了光标才清空
        if (!userMoved) return

        buffer.setLength(0)
        candidates = ArrayList()
        assocMode = false
        consumed = 0
        candidatePage = 0
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

        chinese = shouldUseChinese()
    }

    /** 结合设置与当前子类型（中文 / 英文）决定是否进入拼音模式 */
    private fun shouldUseChinese(): Boolean {
        if (numericEditor || passwordEditor) return false
        if (!Prefs.isChineseMode(this)) return false
        return !Compat.currentSubtypeLocale(this).startsWith("en")
    }

    // ==================================================================
    // 键盘回调
    // ==================================================================
    override fun onKey(key: Key) {
        if (key.type == KeyType.NONE) return

        // 反馈：震动 / 音效开关与系统服务都已缓存，按键路径上无额外读取
        haptics.tick()
        soundFx.tick()

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

        // 英文模式下根据光标位置自动决定句首大写
        if (!chinese && (key.type == KeyType.CHAR || key.type == KeyType.DELETE ||
                key.type == KeyType.SPACE || key.type == KeyType.ENTER)
        ) {
            updateAutoCaps()
        }
    }

    /**
     * 字母键长按：输入对应的数字 / 符号。
     * 因为字母键改为抬起时输入，长按不会先多打一个字母。
     */
    override fun onKeyLongPress(key: Key) {
        if (key.secondary.isEmpty()) return
        haptics.tick()
        soundFx.tick()
        val ic = currentInputConnection ?: return
        // 先把未完成的拼音原样上屏，再输入符号
        flushBufferRaw(ic)
        try {
            ic.commitText(key.secondary, 1)
        } catch (ignored: Throwable) {
            return
        }
        clearCandidates()
        resetOneShotCaps()
    }

    // ==================================================================
    // 字符输入
    // ==================================================================
    private fun handleChar(rawValue: String) {
        if (rawValue.isEmpty()) return

        // 九键模式：数字键进入 T9 缓冲，由引擎按数字串查候选
        if (t9Mode) {
            if (rawValue.length == 1 && rawValue[0] in '0'..'9') {
                appendToBuffer(rawValue, showComposing = false)
                return
            }
        }

        var value = rawValue
        // 中文模式下，逗号/句号直接上屏中文标点
        if (chinese && !numericEditor) {
            if (value == ",") value = "，"
            if (value == ".") value = "。"
        }

        val isLetter = value.length == 1 &&
            ((value[0] in 'a'..'z') || (value[0] in 'A'..'Z'))

        if (chinese && !numericEditor && isLetter) {
            appendToBuffer(value.lowercase(), showComposing = true)
            return
        }

        val ic = currentInputConnection ?: return

        // 上屏标点/数字前，先把未完成的拼音原样提交，避免内容丢失
        flushBufferRaw(ic)
        ic.commitText(value, 1)
        clearCandidates()
        resetOneShotCaps()
    }

    /** 追加到拼音 / 数字缓冲，超过长度上限时先上屏前面的内容，保证按键不被丢弃 */
    private fun appendToBuffer(text: String, showComposing: Boolean) {
        if (buffer.length + text.length > MAX_PINYIN_LENGTH) {
            flushForLongInput()
        }
        if (buffer.length + text.length > MAX_PINYIN_LENGTH) return

        buffer.append(text)
        assocMode = false
        if (showComposing) applyComposing()
        refreshCandidates()
        resetOneShotCaps()
    }

    /**
     * 拼音超长处理：优先上屏首候选，没有候选时上屏原始拼音；
     * 如果仍超过上限，再把剩余部分原样提交，绝不会静默丢弃新按键。
     */
    private fun flushForLongInput() {
        val ic = currentInputConnection
        if (ic == null) {
            buffer.setLength(0)
            clearCandidates()
            return
        }
        if (candidates.isNotEmpty()) {
            chooseCandidate(0)
        } else {
            flushBufferRaw(ic)
            clearCandidates()
        }
        if (buffer.length >= MAX_PINYIN_LENGTH) {
            flushBufferRaw(ic)
            clearCandidates()
        }
    }

    /** 把 buffer 原样提交到编辑框并清空（不触发候选选择） */
    private fun flushBufferRaw(ic: InputConnection) {
        if (buffer.isEmpty()) return
        try {
            ic.commitText(buffer.toString(), 1)
        } catch (ignored: Throwable) {
            // 极少数输入框不支持 commit，忽略即可
        }
        buffer.setLength(0)
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
    private val t9Mode: Boolean
        get() = layoutId == LayoutId.T9 && chinese && !numericEditor && !passwordEditor

    private fun refreshCandidates() {
        if (buffer.isEmpty()) {
            if (!assocMode) {
                candidates = ArrayList()
                candidatePage = 0
                renderCandidates()
            }
            return
        }
        val limit = if (Compat.isLowRamDevice(this)) LOW_RAM_CANDIDATE_LIMIT else CANDIDATE_LIMIT
        val result = if (t9Mode) {
            engine.queryT9(buffer.toString(), limit)
        } else {
            engine.query(buffer.toString(), limit)
        }
        candidates = result.candidates
        consumed = if (result.consumed > 0) result.consumed else buffer.length
        assocMode = false
        candidatePage = 0
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
            // commitText 会替换掉当前整段 composing 的拼音（九键模式没有 composing，等同插入）
            selectionGuardUntil = SystemClock.uptimeMillis() + SELECTION_GUARD_MS
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
            candidatePage = 0
            showAssociation(word)
        }
    }

    private fun showAssociation(word: String) {
        val list = engine.assocFor(word)
        candidates = list
        assocMode = list.isNotEmpty()
        consumed = 0
        candidatePage = 0
        renderCandidates()
    }

    private fun clearCandidates() {
        candidates = ArrayList()
        assocMode = false
        consumed = 0
        candidatePage = 0
        renderCandidates()
    }

    /** 候选栏翻页：delta 为 -1 / +1 */
    private fun pageCandidates(delta: Int) {
        val pageCount = (candidates.size + PAGE_SIZE - 1) / PAGE_SIZE
        if (pageCount <= 1) return
        val next = (candidatePage + delta).coerceIn(0, pageCount - 1)
        if (next == candidatePage) return
        candidatePage = next
        renderCandidates()
    }

    /**
     * 候选栏渲染：使用复用池，只更新文字、颜色与可见性。
     * 未使用的 View 置为 GONE，避免每次按键都 removeAllViews / new TextView。
     */
    private fun renderCandidates() {
        val box = candidateBox ?: return
        val skin = currentSkin()
        candidateBar?.setBackgroundColor(skin.candidateBarBg)

        val total = candidates.size
        val pageCount = if (total == 0) 0 else (total + PAGE_SIZE - 1) / PAGE_SIZE
        if (candidatePage > pageCount - 1) {
            candidatePage = (pageCount - 1).coerceAtLeast(0)
        }
        val start = candidatePage * PAGE_SIZE
        val end = if (start < total) minOf(start + PAGE_SIZE, total) else start

        var viewIndex = 0
        if (candidatePage > 0) {
            viewIndex = bindCandidateView(box, viewIndex, "‹", TAG_PAGE_LEFT, skin, false)
        }
        for (i in start until end) {
            viewIndex = bindCandidateView(box, viewIndex, candidates[i], i, skin, i == 0)
        }
        if (candidatePage < pageCount - 1) {
            viewIndex = bindCandidateView(box, viewIndex, "›", TAG_PAGE_RIGHT, skin, false)
        }

        while (viewIndex < candidateViews.size) {
            candidateViews[viewIndex].visibility = View.GONE
            viewIndex++
        }
    }

    /** 绑定第 index 个候选 View（不存在则创建），返回下一个可用下标 */
    private fun bindCandidateView(
        box: LinearLayout,
        index: Int,
        label: String,
        tagValue: Int,
        skin: Skin,
        accent: Boolean
    ): Int {
        val view: TextView
        if (index < candidateViews.size) {
            view = candidateViews[index]
        } else {
            view = TextView(this)
            view.textSize = 16f
            view.gravity = Gravity.CENTER
            view.setSingleLine(true)
            val horizontalPadding = Compat.dp(this, 11)
            view.setPadding(horizontalPadding, 0, horizontalPadding, 0)
            view.isClickable = true
            // 点击监听只创建一次，通过 tag 区分候选下标与翻页
            view.setOnClickListener { v ->
                val position = v.tag as? Int ?: return@setOnClickListener
                when (position) {
                    TAG_PAGE_LEFT -> pageCandidates(-1)
                    TAG_PAGE_RIGHT -> pageCandidates(1)
                    else -> chooseCandidate(position)
                }
            }
            box.addView(
                view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            candidateViews.add(view)
        }

        view.visibility = View.VISIBLE
        view.tag = tagValue
        view.text = label
        view.setTextColor(if (accent) skin.accent else skin.keyText)
        return index + 1
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
                flushBufferRaw(ic)
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
            flushBufferRaw(ic)
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

        // 1. 拼音 / 九键缓冲优先
        if (buffer.isNotEmpty()) {
            buffer.deleteCharAt(buffer.length - 1)
            if (buffer.isEmpty()) {
                // 九键模式没有 composing 文本，不需要清理输入框
                if (!t9Mode) {
                    try {
                        ic.commitText("", 1) // 清掉 composing 下划线
                    } catch (ignored: Throwable) {
                        // 忽略
                    }
                }
                clearCandidates()
            } else {
                if (!t9Mode) {
                    try {
                        ic.setComposingText(buffer.toString(), 1)
                    } catch (ignored: Throwable) {
                        // 忽略
                    }
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

    /** 单次大写输入完成后自动复位（仅中文模式；英文模式由光标位置决定） */
    private fun resetOneShotCaps() {
        if (chinese && caps && !capsLock) {
            caps = false
            applyLayout()
        }
    }

    /** 英文模式：根据光标前的内容判断是否需要句首自动大写 */
    private fun updateAutoCaps() {
        if (chinese || capsLock) return
        val ic = currentInputConnection ?: return
        val inputType = currentInputEditorInfo?.inputType ?: InputType.TYPE_CLASS_TEXT
        val mode = try {
            ic.getCursorCapsMode(inputType)
        } catch (t: Throwable) {
            0
        }
        val newCaps = (mode and TextUtils.CAP_MODE_CHARACTERS) != 0 ||
            (mode and TextUtils.CAP_MODE_SENTENCES) != 0
        if (newCaps != caps) {
            caps = newCaps
            applyLayout()
        }
    }

    private fun toggleLanguage() {
        val ic = currentInputConnection
        if (ic != null && buffer.isNotEmpty()) {
            flushBufferRaw(ic)
        }
        chinese = !chinese
        Prefs.setChineseMode(this, chinese)
        caps = false
        capsLock = false
        layoutId = LayoutId.LETTERS
        clearCandidates()
        applyLayout()
        if (!chinese) updateAutoCaps()
    }

    private fun switchLayout(target: String) {
        val ic = currentInputConnection
        if (ic != null && buffer.isNotEmpty()) {
            flushBufferRaw(ic)
            clearCandidates()
        }
        layoutId = when (target) {
            "T9" -> LayoutId.T9
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
    private fun currentSkin(): Skin {
        val cached = skinCache
        if (cached != null) return cached
        val created = SkinFactory.create(
            this,
            Prefs.isDarkSkin(this),
            Prefs.accentColor(this),
            Prefs.keyRadiusDp(this)
        )
        skinCache = created
        return created
    }

    private fun applySkin() {
        skinCache = null
        val skin = currentSkin()
        rootView?.setBackgroundColor(skin.keyboardBg)
        candidateBar?.setBackgroundColor(skin.candidateBarBg)
        applyLayout()
        renderCandidates()
    }

    private fun updateKeyboardHeight() {
        val keyboard = keyboardView ?: return
        val params = keyboard.layoutParams as? LinearLayout.LayoutParams ?: return
        params.height = Prefs.keyboardHeightPx(this)
        keyboard.layoutParams = params
        keyboard.requestLayout()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Prefs.KEY_DARK_SKIN,
            Prefs.KEY_ACCENT_COLOR,
            Prefs.KEY_KEY_RADIUS -> applySkin()

            Prefs.KEY_KEYBOARD_HEIGHT -> updateKeyboardHeight()

            Prefs.KEY_VIBRATE -> haptics.refresh()

            Prefs.KEY_SOUND -> soundFx.refresh()

            Prefs.KEY_CHINESE_MODE -> {
                // 用户在应用内主动切换语言时不再受子类型限制，否则在英文子类型下无法切到中文
                chinese = Prefs.isChineseMode(this) && !numericEditor && !passwordEditor
                applyLayout()
            }

            UserDictStore.KEY_WORDS,
            UserDictStore.KEY_PHRASES -> {
                engine.reloadUserData()
                refreshCandidates()
            }

            else -> {
                // 其它设置无需刷新界面
            }
        }
    }

    companion object {
        /** 候选栏每页数量（超出后通过 ‹ › 翻页） */
        private const val PAGE_SIZE = 9
        /** 普通机型候选总数（约 4 页） */
        private const val CANDIDATE_LIMIT = 36
        /** 低内存机型候选总数（约 2 页） */
        private const val LOW_RAM_CANDIDATE_LIMIT = 18
        /** 拼音缓冲最大长度 */
        private const val MAX_PINYIN_LENGTH = 24

        /** 候选 View 的 tag：左翻页 / 右翻页，非负数表示候选下标 */
        private const val TAG_PAGE_LEFT = -1
        private const val TAG_PAGE_RIGHT = -2

        /** 自身 commitText 后忽略光标回调的时间窗口 */
        private const val SELECTION_GUARD_MS = 150L
    }
}
