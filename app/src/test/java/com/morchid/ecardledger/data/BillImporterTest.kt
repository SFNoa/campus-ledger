package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 账单解析测试。
 *
 * 样本是按**真实导出的结构**构造的：
 *  - 微信账单前面有十几行账户元信息与常见问题，中间一行 `-----列表----`，然后是表头
 *  - 支付宝账单前面是「交易记录明细查询 / 账号 / 起始日期 / 横线」，然后是表头
 *  - 两边都有「不计收支」这种不算真实收支的行（转账、还款）
 *
 * 行数刻意写得和真实情况接近（微信 ~17 行、支付宝 ~23 行），
 * 但解析器**不依赖行数**，而是找表头 + 按列名映射，所以顺序变化也能扛。
 */
class BillImporterTest {

    private fun wechatBill(vararg dataRows: String): String {
        val meta = (1..14).joinToString("\n") { "微信支付账单明细元信息第 $it 行,,,,,,,,,," }
        return buildString {
            appendLine("微信支付账单明细,,,,,,,,,,")
            appendLine("微信昵称：[张三],,,,,,,,,,")
            appendLine("起始时间：[2026-08-01 00:00:00] 终止时间：[2026-08-31 23:59:59],,,,,,,,,,")
            appendLine("导出类型：[全部],,,,,,,,,,")
            appendLine("导出时间：[2026-09-01 10:00:00],,,,,,,,,,")
            appendLine(meta)
            appendLine("----------------------微信支付账单明细列表--------------------,,,,,,,,,,")
            appendLine("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注")
            dataRows.forEach { appendLine(it) }
        }
    }

    private fun alipayBill(vararg dataRows: String): String {
        val meta = (1..16).joinToString("\n") { "支付宝账单元信息第 $it 行" }
        return buildString {
            appendLine("支付宝交易记录明细查询")
            appendLine("账号:[zhangsan@example.com]")
            appendLine("起始日期:[2026-08-01 00:00:00]    终止日期:[2026-08-31 23:59:59]")
            appendLine(meta)
            appendLine("---------------------------------交易记录明细列表------------------------------------")
            appendLine("交易时间,交易分类,交易对方,对方账号,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注")
            dataRows.forEach { appendLine(it) }
        }
    }

    // ------------------------------------------------------------ 微信

    @Test
    fun `微信账单能跳过元信息并按列名解析`() {
        val result = BillImporter.parseText(
            wechatBill(
                "2026-08-05 12:30:45,商户消费,南校区2食堂,餐饮,支出,¥25.50,零钱,支付成功,420000123,100001,",
                "2026-08-06 09:10:00,微信红包,张三,/,收入,¥88.00,零钱,已存入零钱,100003950,,",
            ),
        )

        assertEquals(BillImporter.Platform.WECHAT, result.platform)
        assertEquals(2, result.records.size)

        val expense = result.records[0]
        assertEquals(BillImporter.Direction.EXPENSE, expense.direction)
        assertEquals(2550L, expense.amountCents)
        assertEquals("南校区2食堂", expense.peer)
        assertEquals("餐饮", expense.item)
        assertEquals("420000123", expense.orderId)
        assertEquals("2026-08-05 12:30:45", expense.timeText)

        val income = result.records[1]
        assertEquals(BillImporter.Direction.INCOME, income.direction)
        assertEquals(8800L, income.amountCents)

        assertEquals(2550L, result.expenseCents)
        assertEquals(8800L, result.incomeCents)
    }

    @Test
    fun `不计收支的转账行会被跳过`() {
        val result = BillImporter.parseText(
            wechatBill(
                "2026-08-05 12:30:45,商户消费,食堂,餐饮,支出,¥25.50,零钱,支付成功,1,,",
                "2026-08-07 20:00:00,转账,李四,/,不计收支,¥200.00,零钱,已转账,2,,",
            ),
        )
        assertEquals("只应该留下真实收支那一笔", 1, result.records.size)
        assertEquals(1, result.skippedRows)
    }

    @Test
    fun `交易关闭的行不算`() {
        val result = BillImporter.parseText(
            wechatBill(
                "2026-08-05 12:30:45,商户消费,食堂,餐饮,支出,¥25.50,零钱,已关闭,1,,",
            ),
        )
        assertTrue(result.records.isEmpty())
    }

    @Test
    fun `商品名里有逗号也能正确切分`() {
        val result = BillImporter.parseText(
            wechatBill(
                "2026-08-05 12:30:45,商户消费,某商户,\"套餐A,加大份\",支出,¥30.00,零钱,支付成功,1,,",
            ),
        )
        assertEquals(1, result.records.size)
        assertEquals("套餐A,加大份", result.records[0].item)
        assertEquals(3000L, result.records[0].amountCents)
    }

    @Test
    fun `列顺序变了也认得出`() {
        // 故意把表头和数据的列顺序调换
        val text = buildString {
            appendLine("微信支付账单明细,,,,,")
            appendLine("金额(元),收/支,交易时间,交易对方,交易类型,当前状态,交易单号,支付方式,商品,商户单号,备注")
            appendLine("¥12.34,支出,2026-08-05 12:30:45,便利店,商户消费,支付成功,999,零钱,饮料,,")
        }
        val result = BillImporter.parseText(text)
        assertEquals(1, result.records.size)
        assertEquals(1234L, result.records[0].amountCents)
        assertEquals("便利店", result.records[0].peer)
        assertEquals("999", result.records[0].orderId)
    }

