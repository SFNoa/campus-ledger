package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「内部转账」配对的测试。
 *
 * 用的是**真实数据形状**：中南大学校园卡的充值记录实测长这样 ——
 *   payName = "微信支付转账"、payIcon = "weixin"、turnoverType = "充值"、typeId = "2"（收入）
 * 所以「微信支出 ⇄ 校园卡充值」这一对是可以确认的。
 */
class TransferMatcherTest {

    private val campus = AccountIds.campus("csu")
    private val wechat = AccountIds.WECHAT
    private val alipay = AccountIds.ALIPAY

    /** 基准时间，避免依赖真实时钟 */
    private val t0 = 1_790_000_000_000L

    private fun entry(
        orderId: String,
        accountId: String,
        amountCents: Long,
        isIncome: Boolean,
        epochMillis: Long = t0,
        payName: String = "",
        merchant: String = "",
        kind: String = "",
        isTransfer: Boolean = false,
        transferGroupId: String? = null,
        rawText: String = "",
        source: EntrySource = EntrySource.SYNC,
    ) = LedgerEntry(
        orderId = orderId,
        timeText = "2026-09-28 12:00:00",
        epochMillis = epochMillis,
        amountCents = amountCents,
        isIncome = isIncome,
        kind = kind,
        merchant = merchant,
        payName = payName,
        balanceCents = 0,
        toAccount = "",
        category = "",
        note = "",
        manual = false,
        accountId = accountId,
        source = source,
        isTransfer = isTransfer,
        transferGroupId = transferGroupId,
        rawText = rawText,
    )

    /** 校园卡的一条「微信支付转账」充值（实测形状） */
    private fun campusRecharge(
        orderId: String = "campus-recharge-1",
        amountCents: Long = 20_000,
        epochMillis: Long = t0,
    ) = entry(
        orderId = orderId,
        accountId = campus,
        amountCents = amountCents,
        isIncome = true,
        epochMillis = epochMillis,
        payName = "微信支付转账",
        merchant = "微信支付转账",
        kind = "充值",
    )

    /** 微信侧对应的那笔支出（通知监听记下来的） */
    private fun wechatPayment(
        orderId: String = "wechat-pay-1",
        amountCents: Long = 20_000,
        epochMillis: Long = t0,
        merchant: String = "校园卡充值",
    ) = entry(
        orderId = orderId,
        accountId = wechat,
        amountCents = amountCents,
        isIncome = false,
        epochMillis = epochMillis,
        payName = merchant,
        merchant = merchant,
        source = EntrySource.NOTIFICATION,
    )

    // ------------------------------------------------------------ 正例

    @Test
    fun `微信支出与校园卡充值能配成内部转账`() {
        val recharge = campusRecharge()
        val payment = wechatPayment()
        assertTrue(TransferMatcher.isPair(recharge, payment))
        assertEquals(payment.orderId, TransferMatcher.findCounterpart(recharge, listOf(payment))?.orderId)
    }

    @Test
    fun `支付宝充值也能识别（校园卡文案里含支付宝）`() {
        val recharge = entry(
            orderId = "campus-alipay",
            accountId = campus,
            amountCents = 10_000,
            isIncome = true,
            payName = "支付宝转账",
            merchant = "支付宝转账",
            kind = "充值",
        )
        val payment = entry(
            orderId = "alipay-pay-1",
            accountId = alipay,
            amountCents = 10_000,
            isIncome = false,
            merchant = "校园卡充值",
        )
        assertTrue(TransferMatcher.isPair(recharge, payment))
    }

    @Test
    fun `多候选取时间最接近的那笔`() {
        val recharge = campusRecharge(epochMillis = t0)
        val far = wechatPayment(orderId = "far", epochMillis = t0 + 30 * 60_000L)
        val near = wechatPayment(orderId = "near", epochMillis = t0 + 60_000L)

        assertEquals(
            "应当选时间上最接近的",
            "near",
            TransferMatcher.findCounterpart(recharge, listOf(far, near))?.orderId,
        )
    }

    @Test
    fun `两张校园卡之间也能按金额加时间配对（判断不出渠道时放行）`() {
        val a = entry("card-a", AccountIds.campus("csu"), 5_000, isIncome = false)
        val b = entry("card-b", AccountIds.campus("other"), 5_000, isIncome = true)
        assertTrue(TransferMatcher.isPair(a, b))
    }

    // ------------------------------------------------------------ 反例

    @Test
    fun `金额不同不配对（必须是同一笔钱）`() {
        assertFalse(TransferMatcher.isPair(campusRecharge(), wechatPayment(amountCents = 19_900)))
    }

