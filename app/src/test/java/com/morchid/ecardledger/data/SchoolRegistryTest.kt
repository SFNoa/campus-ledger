package com.morchid.ecardledger.data

import com.morchid.ecardledger.data.school.LoginKind
import com.morchid.ecardledger.data.school.SchoolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 院校注册表。
 *
 * 重点不是"有几所学校"，而是**每所学校的登录方式和可用状态必须诚实** ——
 * 一个点了没反应的选项，比列表里少一所学校糟糕得多。
 */
class SchoolRegistryTest {

    @Test
    fun `中南大学是实测可用的账号密码登录`() {
        val csu = SchoolRegistry.byId("csu")
        assertNotNull(csu)
        assertEquals(LoginKind.CAS_FORM, csu!!.loginKind)
        // 实测通过的学校，接口地址必须齐全（缺一个就同步不了）
        assertTrue(csu.ecardServiceUrl.isNotBlank())
        assertTrue(csu.cardUrl.isNotBlank())
        assertTrue(csu.turnoverUrl.isNotBlank())
    }

    @Test
    fun `默认学校是实测通过的中南大学`() {
        assertEquals("csu", SchoolRegistry.default.id)
        assertEquals(LoginKind.CAS_FORM, SchoolRegistry.default.loginKind)
    }

    @Test
    fun `列表里的学校不能重名或重 id`() {
        val ids = SchoolRegistry.all.map { it.id }
        assertEquals("id 不能重复：$ids", ids.size, ids.toSet().size)
        val names = SchoolRegistry.all.map { it.displayName }
        assertEquals("名字不能重复：$names", names.size, names.toSet().size)
    }

    /**
     * 故意钉住这条：列表里**只放实测能用的学校**。
     * 华南师范大学曾作为"暂不支持"的占位条目存在过，后来按要求删掉了 ——
     * 想加新学校，先把 [SchoolRegistry.all] 里这一条断言改掉，等于强制你想清楚"这所真能用吗"。
     */
    @Test
    fun `列表里只放实测可用的学校`() {
        assertEquals(1, SchoolRegistry.all.size)
        assertTrue(
            "列表里出现了没实测过的学校：" + SchoolRegistry.all.map { it.displayName },
            SchoolRegistry.all.all { it.loginKind == LoginKind.CAS_FORM && it.turnoverUrl.isNotBlank() },
        )
    }

    @Test
    fun `byId 对未知 id 和 null 都返回 null，不抛异常`() {
        assertEquals(null, SchoolRegistry.byId("不存在的学校"))
        assertEquals(null, SchoolRegistry.byId(null))
        assertEquals(null, SchoolRegistry.byId("scnu"))
    }
}