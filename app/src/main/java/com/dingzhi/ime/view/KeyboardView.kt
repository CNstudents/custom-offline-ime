package com.dingzhi.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import com.dingzhi.ime.R
import com.dingzhi.ime.core.Prefs
import com.dingzhi.ime.input.Key
import com.dingzhi.ime.input.KeyType
import com.dingzhi.ime.theme.Skin
import com.dingzhi.ime.theme.SkinFactory
import com.dingzhi.ime.util.Compat
import kotlin.math.abs
import kotlin.math.min

/**
 * 自绘键盘视图。
 *
 * 为什么不用系统 KeyboardView：
 * - 系统 KeyboardView 在 API 19 与 API 30+ 的默认样式、文字大小、震动反馈都不一致；
 * - 自绘可以精确控制圆角、配色、按下态、候选高亮，保证 4.4 到 14 的视觉一致；
 * - 只使用 Canvas 的 drawRoundRect / drawText，API 19 完全支持，不需要新 API。
 *
 * 性能：
 * - 按键矩形与文字宽度在布局阶段算好并缓存，onDraw 只做纯绘制，不再 measureText；
 * - 按下 / 抬起只重绘受影响的两个按键矩形，不整屏刷新；
 * - 退格长按连删时同样只重绘按键本身。
 *
 * 交互：
 * - 普通功能键按下立即触发；
 * - 字母键抬起时触发，长按 350ms 输入对应数字/符号，避免长按多打一个字母；
 * - 退格键按住 420ms 后每 45ms 连续删除；
 * - 手指移动超过系统 touch slop 后重新定位高亮，避免误触导致的高亮错位。
 *
 * 无障碍：
 * - 通过 ExploreByTouchHelper 为每个按键生成虚拟节点，TalkBack 可以逐键朗读与点击。
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(key: Key)

        /** 字母键长按输入数字/符号；默认不处理 */
        fun onKeyLongPress(key: Key) {}
    }

    var listener: Listener? = null

    var skin: Skin = SkinFactory.create(context, false, Prefs.DEFAULT_ACCENT, Prefs.DEFAULT_RADIUS_DP)
        set(value) {
            field = value
            invalidate()
        }

    /** 当前布局（每行一组按键） */
    private var rows: List<List<Key>> = ArrayList()

    /** 与 rows 一一对应的按键矩形 */
    private val keyRects = ArrayList<ArrayList<RectF>>()

    /** 与 rows 对应的文字绘制尺寸与宽度缓存，避免 onDraw 里 measureText */
    private val textSizes = ArrayList<ArrayList<Float>>()
    private val textWidths = ArrayList<ArrayList<Float>>()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var pressedKey: Key? = null
    private var pendingKey: Key? = null
    private var longPressFired = false

    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private val keyGap = resources.getDimension(R.dimen.key_gap)
    private val sidePadding = resources.getDimension(R.dimen.key_side_padding)
    private val rowGap = resources.getDimension(R.dimen.key_row_gap)
    private val verticalPadding = resources.getDimension(R.dimen.key_vertical_padding)
    private val charTextSize = resources.getDimension(R.dimen.key_char_text)
    private val funcTextSize = resources.getDimension(R.dimen.key_func_text)
    private val textPadding = resources.getDimension(R.dimen.key_text_padding)

    private val handler = Handler(Looper.getMainLooper())

    private val accessibilityHelper by lazy { KeyTouchHelper(this) }

    init {
        isFocusable = true
        ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
    }

    /** 退格长按连续删除 */
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = pressedKey ?: return
            if (key.type != KeyType.DELETE) return
            listener?.onKey(key)
            handler.postDelayed(this, DELETE_REPEAT_INTERVAL_MS)
        }
    }

    /** 字母键长按输入数字 / 符号 */
    private val longPressRunnable = Runnable {
        val key = pendingKey ?: return@Runnable
        if (key.secondary.isEmpty()) return@Runnable
        longPressFired = true
        pendingKey = null
        listener?.onKeyLongPress(key)
    }

    // ==================================================================
    // 布局与测量
    // ==================================================================
    fun setRows(list: List<List<Key>>) {
        rows = list
        pressedKey = null
        pendingKey = null
        longPressFired = false
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(longPressRunnable)
        buildRects()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        // 父容器通常给固定高度；给 wrap_content 时退回默认 4 行高度
        val height = resolveSize(Compat.dp(context, 240), heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildRects()
    }

    /** 根据当前尺寸计算每个按键的矩形与文字尺寸 */
    private fun buildRects() {
        keyRects.clear()
        textSizes.clear()
        textWidths.clear()
        if (width <= 0 || height <= 0 || rows.isEmpty()) return

        val rowHeight = (height - verticalPadding * 2 - rowGap * (rows.size - 1)) / rows.size
        var top = verticalPadding

        for (row in rows) {
            var totalWeight = 0f
            for (key in row) totalWeight += key.weight
            val available = width - sidePadding * 2 - keyGap * (row.size - 1)

            val rects = ArrayList<RectF>(row.size)
            val sizes = ArrayList<Float>(row.size)
            val widths = ArrayList<Float>(row.size)
            var left = sidePadding
            for (key in row) {
                val keyWidth = if (totalWeight > 0f) available * (key.weight / totalWeight) else 0f
                val rect = RectF(left, top, left + keyWidth, top + rowHeight)
                rects.add(rect)

                // 文字尺寸只在布局阶段计算一次，onDraw 直接使用缓存
                val result = measureLabel(key, rect)
                sizes.add(result.first)
                widths.add(result.second)

                left += keyWidth + keyGap
            }
            keyRects.add(rects)
            textSizes.add(sizes)
            textWidths.add(widths)
            top += rowHeight + rowGap
        }
    }

    /** 返回 (文字大小, 文字宽度)，文字过宽时等比缩小 */
    private fun measureLabel(key: Key, rect: RectF): Pair<Float, Float> {
        if (key.label.isEmpty() || key.type == KeyType.NONE || rect.width() <= 0f) {
            return Pair(charTextSize, 0f)
        }
        val base = if (key.type == KeyType.CHAR) charTextSize else funcTextSize
        textPaint.textSize = base
        var width = textPaint.measureText(key.label)
        var size = base
        val maxWidth = rect.width() - textPadding * 2
        if (width > maxWidth && width > 0f && maxWidth > 0f) {
            size = base * maxWidth / width
            textPaint.textSize = size
            width = textPaint.measureText(key.label)
        }
        return Pair(size, width)
    }

    // ==================================================================
    // 绘制
    // ==================================================================
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(skin.keyboardBg)
        if (keyRects.isEmpty()) return

        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            val rects = keyRects.getOrNull(rowIndex) ?: continue
            val sizes = textSizes.getOrNull(rowIndex) ?: continue
            val widths = textWidths.getOrNull(rowIndex) ?: continue
            for (i in row.indices) {
                val key = row[i]
                if (key.type == KeyType.NONE) continue
                drawKey(canvas, key, rects[i], sizes[i], widths[i])
            }
        }
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF, textSize: Float, textWidth: Float) {
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val isFunc = key.type != KeyType.CHAR
        // 回车键与高亮键（Shift 开启 / 中文模式）使用主题色
        val accentBg = key.type == KeyType.ENTER || key.highlight

        bgPaint.color = when {
            accentBg -> skin.accent
            isFunc -> skin.funcKeyBg
            else -> skin.keyBg
        }

        val radius = min(skin.keyRadiusPx, min(rect.width(), rect.height()) / 2f)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // 按下蒙层
        if (pressedKey === key) {
            bgPaint.color = skin.pressed
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
        }

        val label = key.label
        if (label.isEmpty()) return

        textPaint.textSize = textSize
        textPaint.color = when {
            accentBg -> skin.onAccent
            isFunc -> skin.funcText
            else -> skin.keyText
        }

        val metrics = textPaint.fontMetrics
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, rect.centerX() - textWidth / 2f, baseline, textPaint)
    }

    // ==================================================================
    // 触摸
    // ==================================================================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val old = pressedKey
                val key = findKey(event.x, event.y)
                pressedKey = key
                pendingKey = null
                longPressFired = false
                downX = event.x
                downY = event.y
                handler.removeCallbacks(repeatRunnable)
                handler.removeCallbacks(longPressRunnable)

                if (key != null) {
                    when {
                        key.type == KeyType.DELETE -> {
                            listener?.onKey(key)
                            handler.postDelayed(repeatRunnable, DELETE_REPEAT_DELAY_MS)
                        }
                        key.secondary.isNotEmpty() -> {
                            // 字母键：先不输入，抬起时输入字母，长按则输入数字 / 符号
                            pendingKey = key
                            handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                        }
                        else -> listener?.onKey(key)
                    }
                }
                if (old !== key) invalidateKey(old)
                invalidateKey(key)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressedKey == null) return true
                // 在系统 touch slop 内不改变高亮，避免轻微抖动导致高亮闪烁
                if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                    return true
                }
                val key = findKey(event.x, event.y)
                if (key !== pressedKey) {
                    val old = pressedKey
                    pressedKey = key
                    // 滑出原键后取消尚未触发的字母输入与长按
                    pendingKey = null
                    longPressFired = false
                    handler.removeCallbacks(repeatRunnable)
                    handler.removeCallbacks(longPressRunnable)
                    invalidateKey(old)
                    invalidateKey(key)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                val pending = pendingKey
                if (!longPressFired && pending != null) {
                    listener?.onKey(pending)
                }
                pendingKey = null
                longPressFired = false
                val old = pressedKey
                pressedKey = null
                handler.removeCallbacks(repeatRunnable)
                handler.removeCallbacks(longPressRunnable)
                invalidateKey(old)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pendingKey = null
                longPressFired = false
                val old = pressedKey
                pressedKey = null
                handler.removeCallbacks(repeatRunnable)
                handler.removeCallbacks(longPressRunnable)
                invalidateKey(old)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findKey(x: Float, y: Float): Key? {
        for (rowIndex in keyRects.indices) {
            val rects = keyRects[rowIndex]
            val row = rows.getOrNull(rowIndex) ?: continue
            for (i in rects.indices) {
                if (i >= row.size) continue
                if (rects[i].contains(x, y)) return row[i]
            }
        }
        return null
    }

    private fun findRect(target: Key): RectF? {
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            for (i in row.indices) {
                if (row[i] === target) {
                    return keyRects.getOrNull(rowIndex)?.getOrNull(i)
                }
            }
        }
        return null
    }

    /** 只重绘指定按键所在的矩形区域 */
    private fun invalidateKey(key: Key?) {
        if (key == null) return
        val rect = findRect(key) ?: return
        val pad = keyGap
        invalidate(
            (rect.left - pad).toInt(),
            (rect.top - pad).toInt(),
            (rect.right + pad).toInt(),
            (rect.bottom + pad).toInt()
        )
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    // ==================================================================
    // 无障碍
    // ==================================================================
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        return accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    private fun idOf(key: Key): Int {
        for (rowIndex in rows.indices) {
            val row = rows[rowIndex]
            for (i in row.indices) {
                if (row[i] === key) return rowIndex * VIRTUAL_ID_ROW_FACTOR + i
            }
        }
        return ExploreByTouchHelper.INVALID_ID
    }

    private fun accessibilityLabel(key: Key): String {
        if (key.label.isNotEmpty()) return key.label
        return when (key.type) {
            KeyType.SHIFT -> "大写"
            KeyType.DELETE -> "退格"
            KeyType.ENTER -> "回车"
            KeyType.SPACE -> "空格"
            KeyType.LANG -> "中英文切换"
            KeyType.LAYOUT -> "切换布局"
            KeyType.NONE -> ""
            KeyType.CHAR -> key.value
        }
    }

    private inner class KeyTouchHelper(host: View) : ExploreByTouchHelper(host) {

        override fun getVirtualViewAt(x: Float, y: Float): Int {
            val key = findKey(x, y) ?: return ExploreByTouchHelper.HOST_ID
            if (key.type == KeyType.NONE) return ExploreByTouchHelper.HOST_ID
            return idOf(key)
        }

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            for (rowIndex in rows.indices) {
                val row = rows[rowIndex]
                for (i in row.indices) {
                    if (row[i].type != KeyType.NONE) {
                        virtualViewIds.add(rowIndex * VIRTUAL_ID_ROW_FACTOR + i)
                    }
                }
            }
        }

        override fun onPopulateNodeForVirtualView(
            virtualViewId: Int,
            node: AccessibilityNodeInfoCompat
        ) {
            val rowIndex = virtualViewId / VIRTUAL_ID_ROW_FACTOR
            val column = virtualViewId % VIRTUAL_ID_ROW_FACTOR
            val key = rows.getOrNull(rowIndex)?.getOrNull(column)
            if (key == null || key.type == KeyType.NONE) {
                node.contentDescription = "空白"
                node.text = "空白"
                node.setBoundsInParent(Rect())
                return
            }
            val label = accessibilityLabel(key)
            node.contentDescription = label
            node.text = label
            node.className = "android.widget.Button"
            node.isClickable = true
            node.isFocusable = true
            node.isEnabled = true

            val rect = keyRects.getOrNull(rowIndex)?.getOrNull(column)
            node.setBoundsInParent(
                if (rect == null) Rect()
                else Rect(
                    rect.left.toInt(),
                    rect.top.toInt(),
                    rect.right.toInt(),
                    rect.bottom.toInt()
                )
            )
        }

        override fun onPerformActionForVirtualView(
            virtualViewId: Int,
            action: Int,
            arguments: Bundle?
        ): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            val rowIndex = virtualViewId / VIRTUAL_ID_ROW_FACTOR
            val column = virtualViewId % VIRTUAL_ID_ROW_FACTOR
            val key = rows.getOrNull(rowIndex)?.getOrNull(column) ?: return false
            listener?.onKey(key)
            return true
        }
    }

    companion object {
        private const val DELETE_REPEAT_DELAY_MS = 420L
        private const val DELETE_REPEAT_INTERVAL_MS = 45L
        private const val LONG_PRESS_MS = 350L
        /** 虚拟节点 id = 行号 * 该系数 + 列号，按键列数远小于 100 */
        private const val VIRTUAL_ID_ROW_FACTOR = 100
    }
}
