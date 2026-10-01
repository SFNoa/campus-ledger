package com.morchid.ecardledger.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议文本的渲染预处理。
 *
 * 这是**面向用户的法律文本**，渲染错了比不渲染更糟（比如吃掉"禁止"两个字）。
 * 所以钉住：标题分级、表格压平、行内标记清理、空行保留。
 */
class LegalDocsTest {

    @Test
    fun `标题按级别拆开`() {
        val lines = formatLegalMarkdown("# 一级\n## 二级\n### 三级")
        assertEquals(1, lines[0].level)
        assertEquals("一级", lines[0].text)
        assertEquals(2, lines[1].level)
        assertEquals(3, lines[2].level)
    }

    @Test
    fun `表格压成一行，分隔行丢掉`() {
        val md = "| 权限 | 用途 |\n|---|---|\n| 通知 | 记账 |"
        val lines = formatLegalMarkdown(md).filter { it.text.isNotBlank() }
        assertEquals("权限 · 用途", lines[0].text)
        assertEquals("通知 · 记账", lines[1].text)
        assertEquals("分隔行必须丢掉，否则会渲染出一堆横线", 2, lines.size)
    }

    @Test
    fun `行内标记被清掉，但文字一个不少`() {
        val lines = formatLegalMarkdown("**禁止商业用途**，详见 `LICENSE`")
        assertEquals("禁止商业用途，详见 LICENSE", lines[0].text)
        assertTrue("不能把内容吃掉", lines[0].text.contains("禁止"))
    }

    @Test
    fun `空行保留，用来分段`() {
        val lines = formatLegalMarkdown("第一段\n\n第二段")
        assertEquals(3, lines.size)
        assertEquals("", lines[1].text)
    }

    @Test
    fun `关键承诺不会被渲染吃掉`() {
        val md = "# 隐私政策\n\n一句话：**这个 App 没有服务器，你的数据只在这台手机上。**\n\n| 权限 | 用途 |\n|---|---|\n| 通知使用权 | 本地解析，不外传 |"
        val text = formatLegalMarkdown(md).joinToString("\n") { it.text }
        assertTrue(text.contains("没有服务器"))
        assertTrue(text.contains("不外传"))
        assertTrue("标题层级要保留", formatLegalMarkdown(md).any { it.level == 1 })
    }
}