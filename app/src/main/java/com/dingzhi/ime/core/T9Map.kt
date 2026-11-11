package com.dingzhi.ime.core

/**
 * 九键（T9）数字映射。
 *
 * 用途：把连续拼音转换成手机九键上的数字序列，
 * 例如 nihao -> 64426，zhang -> 94264。
 *
 * 这个对象不依赖任何 Android API，可以直接用纯 JVM 单元测试覆盖。
 */
object T9Map {

    /** 字母 -> 数字，a~z 之外返回 0 */
    fun digitOf(c: Char): Char = when (c) {
        in 'a'..'c' -> '2'
        in 'd'..'f' -> '3'
        in 'g'..'i' -> '4'
        in 'j'..'l' -> '5'
        in 'm'..'o' -> '6'
        in 'p'..'s' -> '7'
        in 't'..'v' -> '8'
        in 'w'..'z' -> '9'
        else -> '0'
    }

    /** 拼音串 -> 数字串，忽略非 a~z 字符 */
    fun toDigits(pinyin: String): String {
        if (pinyin.isEmpty()) return ""
        val sb = StringBuilder(pinyin.length)
        for (c in pinyin.lowercase()) {
            if (c in 'a'..'z') sb.append(digitOf(c))
        }
        return sb.toString()
    }
}