    // ------------------------------------------------------------ 支付宝

    @Test
    fun `支付宝账单能识别平台并解析`() {
        val result = BillImporter.parseText(
            alipayBill(
                "2026-08-10 18:20:11,餐饮美食,肯德基,ken@example.com,晚餐,支出,35.00,余额宝,交易成功,2026081022001234,3050,",
                "2026-08-11 08:00:00,转账红包,王五,wang@example.com,转账,收入,50.00,余额,交易成功,2026081122001235,,",
            ),
        )
        assertEquals(BillImporter.Platform.ALIPAY, result.platform)
        assertEquals(2, result.records.size)
        assertEquals(3500L, result.records[0].amountCents)
        assertEquals(BillImporter.Direction.EXPENSE, result.records[0].direction)
        assertEquals("肯德基", result.records[0].peer)
        assertEquals("余额宝", result.records[0].method)
        assertEquals("2026081022001234", result.records[0].orderId)
        assertEquals(BillImporter.Direction.INCOME, result.records[1].direction)
    }

    @Test
    fun `两种账单都不会把对方认成自己`() {
        assertEquals(
            BillImporter.Platform.WECHAT,
            BillImporter.parseText(wechatBill()).platform,
        )
        assertEquals(
            BillImporter.Platform.ALIPAY,
            BillImporter.parseText(alipayBill()).platform,
        )
    }

    // ------------------------------------------------------------ 编码与容错

    @Test
    fun `UTF-8 BOM 不影响解析`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            wechatBill("2026-08-05 12:30:45,商户消费,食堂,餐饮,支出,¥25.50,零钱,支付成功,1,,")
                .toByteArray(Charsets.UTF_8)
        val result = BillImporter.parse(bytes)
        assertEquals(1, result.records.size)
    }

    @Test
    fun `GBK 编码的账单也能解码`() {
        val text = alipayBill(
            "2026-08-10 18:20:11,餐饮美食,肯德基,ken@example.com,晚餐,支出,35.00,余额宝,交易成功,2026081022001234,,",
        )
        val bytes = text.toByteArray(charset("GBK"))
        val result = BillImporter.parse(bytes)
        assertEquals("支付宝的老账单是 GBK，必须能读", 1, result.records.size)
        assertEquals("肯德基", result.records[0].peer)
    }

    @Test
    fun `找不到表头时给出能看懂的提示，而不是静默失败`() {
        val result = BillImporter.parseText("这不是账单\n随便什么内容")
        assertTrue(result.records.isEmpty())
        assertTrue(
            "提示里要说清怎么办：${result.warnings}",
            result.warnings.any { it.contains("表头") && it.contains("CSV") },
        )
    }

    @Test
    fun `金额的各种写法都能换算成分`() {
        assertEquals(123456L, BillImporter.parseAmountCents("¥1,234.56"))
        assertEquals(123456L, BillImporter.parseAmountCents("1234.56"))
        assertEquals(1200L, BillImporter.parseAmountCents("￥12"))
        assertEquals(8800L, BillImporter.parseAmountCents("88.00元"))
        assertEquals(50L, BillImporter.parseAmountCents("0.50"))
        assertNull(BillImporter.parseAmountCents(""))
        assertNull(BillImporter.parseAmountCents("/"))
        assertNull(BillImporter.parseAmountCents("没有数字"))
    }

    @Test
    fun `空行与表尾汇总行只是跳过，不会当成记录`() {
        val result = BillImporter.parseText(
            wechatBill(
                "2026-08-05 12:30:45,商户消费,食堂,餐饮,支出,¥25.50,零钱,支付成功,1,,",
                "",
                "共 1 笔记录,,,,,,,,,,",
            ),
        )
        assertEquals(1, result.records.size)
        assertEquals("表尾汇总行算跳过的，空行不算", 1, result.skippedRows)
    }

    @Test
    fun `金额带负号也一律取绝对值——方向由收支那一列说了算`() {
        // 真机反馈「有的收入会变成支出」：早期版本保留了负号，
        // 于是「-25.00 + 支出」在统计里算成了正数（看起来就是方向反了）
        assertEquals(2500L, BillImporter.parseAmountCents("-25.00"))
        assertEquals(2500L, BillImporter.parseAmountCents("-¥25.00"))
        assertEquals(8800L, BillImporter.parseAmountCents("+88.00"))
    }

    @Test
    fun `带负号的收入行仍然记成收入`() {
        val result = BillImporter.parseText(
            wechatBill(
                "2026-08-06 09:10:00,微信红包,张三,/,收入,-88.00,零钱,已存入零钱,1,,",
            ),
        )
        val record = result.records.single()
        assertEquals("方向看收/支列", BillImporter.Direction.INCOME, record.direction)
        assertEquals("金额存正数", 8800L, record.amountCents)
    }
}
