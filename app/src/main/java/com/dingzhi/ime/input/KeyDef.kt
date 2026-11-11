package com.dingzhi.ime.input

/**
 * 按键类型。
 * CHAR   普通字符键（字母 / 数字 / 符号 / 中文标点）
 * SHIFT  大小写切换
 * DELETE 退格（长按连续删除）
 * ENTER  回车 / 执行输入框动作
 * SPACE  空格
 * LANG   中英文切换
 * LAYOUT 切换键盘布局（底部功能键）
 * NONE   占位（用于第二行左右留白）
 */
enum class KeyType { CHAR, SHIFT, DELETE, ENTER, SPACE, LANG, LAYOUT, NONE }

/** 键盘布局标识 */
enum class LayoutId { LETTERS, NUMBERS, SYMBOLS }

/**
 * 一个按键的定义。
 * @param label     显示文案（大写字母 / 中文功能名 / 符号）
 * @param type      按键类型
 * @param value     字符键的插入内容，或 LAYOUT 键的目标布局名
 * @param weight    宽度权重，同一行内按权重分配宽度
 * @param highlight 是否用主题色高亮（用于 Shift 开启状态、中文模式等）
 */
class Key(
    val label: String,
    val type: KeyType,
    val value: String = "",
    val weight: Float = 1f,
    val highlight: Boolean = false
)

/**
 * 键盘布局定义（纯代码生成，不依赖 XML，保证 API 19 的渲染一致性）。
 *
 * 布局说明：
 * - 字母页：26 键全拼 / 英文，底部带 123、中/英、逗号、空格、句号、回车
 * - 数字页：数字 + 常用符号 + 符号页入口
 * - 符号页：中英文符号 + 数字页入口
 */
object KeyboardLayouts {

    fun build(layout: LayoutId, caps: Boolean, chinese: Boolean): List<List<Key>> {
        return when (layout) {
            LayoutId.LETTERS -> letters(caps, chinese)
            LayoutId.NUMBERS -> numbers(chinese)
            LayoutId.SYMBOLS -> symbols(chinese)
        }
    }

    // ---------------- 字母页 ----------------
    private fun letters(caps: Boolean, chinese: Boolean): List<List<Key>> {
        val rows = ArrayList<List<Key>>(4)

        rows.add(letterRow("qwertyuiop", caps))

        // 第二行左右各留半格，形成经典错位
        val row2 = ArrayList<Key>(11)
        row2.add(Key("", KeyType.NONE, weight = 0.5f))
        row2.addAll(letterRow("asdfghjkl", caps))
        row2.add(Key("", KeyType.NONE, weight = 0.5f))
        rows.add(row2)

        // 第三行：Shift + 7 个字母 + 退格
        val row3 = ArrayList<Key>(9)
        row3.add(Key("↑", KeyType.SHIFT, weight = 1.5f, highlight = caps))
        row3.addAll(letterRow("zxcvbnm", caps))
        row3.add(Key("退格", KeyType.DELETE, weight = 1.6f))
        rows.add(row3)

        rows.add(bottomRow(chinese, "123", LayoutId.NUMBERS))
        return rows
    }

    // ---------------- 数字页 ----------------
    private fun numbers(chinese: Boolean): List<List<Key>> {
        val rows = ArrayList<List<Key>>(4)

        rows.add(charRow("1234567890"))

        val row2 = ArrayList<Key>(10)
        val symbols2 = arrayOf("@", "#", "$", "%", "&", "*", "-", "+", "(", ")")
        for (s in symbols2) row2.add(Key(s, KeyType.CHAR, s))
        rows.add(row2)

        val row3 = ArrayList<Key>(9)
        row3.add(Key("符号", KeyType.LAYOUT, "SYMBOLS", weight = 1.7f))
        val symbols3 = arrayOf("\"", "'", ":", ";", "/", "!", "?")
        for (s in symbols3) row3.add(Key(s, KeyType.CHAR, s))
        row3.add(Key("退格", KeyType.DELETE, weight = 1.7f))
        rows.add(row3)

        rows.add(bottomRow(chinese, "ABC", LayoutId.LETTERS))
        return rows
    }

    // ---------------- 符号页 ----------------
    private fun symbols(chinese: Boolean): List<List<Key>> {
        val rows = ArrayList<List<Key>>(4)

        rows.add(charRow("[ ] { } # % ^ * + =".split(" ").toTypedArray()))

        val row2 = ArrayList<Key>(10)
        val symbols2 = arrayOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•")
        for (s in symbols2) row2.add(Key(s, KeyType.CHAR, s))
        rows.add(row2)

        val row3 = ArrayList<Key>(9)
        row3.add(Key("123", KeyType.LAYOUT, "NUMBERS", weight = 1.7f))
        val symbols3 = arrayOf("，", "。", "？", "！", "：", "；")
        for (s in symbols3) row3.add(Key(s, KeyType.CHAR, s))
        row3.add(Key("退格", KeyType.DELETE, weight = 1.8f))
        rows.add(row3)

        rows.add(bottomRow(chinese, "ABC", LayoutId.LETTERS))
        return rows
    }

    // ---------------- 公共行 ----------------
    private fun letterRow(letters: String, caps: Boolean): List<Key> {
        val row = ArrayList<Key>(letters.length)
        for (c in letters) {
            val lower = c.toString()
            val label = if (caps) lower.uppercase() else lower
            // value 直接带上大小写，英文模式下可以直接上屏
            row.add(Key(label, KeyType.CHAR, label))
        }
        return row
    }

    private fun charRow(chars: String): List<Key> {
        val row = ArrayList<Key>(chars.length)
        for (c in chars) {
            val text = c.toString()
            row.add(Key(text, KeyType.CHAR, text))
        }
        return row
    }

    private fun charRow(chars: Array<String>): List<Key> {
        val row = ArrayList<Key>(chars.size)
        for (c in chars) {
            if (c.isEmpty()) continue
            row.add(Key(c, KeyType.CHAR, c))
        }
        return row
    }

    /**
     * 底部功能行。
     * @param layoutLabel 左侧布局切换键文案（123 / ABC）
     * @param target      左侧布局切换键目标
     */
    private fun bottomRow(chinese: Boolean, layoutLabel: String, target: LayoutId): List<Key> {
        val row = ArrayList<Key>(6)
        row.add(Key(layoutLabel, KeyType.LAYOUT, target.name, weight = 1.4f))
        // 显示当前语言（中 / 英），中文时用主题色高亮
        row.add(Key(if (chinese) "中" else "英", KeyType.LANG, weight = 1.4f, highlight = chinese))
        row.add(Key("，", KeyType.CHAR, ",", weight = 1.0f))
        row.add(Key("空格", KeyType.SPACE, weight = 3.4f))
        row.add(Key("。", KeyType.CHAR, ".", weight = 1.0f))
        row.add(Key("回车", KeyType.ENTER, weight = 1.8f))
        return row
    }
}