    @Test
    fun `时间超出窗口不配对`() {
        val recharge = campusRecharge(epochMillis = t0)
        val late = wechatPayment(epochMillis = t0 + 3 * 60 * 60_000L) // 3 小时
        assertFalse(TransferMatcher.isPair(recharge, late))
        assertNull(TransferMatcher.findCounterpart(recharge, listOf(late)))
    }

    @Test
    fun `窗口边界刚好等于阈值时算配对`() {
        val recharge = campusRecharge(epochMillis = t0)
        val edge = wechatPayment(epochMillis = t0 + TransferMatcher.DEFAULT_WINDOW_MILLIS)
        assertTrue(TransferMatcher.isPair(recharge, edge))
    }

    @Test
    fun `方向相同不配对（两笔都是收入）`() {
        // 校园卡充值（收入）+ 微信收款（收入）：渠道对得上、金额相同、时间一致、账户不同，
        // 唯一不符合的就是「方向必须相反」—— 必须被方向规则挡掉，否则会把两笔收入凑成转账
        val recharge = campusRecharge()
        val wechatIncome = entry(
            orderId = "wechat-in",
            accountId = wechat,
            amountCents = 20_000,
            isIncome = true,
            merchant = "收款",
        )
        assertFalse(TransferMatcher.isPair(recharge, wechatIncome))
    }

    @Test
    fun `同一个账户内的两笔不配对`() {
        val a = entry("a", campus, 20_000, isIncome = true)
        val b = entry("b", campus, 20_000, isIncome = false)
        assertFalse(TransferMatcher.isPair(a, b))
    }

    @Test
    fun `已经配过对的记录不再参与配对`() {
        val recharge = campusRecharge()
        val already = wechatPayment().copy(isTransfer = true, transferGroupId = "g1")
        assertFalse(TransferMatcher.isPair(recharge, already))

        val grouped = wechatPayment().copy(transferGroupId = "g1")
        assertFalse(TransferMatcher.isPair(recharge, grouped))
    }

    @Test
    fun `渠道对不上不配对（校园卡说是微信，对手方却是支付宝）`() {
        val recharge = campusRecharge() // payName 含「微信」
        val alipayPayment = entry("alipay-1", alipay, 20_000, isIncome = false, merchant = "校园卡充值")
        assertFalse(TransferMatcher.isPair(recharge, alipayPayment))
    }

    @Test
    fun `微信与支付宝之间不配对（渠道不同）`() {
        val a = entry("w", wechat, 5_000, isIncome = false)
        val b = entry("a", alipay, 5_000, isIncome = true)
        assertFalse(TransferMatcher.isPair(a, b))
    }

    @Test
    fun `金额为 0 或负数不配对`() {
        val recharge = campusRecharge(amountCents = 0)
        val payment = wechatPayment(amountCents = 0)
        assertFalse(TransferMatcher.isPair(recharge, payment))
    }

    @Test
    fun `自己与自己不配对`() {
        val recharge = campusRecharge()
        assertFalse(TransferMatcher.isPair(recharge, recharge))
    }

    @Test
    fun `空候选返回 null`() {
        assertNull(TransferMatcher.findCounterpart(campusRecharge(), emptyList()))
    }

    // ------------------------------------------------------------ 统计口径

    @Test
    fun `转账不计入收支统计，普通记录计入`() {
        val transfer = campusRecharge().copy(isTransfer = true, transferGroupId = "g1")
        assertEquals("转账对统计的贡献必须是 0", 0L, transfer.statCents)
        assertEquals("但仍要影响账户余额", 20_000L, transfer.signedCents)

        val normal = entry("n", campus, 1_350, isIncome = false)
        assertEquals(-1_350L, normal.statCents)
    }

    @Test
    fun `配对之后同账户余额不受影响，但总收支不再被虚增`() {
        val recharge = campusRecharge(amountCents = 20_000)
        val payment = wechatPayment(amountCents = 20_000)

        // 配对前：校园卡 +200 元、微信 -200 元，收支统计里两项都被算进去
        assertEquals(20_000L, recharge.statCents)
        assertEquals(-20_000L, payment.statCents)

        // 配对后：两项对统计的贡献都归零，只有账户余额变了
        val pairedRecharge = recharge.copy(isTransfer = true, transferGroupId = "g")
        val pairedPayment = payment.copy(isTransfer = true, transferGroupId = "g")
        assertEquals(0L, pairedRecharge.statCents + pairedPayment.statCents)
    }
}
