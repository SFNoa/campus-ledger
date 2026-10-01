package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 针对 CAS 登录页表单解析的回归测试。
 *
 * 用真实的登录页（tools 里保存的 cas-login.html，35KB）做夹具。
 * 这里的重点是钉死那个出过致命 bug 的行为：
 * 登录页有 4 个表单共用同一个 name，必须解析 id=pwdFromId 这个账号密码表单，
 * 否则会拿到 FIDO 表单的 cllt=fidoLogin，导致每次登录都被当成 FIDO 登录并返回 401。
 */
class CasLoginFormTest {

    private val html: String by lazy {
        javaClass.classLoader!!.getResourceAsStream("cas-login.html")!!
            .readBytes().toString(Charsets.UTF_8)
    }

    @Test
    fun `能解析出 pwdFromId 账号密码表单`() {
        val form = EcardApi.parseForm(html, "pwdFromId")
        assertNotNull("必须能找到 id=pwdFromId 的表单", form)
    }

    @Test
    fun `关键隐藏字段取值正确`() {
        val form = EcardApi.parseForm(html, "pwdFromId")!!
        val byName = form.fields.filter { it.name.isNotEmpty() }.associateBy { it.name }

        assertEquals("cllt 必须是 userNameLogin（不是 fidoLogin）", "userNameLogin", byName["cllt"]?.value)
        assertEquals("generalLogin", byName["dllt"]?.value)
        assertEquals("submit", byName["_eventId"]?.value)
        assertEquals("e1s1", byName["execution"]?.value)
        assertEquals("/authserver/login", form.action)
    }

    @Test
    fun `密码盐存在且为 16 字节`() {
        val form = EcardApi.parseForm(html, "pwdFromId")!!
        val salt = form.fields.firstOrNull { it.id == "pwdEncryptSalt" }?.value?.trim()
        assertNotNull("必须找到 pwdEncryptSalt", salt)
        assertEquals("盐必须是 16 字节，AES-128 的 key 长度", 16, salt!!.length)
    }

    @Test
    fun `可见密码框与隐藏密文框都在，且隐藏框没有 name 以外的干扰`() {
        val form = EcardApi.parseForm(html, "pwdFromId")!!
        val visible = form.fields.firstOrNull { it.id == "password" }
        val hidden = form.fields.firstOrNull { it.id == "saltPassword" }
        assertNotNull(visible)
        assertNotNull(hidden)
        assertEquals("passwordText", visible!!.name)
        assertEquals("password", hidden!!.name)
        assertEquals("hidden", hidden.type)
    }

    @Test
    fun `文档里第一个 cllt 确实是 fidoLogin——这就是当年的坑`() {
        val firstCllt = Regex(
            "name=[\"']?cllt[\"']?[^>]*value=[\"']([^\"']*)[\"']",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
        assertEquals(
            "如果这个断言失败，说明页面结构变了，需要重新确认取值方式",
            "fidoLogin",
            firstCllt,
        )
    }

    @Test
    fun `other表单也能解析出来，确认多表单共存`() {
        assertNotNull(EcardApi.parseForm(html, "phoneFromId"))
        assertNotNull(EcardApi.parseForm(html, "qrLoginForm"))
        assertEquals(
            "dynamicLogin",
            EcardApi.parseForm(html, "phoneFromId")!!
                .fields.first { it.name == "cllt" }.value,
        )
    }

    @Test
    fun `找不到表单时返回 null 而不是抛异常`() {
        assertEquals(null, EcardApi.parseForm(html, "notExistingFormId"))
    }

    @Test
    fun `商户名会去掉 -持卡人消费 后缀`() {
        assertEquals(
            "南校区学生宿舍内xzx洗浴",
            EcardApi.cleanMerchant("南校区学生宿舍内xzx洗浴-持卡人消费", "持卡人消费"),
        )
        assertEquals(
            "微信支付转账",
            EcardApi.cleanMerchant("微信支付转账", "微信支付转账"),
        )
        assertEquals(
            "校园卡消费",
            EcardApi.cleanMerchant("", ""),
        )
        // 实测：有一批记录的 resume 恰好就是「持卡人消费」，不能当成商户名
        assertEquals(
            "校园卡消费",
            EcardApi.cleanMerchant("持卡人消费", "持卡人消费"),
        )
        assertEquals(
            "微信支付转账",
            EcardApi.cleanMerchant("持卡人消费", "微信支付转账"),
        )
    }

    @Test
    fun `自动分类能认出食堂和洗浴`() {
        assertEquals(
            // 商户名里写了「食堂」，所以细分到二级（一级仍是餐饮）
            "餐饮/食堂",
            AutoCategory.guess("南校区2食堂2楼白案组", "消费", "持卡人消费", "3200010"),
        )
        assertEquals(
            "洗浴",
            AutoCategory.guess("南校区学生宿舍内xzx洗浴", "消费", "持卡人消费", "1000085"),
        )
        // 商户名看不出细分时，只靠账号规则 → 停在一级
        assertEquals(
            "餐饮",
            AutoCategory.guess("持卡人消费", "消费", "持卡人消费", "3200010"),
        )
        assertEquals(
            "充值",
            AutoCategory.guess("微信支付转账", "充值", "微信支付转账", "0"),
        )
        assertTrue(AutoCategory.DEFAULT_CATEGORIES.contains("其他"))
    }
}
