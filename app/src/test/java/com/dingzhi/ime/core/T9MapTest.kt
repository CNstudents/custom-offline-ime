package com.dingzhi.ime.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T9Map 纯 JVM 单元测试：验证九键数字映射与边界输入。
 */
class T9MapTest {

    @Test
    fun mapsPinyinToDigits() {
        assertEquals("64426", T9Map.toDigits("nihao"))
        assertEquals("94264", T9Map.toDigits("zhang"))
        assertEquals("666", T9Map.toDigits("mom"))
    }

    @Test
    fun ignoresNonLettersAndHandlesCase() {
        assertEquals("64426", T9Map.toDigits("Ni-Hao"))
        assertEquals("", T9Map.toDigits(""))
        assertEquals("", T9Map.toDigits("，。"))
    }
}
