package com.dingzhi.ime.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 本地拼音引擎（完全离线）。
 *
 * 数据来源：assets/dict/pinyin.txt（词库）、assets/dict/assoc.txt（联想词库）。
 * 加载策略：首次进入输入法时在后台线程懒加载，加载完成后主线程回调；
 *          低内存机型上减少候选数量与索引长度，避免老设备 OOM。
 *
 * 支持能力：
 * 1. 全拼：输入 nihao -> 你好
 * 2. 简拼：输入 nh   -> 你好（仅对多音节词建立简拼索引）
 * 3. 联想：上屏「你好」后显示「你好吗 / 你好呀 ...」
 * 4. 前缀：输入 nih -> 你好（词库中以 nih 开头的词条）
 * 5. 用户词条 / 快捷短语：优先级最高
 */
class PinyinEngine(private val context: Context) {

    /** 词条：词语 + 完整拼音 + 频率 */
    class Entry(val word: String, val pinyin: String, val freq: Int)

    /**
     * 查询结果。
     * consumed 表示这次候选消耗掉输入串的前多少个字母，
     * 用来支持「nihaoma -> 选你好后剩余 ma 继续输入」。
     */
    class Result(val candidates: ArrayList<String>, val consumed: Int)

    // 完整拼音 -> 词条列表
    private val byPinyin = HashMap<String, ArrayList<Entry>>(1024)
    // 简拼 -> 词条列表
    private val byInitials = HashMap<String, ArrayList<Entry>>(512)
    // 排序后的拼音 Key 列表，用于前缀二分查找
    private val sortedKeys = ArrayList<String>(1024)
    // 联想：前词 -> 后续词
    private val assoc = HashMap<String, ArrayList<String>>(512)
    // 快捷短语：编码 -> 内容
    private val phraseCodes = HashMap<String, ArrayList<String>>(32)
    // 用户自定义词条
    private val userWords = ArrayList<Entry>(32)

    private val whitespace = Regex("\\s+")
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var loaded = false

    val isReady: Boolean
        get() = loaded

    /** 词库是否已加载完成（供设置页展示） */
    fun loadAsync(onDone: () -> Unit) {
        if (loaded) {
            onDone()
            return
        }
        val thread = Thread {
            try {
                loadInternal()
            } catch (t: Throwable) {
                // 词库缺失/损坏时保持空引擎，键盘仍然可以输入英文、数字与符号
            }
            mainHandler.post {
                reloadUserData()
                onDone()
            }
        }
        thread.name = "ime-dict-loader"
        thread.priority = Thread.MIN_PRIORITY // 低优先级，避免抢 UI 线程
        thread.start()
    }

    /** 每次用户词库变化后刷新内存中的用户词条/短语索引 */
    fun reloadUserData() {
        userWords.clear()
        val words = UserDictStore.words(context)
        for (item in words) {
            val pinyin = normalizePinyin(item.pinyin)
            if (pinyin.isNotEmpty() && item.word.isNotEmpty()) {
                // 用户词条频率给到极高，保证排在系统词库前面
                userWords.add(Entry(item.word, pinyin, 1000000))
            }
        }
        phraseCodes.clear()
        val phrases = UserDictStore.phrases(context)
        for (item in phrases) {
            val code = item.code.lowercase().trim()
            if (code.isNotEmpty() && item.text.isNotEmpty()) {
                phraseCodes.getOrPut(code) { ArrayList(2) }.add(item.text)
            }
        }
    }

    /**
     * 查询候选词。
     * @param input 用户输入的拼音串（小写字母）
     * @param limit 最多返回多少条候选
     */
    fun query(input: String, limit: Int): Result {
        if (!loaded || input.isEmpty() || limit <= 0) return Result(ArrayList(0), 0)
        val key = input.lowercase()
        for (c in key) {
            if (c < 'a' || c > 'z') return Result(ArrayList(0), 0)
        }
        // 先按整串匹配
        var list = collect(key, limit)
        if (list.isNotEmpty()) return Result(list, key.length)
        // 整串无结果时，逐字削减尾部，实现「长串先出前面的词」
        var len = key.length - 1
        while (len >= 1) {
            list = collect(key.substring(0, len), limit)
            if (list.isNotEmpty()) return Result(list, len)
            len--
        }
        return Result(ArrayList(0), 0)
    }

    /** 上屏一个词以后，取本地联想词 */
    fun assocFor(word: String): ArrayList<String> {
        val list = assoc[word]
        return if (list == null) ArrayList(0) else ArrayList(list)
    }

    // ==================================================================
    // 内部：加载
    // ==================================================================
    @Synchronized
    private fun loadInternal() {
        if (loaded) return
        loadPinyinFile()
        loadAssocFile()
        for (list in byPinyin.values) sortEntries(list)
        for (list in byInitials.values) sortEntries(list)
        sortedKeys.addAll(byPinyin.keys)
        sortedKeys.sort()
        loaded = true // 所有索引构建完成后才对查询可见
    }

    private fun loadPinyinFile() {
        val stream = context.assets.open("dict/pinyin.txt")
        val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
        try {
            var line = reader.readLine()
            while (line != null) {
                val text = line.trim()
                if (text.isNotEmpty() && text[0] != '#') {
                    parsePinyinLine(text)
                }
                line = reader.readLine()
            }
        } finally {
            try {
                reader.close()
            } catch (ignored: Exception) {
                // 关闭失败无需处理
            }
        }
    }

