package com.morchid.ecardledger.notification

import com.morchid.ecardledger.data.AccountType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知解析测试。
 *
 * 这里的用例分三类：
 *  1. **必须识别**的真支付通知（微信支付/支付宝的付款、收款、退款）
 *  2. **必须忽略**的噪音 —— 这一组是**真机反馈打回来补的**：
 *     用户发现「来自微信的任何消息都会被记录」，根因有两个（见下面各用例的注释）
 *  3. 微信**红包/转账通知不带金额**这一客观限制的处理方式
 *
 * ⚠️ 文案样本是按常见格式构造的，不是逐条从真机抓的；规则表可以随时加。
 */
class PaymentNotificationParserTest {

    private val wechat = "com.tencent.mm"
    private val alipay = "com.eg.android.AlipayGphone"

    /** 聊天场景里的标题通常是联系人/群名 */
    private val friend = "张三"
    private val group = "宿舍群(6)"

    private fun parse(pkg: String, title: String?, text: String?) =
        PaymentNotificationParser.parse(pkg, title, text)

    /** 只关心「解析成功」的场景用这个取结果 */
    private fun parsed(pkg: String, title: String?, text: String?) =
        (parse(pkg, title, text) as? NotificationParse.Parsed)?.payment

    // ------------------------------------------------------------ 来源

    @Test
    fun `只认微信与支付宝两个来源`() {
        assertEquals(AccountType.WECHAT, PaymentNotificationParser.channelOf(wechat))
        assertEquals(AccountType.ALIPAY, PaymentNotificationParser.channelOf(alipay))
        assertNull(PaymentNotificationParser.channelOf("com.other.app"))
        assertNull(PaymentNotificationParser.channelOf(null))
    }

    @Test
    fun `不认识的应用一律忽略`() {
        assertEquals(NotificationParse.Ignore, parse("com.other.app", "某某支付", "已支付¥10.00"))
    }

    // ------------------------------------------------------------ 噪音：必须忽略

