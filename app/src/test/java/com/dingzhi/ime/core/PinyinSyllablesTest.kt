package com.dingzhi.ime.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PinyinSyllables 纯 JVM 单元测试（不依赖 Android）。
 * 覆盖音节判定与简拼切分，防止词库索引在重构后悄悄退化。
 */
class PinyinSyllablesTest {

    @Test
    fun syllableTableRecognizesCommonSyllables() {
        assertTrue(PinyinSyllables.isSyllable("ni"))
        assertTrue(PinyinSyllables.isSyllable("hao"))
        assertTrue(PinyinSyllables.isSyllable("zhang"))
        assertTrue(PinyinSyllables.isSyllable("chuang"))
        assertFalse(PinyinSyllables.isSyllable("zh"))
        assertFalse(PinyinSyllables.isSyllable(""))
    }

    @Test
    fun initialsOfSplitsByLongestSyllable() {
        assertEquals("nh", PinyinSyllables.initialsOf("nihao"))
        assertEquals("zs", PinyinSyllables.initialsOf("zhangsan"))
        // xian 是一个完整音节，应得到单字母简拼 x，而不是 xa / xi
        assertEquals("x", PinyinSyllables.initialsOf("xian"))
        assertEquals("", PinyinSyllables.initialsOf(""))
    }
}