    private fun parsePinyinLine(text: String) {
        val parts = text.split(whitespace)
        if (parts.size < 2) return
        val pinyin = normalizePinyin(parts[0])
        if (pinyin.isEmpty() || pinyin.length > 30) return

        val freq = if (parts.size >= 3) parts[2].toIntOrNull() else null
        if (freq != null) {
            // 格式二：拼音 词语 频率
            addEntry(pinyin, parts[1], freq)
        } else {
            // 格式一：拼音 汉字串，按位置递减频率
            val chars = parts[1]
            var index = 0
            var rank = 0
            while (index < chars.length && rank < 30) {
                addEntry(pinyin, chars[index].toString(), 9000 - rank * 65)
                index++
                rank++
            }
        }
    }

    private fun loadAssocFile() {
        val stream = context.assets.open("dict/assoc.txt")
        val reader = BufferedReader(InputStreamReader(stream, "UTF-8"))
        try {
            var line = reader.readLine()
            while (line != null) {
                val text = line.trim()
                if (text.isNotEmpty() && text[0] != '#') {
                    val parts = text.split(whitespace)
                    if (parts.size >= 2) {
                        val key = parts[0]
                        val list = assoc.getOrPut(key) { ArrayList(6) }
                        var i = 1
                        while (i < parts.size && list.size < MAX_ASSOC) {
                            val word = parts[i]
                            if (word.isNotEmpty() && !list.contains(word)) list.add(word)
                            i++
                        }
                    }
                }
                line = reader.readLine()
            }
        } finally {
            try {
                reader.close()
            } catch (ignored: Exception) {
                // 关闭失败无需处理
            }
        }
    }

    private fun addEntry(pinyin: String, word: String, freq: Int) {
        if (word.isEmpty()) return
        val bucket = byPinyin.getOrPut(pinyin) { ArrayList(4) }
        if (bucket.size >= MAX_PER_KEY) return
        bucket.add(Entry(word, pinyin, freq))

        // 只给多音节词建简拼索引，避免单字简拼制造噪音
        val initials = PinyinSyllables.initialsOf(pinyin)
        if (initials.length >= 2 && initials != pinyin) {
            val initialsBucket = byInitials.getOrPut(initials) { ArrayList(4) }
            if (initialsBucket.size < MAX_PER_KEY) {
                initialsBucket.add(Entry(word, pinyin, freq))
            }
        }
    }

    private fun sortEntries(list: ArrayList<Entry>) {
        list.sortWith(Comparator { a, b ->
            if (a.freq != b.freq) b.freq - a.freq else a.word.length - b.word.length
        })
    }

    // ==================================================================
    // 内部：查询
    // ==================================================================
    private fun collect(key: String, limit: Int): ArrayList<String> {
        val out = ArrayList<String>(limit)
        val seen = HashSet<String>(limit * 2)

        // 1. 快捷短语（编码精确匹配，优先级最高）
        val phrases = phraseCodes[key]
        if (phrases != null) {
            for (p in phrases) {
                if (out.size >= limit) return out
                if (seen.add(p)) out.add(p)
            }
        }

        // 2. 用户自定义词条（拼音精确或前缀匹配）
        for (entry in userWords) {
            if (entry.pinyin == key || entry.pinyin.startsWith(key)) {
                if (out.size >= limit) return out
                if (seen.add(entry.word)) out.add(entry.word)
            }
        }

        // 3. 系统词库精确匹配（全拼）
        val exact = byPinyin[key]
        if (exact != null) {
            for (entry in exact) {
                if (out.size >= limit) return out
                if (seen.add(entry.word)) out.add(entry.word)
            }
        }

        // 4. 简拼匹配（2~6 个字母，避免过短的简拼产生大量噪音）
        if (key.length >= 2 && key.length <= 6) {
            val initials = byInitials[key]
            if (initials != null) {
                for (entry in initials) {
                    if (out.size >= limit) return out
                    if (seen.add(entry.word)) out.add(entry.word)
                }
            }
        }

        // 5. 前缀匹配（输入 nih -> 你好）
        val candidates = prefixEntries(key)
        for (entry in candidates) {
            if (out.size >= limit) return out
            if (seen.add(entry.word)) out.add(entry.word)
        }
        return out
    }

    /** 前缀二分查找：在已排序的拼音 Key 中找出所有以 prefix 开头的词条 */
    private fun prefixEntries(prefix: String): ArrayList<Entry> {
        val result = ArrayList<Entry>(64)
        var low = 0
        var high = sortedKeys.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sortedKeys[mid] < prefix) low = mid + 1 else high = mid
        }
        var index = low
        while (index < sortedKeys.size && result.size < MAX_PREFIX_ENTRIES) {
            val key = sortedKeys[index]
            if (!key.startsWith(prefix)) break
            val list = byPinyin[key]
            if (list != null) result.addAll(list)
            index++
        }
        // 前缀结果按频率排序，最多返回 40 条给上层做去重
        result.sortWith(Comparator { a, b ->
            if (a.freq != b.freq) b.freq - a.freq else a.pinyin.length - b.pinyin.length
        })
        if (result.size > 40) {
            val trimmed = ArrayList<Entry>(40)
            for (i in 0 until 40) trimmed.add(result[i])
            return trimmed
        }
        return result
    }

    private fun normalizePinyin(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value.lowercase()) {
            if (c in 'a'..'z') sb.append(c)
        }
        return sb.toString()
    }

    companion object {
        /** 单个拼音下最多保留的词条数，控制内存 */
        private const val MAX_PER_KEY = 48
        /** 前缀扫描时最多收集的词条数 */
        private const val MAX_PREFIX_ENTRIES = 160
        /** 单个词最多联想数量 */
        private const val MAX_ASSOC = 8
    }
}