    @Test
    fun `普通聊天消息一律忽略`() {
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "在吗"))
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "文件收到了吗"))
        assertEquals(NotificationParse.Ignore, parse(wechat, group, "晚上一起吃饭吗"))
    }

    @Test
    fun `聊天里提到金额也绝不能记账`() {
        // 这是真机反馈的核心问题：老版本只要文本里有「N 元」就记一笔支出
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "我转你 50 元"))
        assertEquals(NotificationParse.Ignore, parse(wechat, group, "今晚吃饭 AA，每人 88 元"))
        assertEquals(NotificationParse.Ignore, parse(wechat, group, "谁借我 100 元"))
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "这个手机 5000 元，太贵了"))
        // 带 ¥ 也一样：没有交易信号就不算账
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "记得给我 ¥50"))
    }

    @Test
    fun `聊天里出现交易类字眼但没有金额写法时也忽略`() {
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "你转账给我了吗"))
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "付款了没"))
    }

    @Test
    fun `聊天里同时出现交易字眼和元金额时仍然忽略`() {
        // 「转账 100 元」是聊天常见说法，不应该被当成支付
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "转账 100 元给你了"))
    }

    @Test
    fun `标题是昵称、正文也没有平台措辞时一律忽略——不靠正文里的金额猜`() {
        // 发送方优先的硬门槛：过不了这一关就不看金额了
        assertEquals(NotificationParse.Ignore, parse(wechat, null, "转账 ¥88.00"))
        assertEquals(NotificationParse.Ignore, parse(wechat, friend, "收款 ¥88.00"))
        assertEquals(NotificationParse.Ignore, parse(wechat, group, "到账 50 元"))
    }

    @Test
    fun `忽略非交易类通知`() {
        assertEquals(NotificationParse.Ignore, parse(wechat, "微信", "你的账户余额提醒"))
        assertEquals(NotificationParse.Ignore, parse(alipay, "支付宝", "登录提醒：你的账号在新设备登录"))
        assertEquals(NotificationParse.Ignore, parse(alipay, "支付宝", "验证码 123456，请勿告诉他人"))
        assertEquals(NotificationParse.Ignore, parse(wechat, "微信", "双十一优惠：满300减50"))
    }

    @Test
    fun `标题与内容都为空时忽略`() {
        assertEquals(NotificationParse.Ignore, parse(wechat, null, null))
        assertEquals(NotificationParse.Ignore, parse(wechat, "", ""))
    }

    // ------------------------------------------------------------ 真支付通知

    @Test
    fun `微信付款被识别为支出，并抽出金额与商户`() {
        val payment = parsed(wechat, "微信支付", "微信支付凭证：已支付¥25.50，向「南校区2食堂」付款")
        assertEquals(AccountType.WECHAT, payment!!.accountType)
        assertEquals("25.50 元应换算成 2550 分", 2550L, payment.amountCents)
        assertFalse(payment.isIncome)
        assertEquals("南校区2食堂", payment.merchant)
        assertEquals(ParsedPayment.Confidence.HIGH, payment.confidence)
    }

    @Test
    fun `支付宝消费被识别为支出`() {
        val payment = parsed(alipay, "支付宝", "支付宝支付成功 ￥12.00 在 便利店消费")
        assertEquals(1200L, payment!!.amountCents)
        assertFalse(payment.isIncome)
        assertEquals("便利店", payment.merchant)
    }

    @Test
    fun `收款到账被识别为收入`() {
        val payment = parsed(wechat, "微信支付", "微信支付：收款到账 ¥50.00")
        assertTrue("收款到账应当是收入", payment!!.isIncome)
        assertEquals(5000L, payment.amountCents)
        assertEquals(ParsedPayment.Confidence.HIGH, payment.confidence)
    }

    @Test
    fun `支付宝退款被识别为收入`() {
        val payment = parsed(alipay, "支付宝", "退款成功 ¥19.90 已退回到你的账户")
        assertTrue(payment!!.isIncome)
        assertEquals(1990L, payment.amountCents)
    }

    // ------------------------------------------------------------ 红包 / 转账

    @Test
    fun `红包与转账通知不带金额——记成待确认让用户补，而不是丢掉`() {
        // 真机反馈：「微信的红包、转账并不会有通知金额」。
        // 这是微信本身的行为，App 拿不到数额，所以只能提示用户补。
        val transfer = parse(wechat, "微信", "[转账]待收款")
        assertTrue(transfer is NotificationParse.NeedsReview)
        assertEquals(AccountType.WECHAT, (transfer as NotificationParse.NeedsReview).accountType)
        assertTrue(transfer.reason.contains("手动补"))

        val redPacket = parse(wechat, "微信", "[微信红包]恭喜发财，大吉大利")
        assertTrue(redPacket is NotificationParse.NeedsReview)
        assertTrue((redPacket as NotificationParse.NeedsReview).reason.contains("红包"))
    }

    @Test
    fun `红包封面这类通知不该被当成红包`() {
        assertEquals(
            NotificationParse.Ignore,
            parse(wechat, "微信", "新红包封面已到账，快来看看"),
        )
    }

    // ------------------------------------------------------------ 降级

    @Test
    fun `弱信号加不确定方向时降级为待确认`() {
        // 「转账 ¥88.00」既没说收还是付；标题是可信发送方，所以能解析，但方向不明 → 待确认
        val payment = parsed(wechat, "微信支付", "转账 ¥88.00")
        assertEquals(8800L, payment!!.amountCents)
        assertEquals(ParsedPayment.Confidence.LOW, payment.confidence)
    }

    @Test
    fun `强信号但金额为 0 时交给用户确认，而不是静默丢弃`() {
        assertTrue(parse(wechat, "微信支付", "已支付¥0") is NotificationParse.NeedsReview)
    }

    @Test
    fun `强信号但认不出金额时记成待确认`() {
        val result = parse(wechat, "微信支付", "你有一笔交易，详情请点击查看")
        assertTrue(result is NotificationParse.NeedsReview)
    }

    // ------------------------------------------------------------ 金额换算

    @Test
    fun `强信号的整元金额也能正确换算`() {
        assertEquals(600L, PaymentNotificationParser.extractAmountCents("已支付¥6"))
        assertEquals(10_000L, PaymentNotificationParser.extractAmountCents("支付100元"))
        assertEquals(1250L, PaymentNotificationParser.extractAmountCents("消费12.5元"))
        assertEquals(1L, PaymentNotificationParser.extractAmountCents("¥0.01"))
    }

    @Test
    fun `金额换算不受浮点误差影响`() {
        assertEquals(1234L, PaymentNotificationParser.extractAmountCents("¥12.34"))
        assertEquals(9_999L, PaymentNotificationParser.extractAmountCents("¥99.99"))
    }

    @Test
    fun `不允许弱金额写法时只认带货币符号的`() {
        assertEquals(500L, PaymentNotificationParser.extractAmountCents("¥5.00", allowWeakPatterns = false))
        assertNull(
            "「5 元」在聊天里太常见，弱信号下不能采信",
            PaymentNotificationParser.extractAmountCents("5 元", allowWeakPatterns = false),
        )
    }

    // ------------------------------------------------------------ 信号与方向

    @Test
    fun `发送方白名单判定`() {
        val defaults = PaymentNotificationParser.DEFAULT_SENDERS
        assertTrue(PaymentNotificationParser.isTrustedSender("微信支付", defaults))
        assertTrue("标题里含白名单词即可，允许前后有其它字", PaymentNotificationParser.isTrustedSender("微信支付凭证", defaults))
        assertTrue(PaymentNotificationParser.isTrustedSender("支付宝", defaults))
        assertFalse("联系人昵称不是支付来源", PaymentNotificationParser.isTrustedSender("张三", defaults))
        assertFalse("群名也不是", PaymentNotificationParser.isTrustedSender("宿舍群(6)", defaults))
        assertFalse("没有标题就不可信", PaymentNotificationParser.isTrustedSender(null, defaults))
        assertFalse(PaymentNotificationParser.isTrustedSender("", defaults))
    }

    @Test
    fun `用户可以把没认出来的发送方加进白名单`() {
        // 假设某个微信版本把支付通知的标题写成了「XX收款」
        assertEquals(
            NotificationParse.Ignore,
            parse(wechat, "XX收款", "收款 ¥66.00"),
        )
        // 加进白名单后就能识别了 —— 这正是诊断页那个按钮要做的事
        val custom = PaymentNotificationParser.DEFAULT_SENDERS + "XX收款"
        val payment = (
            PaymentNotificationParser.parse(wechat, "XX收款", "收款 ¥66.00", custom)
                as? NotificationParse.Parsed
            )?.payment
        assertEquals(6600L, payment!!.amountCents)
    }

    @Test
    fun `标题不在白名单但正文有平台措辞时仍然识别——第二道防线`() {
        // 有些版本可能不在标题里写「微信支付」
        val payment = parsed(wechat, "微信", "支付成功 ¥18.00 在 便利店消费")
        assertEquals(1800L, payment!!.amountCents)
        assertFalse(payment.isIncome)
    }

    @Test
    fun `微信退款认得出金额——真机反馈过这条`() {
        // 退款通知常写成「退款到账 19.90元」，标题也不是「微信支付」
        val payment = parsed(wechat, "服务提醒", "退款到账 19.90元")
        assertEquals(1990L, payment!!.amountCents)
        assertTrue("退款是收入", payment.isIncome)
        assertEquals(
            "发送方不可信 → 标成待确认让用户核对一遍",
            ParsedPayment.Confidence.LOW,
            payment.confidence,
        )
    }

    @Test
    fun `支付宝支出认得出金额——真机反馈过这条`() {
        // 正文只有「N 元」、没有 ¥ 符号，标题也不在白名单里
        val payment = parsed(alipay, "服务提醒", "支付成功 12.00元")
        assertEquals(1200L, payment!!.amountCents)
        assertFalse(payment.isIncome)
        assertEquals(ParsedPayment.Confidence.LOW, payment.confidence)
    }

    @Test
    fun `命中平台措辞就允许 N 元写法，代价是可能把聊天误判成待确认`() {
        // 这是为修上面两条而放开的：聊天里同时出现「支付成功」和金额时会被记成待确认。
        // 权衡：宁可多一条待确认（用户能一眼看到、也能补全或删掉），也不要漏掉真实收支。
        val payment = parsed(wechat, friend, "我支付成功了，50 元已经转你了")
        assertEquals(5000L, payment!!.amountCents)
        assertEquals(ParsedPayment.Confidence.LOW, payment.confidence)
    }

    @Test
    fun `可信发送方下允许 N 元的写法`() {
        // 标题是微信支付，金额没带 ¥ 也认
        val payment = parsed(wechat, "微信支付", "已支付 25 元")
        assertEquals(2500L, payment!!.amountCents)
    }

    @Test
    fun `带「已优惠」的支付通知不能被整条丢掉——真机反馈过这条`() {
        // 老规则一见「优惠」就 Ignore，于是这类支付通知完全记不上账
        val payment = parsed(wechat, "微信支付", "已支付 ¥12.00，已优惠 ¥1.00")
        assertEquals(1200L, payment!!.amountCents)
        assertFalse(payment.isIncome)
    }

    @Test
    fun `来源不可信时「优惠」仍然用来挡广告`() {
        assertEquals(
            NotificationParse.Ignore,
            parse(wechat, friend, "这个优惠券给你，满 100 减 20"),
        )
    }

    @Test
    fun `支付宝的登录提醒仍然被忽略——标题可信不代表内容是一笔钱`() {
        assertEquals(
            NotificationParse.Ignore,
            parse(alipay, "支付宝", "登录提醒，你的账号在新设备登录"),
        )
        assertEquals(
            NotificationParse.Ignore,
            parse(wechat, "微信支付", "验证码 123456，请勿告诉他人"),
        )
    }

    @Test
    fun `来源可信时，正文里孤零零一个两位小数也认`() {
        val payment = parsed(wechat, "微信支付", "付款成功 12.00")
        assertEquals(1200L, payment!!.amountCents)
    }

    @Test
    fun `来源不可信时，孤零零的数字不当金额`() {
        // 聊天里说「12.00」不能记账
        assertEquals(
            NotificationParse.Ignore,
            parse(wechat, friend, "12.00"),
        )
    }

    // ------------------------------------------------ 渠道白名单

    @Test
    fun `渠道在白名单里时，标题再普通也照样解析`() {
        // 有些版本的标题只写「服务通知」，但渠道是支付专用渠道 —— 渠道比标题硬
        val result = PaymentNotificationParser.parse(
            packageName = wechat,
            title = "服务通知",
            text = "12.00元",
            senders = emptyList(),
            channelId = "channel_id=2",
            channels = listOf("channel_id=2"),
        )
        val payment = (result as? NotificationParse.Parsed)?.payment
        assertEquals(1200L, payment!!.amountCents)
        assertEquals(
            "渠道可信但方向不明 → 标成待确认让用户核对",
            ParsedPayment.Confidence.LOW,
            payment.confidence,
        )
    }

    @Test
    fun `渠道不在白名单、标题也不认识时仍然是忽略`() {
        val result = PaymentNotificationParser.parse(
            packageName = wechat,
            title = "服务通知",
            text = "在吗",
            senders = emptyList(),
            channelId = "channel_id=2",
            channels = listOf("channel_id=9"),
        )
        assertEquals(NotificationParse.Ignore, result)
    }

    @Test
    fun `渠道是精确匹配，不会因为前缀相同而误判`() {
        // 渠道 id 是稳定标识，做包含匹配反而会误伤：channel_id=2 不该匹配 channel_id=20
        assertFalse(
            PaymentNotificationParser.isTrustedChannel("channel_id=20", listOf("channel_id=2")),
        )
        assertTrue(
            PaymentNotificationParser.isTrustedChannel("channel_id=2", listOf("channel_id=2")),
        )
        assertFalse("空渠道不参与判断", PaymentNotificationParser.isTrustedChannel("", listOf("x")))
    }

    @Test
    fun `方向判断：两个关键词都出现时看谁先出现`() {
        assertEquals(false, PaymentNotificationParser.detectDirection("已支付¥10，收款方已确认"))
        assertEquals(true, PaymentNotificationParser.detectDirection("收款到账¥10，来自付款方"))
        assertNull(PaymentNotificationParser.detectDirection("¥10.00"))
    }

    @Test
    fun `渠道名里的「支付」不能把收款通知误判成支出`() {
        assertEquals(true, PaymentNotificationParser.detectDirection("微信支付 收款到账 ¥50.00"))
        assertEquals(true, PaymentNotificationParser.detectDirection("支付宝 退款成功 ¥19.90"))
        assertEquals(false, PaymentNotificationParser.detectDirection("微信支付 已支付 ¥25.50"))
    }

    @Test
    fun `认不出商户时回落到渠道名，不留空`() {
        assertEquals("支付宝", parsed(alipay, "支付宝", "支付宝支付成功 ￥5.00")!!.merchant)
    }

    @Test
    fun `同一份通知重复解析结果稳定（纯函数）`() {
        val a = parsed(wechat, "微信支付", "已支付¥25.50，向「南校区2食堂」付款")
        val b = parsed(wechat, "微信支付", "已支付¥25.50，向「南校区2食堂」付款")
        assertEquals(a, b)
    }
}
