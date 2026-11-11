package com.dingzhi.ime.core

import android.content.Context

/**
 * 用户自定义词条 / 快捷短语的本地存储。
 *
 * 存储位置：SharedPreferences（应用私有目录），
 * 每条记录一行，字段之间用 \t 分隔：
 *   词条： pinyin \t 词语
 *   短语： 编码   \t 内容
 *
 * 这样做的原因：
 * - API 19 到 API 34 都不需要外部存储权限，天然规避分区存储差异；
 * - 单条内容做长度限制，避免 SharedPreferences 过大导致老机型读写变慢。
 */
object UserDictStore {

    const val KEY_WORDS = "user_word_list"
    const val KEY_PHRASES = "user_phrase_list"

    /** 单类最多条数，防止 SharedPreferences 无限膨胀（设置页也会读取该值做提示） */
    const val MAX_ITEMS = 500

    class Word(val pinyin: String, val word: String)
    class Phrase(val code: String, val text: String)

    // ---------------- 读取 ----------------
    fun words(context: Context): ArrayList<Word> {
        val result = ArrayList<Word>()
        val raw = Prefs.sp(context).getString(KEY_WORDS, "") ?: ""
        for (line in raw.split("\n")) {
            val index = line.indexOf('\t')
            if (index <= 0 || index >= line.length - 1) continue
            val pinyin = line.substring(0, index).trim()
            val word = line.substring(index + 1).trim()
            if (pinyin.isNotEmpty() && word.isNotEmpty()) {
                result.add(Word(pinyin, word))
            }
        }
        return result
    }

    fun phrases(context: Context): ArrayList<Phrase> {
        val result = ArrayList<Phrase>()
        val raw = Prefs.sp(context).getString(KEY_PHRASES, "") ?: ""
        for (line in raw.split("\n")) {
            val index = line.indexOf('\t')
            if (index <= 0 || index >= line.length - 1) continue
            val code = line.substring(0, index).trim()
            val text = line.substring(index + 1).trim()
            if (code.isNotEmpty() && text.isNotEmpty()) {
                result.add(Phrase(code, text))
            }
        }
        return result
    }

    // ---------------- 写入 ----------------
    /**
     * 添加自定义词条，返回是否成功。
     * 拼音只保留 a-z，长度 1~30；词语长度 1~20。
     */
    fun addWord(context: Context, pinyin: String, word: String): Boolean {
        val py = pinyin.lowercase().filter { it in 'a'..'z' }
        val w = sanitize(word)
        if (py.isEmpty() || py.length > 30 || w.isEmpty() || w.length > 20) return false
        val line = py + "\t" + w
        return appendLine(context, KEY_WORDS, line)
    }

    /**
     * 添加快捷短语，返回是否成功。
     * 编码只保留 a-z/0-9，长度 1~10；内容长度 1~200。
     */
    fun addPhrase(context: Context, code: String, text: String): Boolean {
        val c = code.lowercase().filter { (it in 'a'..'z') || (it in '0'..'9') }
        val t = sanitize(text)
        if (c.isEmpty() || c.length > 10 || t.isEmpty() || t.length > 200) return false
        val line = c + "\t" + t
        return appendLine(context, KEY_PHRASES, line)
    }

    /** 清空全部自定义词条与快捷短语 */
    fun clearAll(context: Context) {
        Prefs.sp(context).edit()
            .remove(KEY_WORDS)
            .remove(KEY_PHRASES)
            .apply()
    }

    /** 删除单条自定义词条（拼音 + 词语精确匹配） */
    fun removeWord(context: Context, pinyin: String, word: String) {
        val target = pinyin.trim().lowercase() + "\t" + sanitize(word)
        removeLine(context, KEY_WORDS, target)
    }

    /** 删除单条快捷短语（编码 + 内容精确匹配） */
    fun removePhrase(context: Context, code: String, text: String) {
        val target = code.trim().lowercase() + "\t" + sanitize(text)
        removeLine(context, KEY_PHRASES, target)
    }

    /**
     * 导出为纯文本，格式：
     *   [words]
     *   pinyin \t word
     *   [phrases]
     *   code \t text
     * 用制表符分隔，便于用记事本直接编辑后再导入。
     */
    fun exportText(context: Context): String {
        val sb = StringBuilder()
        sb.append("# 定制版输入法词库导出\n")
        sb.append("# 词条格式：拼音<TAB>词语；短语格式：编码<TAB>内容\n")
        sb.append("[words]\n")
        for (item in words(context)) {
            sb.append(item.pinyin).append('\t').append(item.word).append('\n')
        }
        sb.append("[phrases]\n")
        for (item in phrases(context)) {
            sb.append(item.code).append('\t').append(item.text).append('\n')
        }
        return sb.toString()
    }

    /**
     * 从纯文本导入，返回尝试导入的条数。
     * 支持 [words] / [phrases] 分节；没有分节时默认按词条处理。
     */
    fun importText(context: Context, text: String): Int {
        var phraseSection = false
        var count = 0
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.equals("[words]", ignoreCase = true)) {
                phraseSection = false
                continue
            }
            if (line.equals("[phrases]", ignoreCase = true)) {
                phraseSection = true
                continue
            }
            val index = line.indexOf('\t')
            if (index <= 0 || index >= line.length - 1) continue
            val left = line.substring(0, index).trim()
            val right = line.substring(index + 1).trim()
            val ok = if (phraseSection) addPhrase(context, left, right) else addWord(context, left, right)
            if (ok) count++
        }
        return count
    }

    // ---------------- 内部实现 ----------------
    private fun removeLine(context: Context, key: String, target: String) {
        val prefs = Prefs.sp(context)
        val current = prefs.getString(key, "") ?: ""
        if (current.isEmpty()) return
        val kept = ArrayList<String>()
        for (line in current.split("\n")) {
            if (line.isNotEmpty() && line != target) kept.add(line)
        }
        prefs.edit().putString(key, joinLines(kept)).apply()
    }

    private fun appendLine(context: Context, key: String, line: String): Boolean {
        val prefs = Prefs.sp(context)
        val current = prefs.getString(key, "") ?: ""
        val lines: ArrayList<String> =
            if (current.isEmpty()) ArrayList() else ArrayList(current.split("\n"))
        if (lines.contains(line)) return true          // 已存在，视为成功
        if (lines.size >= MAX_ITEMS) return false      // 超过上限
        lines.add(line)
        prefs.edit().putString(key, joinLines(lines)).apply()
        return true
    }

    private fun joinLines(lines: ArrayList<String>): String {
        val sb = StringBuilder()
        for (i in lines.indices) {
            if (i > 0) sb.append('\n')
            sb.append(lines[i])
        }
        return sb.toString()
    }

    /** 去掉可能破坏存储格式的换行/制表符 */
    private fun sanitize(value: String): String =
        value.replace("\t", " ").replace("\n", " ").replace("\r", " ").trim()
}
