package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动分类。
 *
 * 真机反馈驱动出来的两条：**「商户」这种泛称归购物**（再细分衣物/日用品…）、
 * **「广铁/中铁」归交通**。分类错不会报错，只会让人看着别扭，所以用测试钉住。
 */
class AutoCategoryTest {

    private fun guess(merchant: String, kind: String = "", payName: String = "") =
        AutoCategory.guess(merchant, kind, payName, "")

    @Test
    fun `含商户字样的归到购物`() {
        assertEquals("购物", guess("商户消费"))
        assertEquals("购物", guess("某某商户"))
        assertEquals("购物/日用品", guess("超市"))
        assertEquals("购物/日用品", guess("便利店"))
    }

    @Test
    fun `购物会按商品名细分`() {
        assertEquals("购物/衣物", guess("优衣库"))
        assertEquals("购物/衣物", guess("服饰旗舰店"))
        assertEquals("购物/数码", guess("京东数码"))
        assertEquals("购物/美妆", guess("护肤专卖店"))
    }

    @Test
    fun `铁路相关的归到交通的火车`() {
        assertEquals("交通/火车", guess("广铁集团"))
        assertEquals("交通/火车", guess("中铁网络"))
        assertEquals("交通/火车", guess("12306"))
        assertEquals("交通/火车", guess("高铁票"))
    }

    @Test
    fun `其他交通也能细分`() {
        assertEquals("交通/公交地铁", guess("长沙地铁"))
        assertEquals("交通/公交地铁", guess("校车"))
        assertEquals("交通/打车", guess("滴滴出行"))
        assertEquals("交通/单车", guess("哈啰出行"))
    }

    @Test
    fun `食堂还是餐饮`() {
        assertEquals("餐饮/食堂", guess("南校区2食堂"))
        assertEquals("餐饮/食堂", guess("白案组"))
        assertTrue(guess("奶茶店").startsWith("餐饮"))
    }

    @Test
    fun `充值的判定优先于其他规则`() {
        // 「微信支付转账」里含"转账"，但它本质是充值，不能被后面的规则抢走
        assertEquals("充值", guess("微信支付转账", payName = "零钱"))
        assertEquals("充值", AutoCategory.guess("某商户", "充值", "", ""))
    }

    @Test
    fun `认不出来就是其他`() {
        assertEquals("其他", guess("某笔说不清的支出"))
    }

    @Test
    fun `一级二级的拆解与默认列表`() {
        assertEquals("购物", AutoCategory.topLevel("购物/衣物"))
        assertEquals("衣物", AutoCategory.subLevel("购物/衣物"))
        assertEquals("餐饮", AutoCategory.topLevel("餐饮"))
        assertEquals("", AutoCategory.subLevel("餐饮"))
        assertEquals(listOf("衣物", "日用品", "数码", "美妆", "其他购物"), AutoCategory.subsOf("购物"))
        assertTrue(AutoCategory.DEFAULT_CATEGORIES.contains("购物"))
    }
}
