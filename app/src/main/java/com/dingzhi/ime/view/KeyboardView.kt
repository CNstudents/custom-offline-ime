package com.dingzhi.ime.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import com.dingzhi.ime.core.Prefs
import com.dingzhi.ime.input.Key
import com.dingzhi.ime.input.KeyType
import com.dingzhi.ime.theme.Skin
import com.dingzhi.ime.theme.SkinFactory
import com.dingzhi.ime.util.Compat

/**
 * 自绘键盘视图。
 *
 * 为什么不用系统 KeyboardView：
 * - 系统 KeyboardView 在 API 19 与 API 30+ 的默认样式、文字大小、震动反馈都不一致；
 * - 自绘可以精确控制圆角、配色、按下态、候选高亮，保证 4.4 到 14 的视觉一致；
 * - 只使用 Canvas 的 drawRoundRect / drawText，API 19 完全支持，不需要新 API。
 *
 * 触摸逻辑：
 * - 手指按下立即触发按键（响应更快）；
 * - 退格键按住 420ms 后每 45ms 连续删除；
 * - 手指滑出按键时取消高亮，但不会取消已经触发的输入。
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onKey(key: Key)
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

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var pressedKey: Key? = null

    private val density = resources.displayMetrics.density
    private val keyGap = 5f * density
    private val sidePadding = 3f * density
    private val rowGap = 6f * density
    private val verticalPadding = 4f * density

    private val handler = Handler(Looper.getMainLooper())

    /** 退格长按连续删除 */
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = pressedKey ?: return
            if (key.type != KeyType.DELETE) return
            listener?.onKey(key)
            handler.postDelayed(this, 45L)
        }
    }

    // ==================================================================
    // 布局与测量
    // ==================================================================
    fun setRows(list: List<List<Key>>) {
        rows = list
        pressedKey = null
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

    /** 根据当前尺寸计算每个按键的矩形 */
    private fun buildRects() {
        keyRects.clear()
        if (width <= 0 || height <= 0 || rows.isEmpty()) return

        val rowHeight = (height - verticalPadding * 2 - rowGap * (rows.size - 1)) / rows.size
        var top = verticalPadding

        for (row in rows) {
            var totalWeight = 0f
            for (key in row) totalWeight += key.weight
            val available = width - sidePadding * 2 - keyGap * (row.size - 1)

            val rects = ArrayList<RectF>(row.size)
            var left = sidePadding
            for (key in row) {
                val keyWidth = if (totalWeight > 0f) available * (key.weight / totalWeight) else 0f
                rects.add(RectF(left, top, left + keyWidth, top + rowHeight))
                left += keyWidth + keyGap
            }
            keyRects.add(rects)
            top += rowHeight + rowGap
        }
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
            for (i in row.indices) {
                val key = row[i]
                if (key.type == KeyType.NONE) continue
                drawKey(canvas, key, rects[i])
            }
        }
    }

    private fun drawKey(canvas: Canvas, key: Key, rect: RectF) {
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val isFunc = key.type != KeyType.CHAR
        // 回车键与高亮键（Shift 开启 / 中文模式）使用主题色
        val accentBg = key.type == KeyType.ENTER || key.highlight

        bgPaint.color = when {
            accentBg -> skin.accent
            isFunc -> skin.funcKeyBg
            else -> skin.keyBg
        }

        val radius = Math.min(skin.keyRadiusPx, Math.min(rect.width(), rect.height()) / 2f)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        // 按下蒙层
        if (pressedKey === key) {
            bgPaint.color = skin.pressed
            canvas.drawRoundRect(rect, radius, radius, bgPaint)
        }

        val label = key.label
        if (label.isEmpty()) return

        var textSize = if (isFunc) FUNC_TEXT_DP * density else CHAR_TEXT_DP * density
        textPaint.textSize = textSize
        var textWidth = textPaint.measureText(label)

        // 文字过宽时等比缩小，避免「退格 / 回车」在窄屏上溢出
        val maxWidth = rect.width() - KEY_TEXT_PADDING_DP * density * 2
        if (textWidth > maxWidth && textWidth > 0f && maxWidth > 0f) {
            textSize = textSize * maxWidth / textWidth
            textPaint.textSize = textSize
            textWidth = textPaint.measureText(label)
        }

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
                val key = findKey(event.x, event.y)
                pressedKey = key
                if (key != null) {
                    listener?.onKey(key)
                    if (key.type == KeyType.DELETE) {
                        handler.removeCallbacks(repeatRunnable)
                        handler.postDelayed(repeatRunnable, 420L)
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val key = findKey(event.x, event.y)
                if (key !== pressedKey) {
                    pressedKey = key
                    handler.removeCallbacks(repeatRunnable)
                    invalidate()
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                handler.removeCallbacks(repeatRunnable)
                invalidate()
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

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val CHAR_TEXT_DP = 20f
        private const val FUNC_TEXT_DP = 12f
        private const val KEY_TEXT_PADDING_DP = 4f
    }
}
