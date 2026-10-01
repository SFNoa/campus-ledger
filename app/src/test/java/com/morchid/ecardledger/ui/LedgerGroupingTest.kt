package com.morchid.ecardledger.ui

import com.morchid.ecardledger.data.AccountIds
import com.morchid.ecardledger.data.EntrySource
import com.morchid.ecardledger.data.LedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * 流水页的周期分组。
 *
 * 纯函数，不需要 Robolectric —— 分组算错会让「日流水」的合计全部对不上，
 * 这是最该被测试盯住的一段逻辑。
 */
class LedgerGroupingTest {

    private fun entry(
        orderId: String,
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 12,
        amountCents: Long = 1000,
        isIncome: Boolean = false,
        isTransfer: Boolean = false,
    ): LedgerEntry {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(year, month - 1, day, hour, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return LedgerEntry(
            orderId = orderId,
            timeText = "",
            epochMillis = cal.timeInMillis,
            amountCents = amountCents,
            isIncome = isIncome,
            kind = if (isIncome) "收入" else "支出",
            merchant = "测试",
            payName = "",
            balanceCents = 0,
            toAccount = "",
            category = "餐饮",
            note = "",
            manual = false,
            accountId = AccountIds.WECHAT,
            source = EntrySource.MANUAL,
            isTransfer = isTransfer,
        )
    }

    @Test
    fun `全部周期不分组，条数不变`() {
        val list = listOf(
            entry("a", 2026, 8, 5),
            entry("b", 2026, 8, 6),
        )
        val rows = groupEntries(list, LedgerPeriod.ALL)
        assertEquals(2, rows.size)
        assertTrue("全部周期不该插表头", rows.all { it is LedgerRow.Item })
    }

    @Test
    fun `按日分组：每天一个表头，组内合计正确`() {
        val list = listOf(
            entry("a", 2026, 8, 6, amountCents = 2500),
            entry("b", 2026, 8, 6, amountCents = 800, isIncome = true),
            entry("c", 2026, 8, 5, amountCents = 300),
        )
        val rows = groupEntries(list, LedgerPeriod.DAY)

        val headers = rows.filterIsInstance<LedgerRow.Header>()
        assertEquals(2, headers.size)
        assertEquals("2026-08-06", headers[0].title)
        assertEquals("这一天收入 8 元", 800L, headers[0].incomeCents)
        assertEquals("这一天支出 25 元", 2500L, headers[0].expenseCents)
        assertEquals("2026-08-05", headers[1].title)
        assertEquals(300L, headers[1].expenseCents)

        // 表头必须排在它那一组的前面
        assertEquals(5, rows.size)
        assertTrue(rows[0] is LedgerRow.Header)
        assertTrue(rows[3] is LedgerRow.Header)
    }

    @Test
    fun `按月与按年分组`() {
        val list = listOf(
            entry("a", 2026, 8, 6),
            entry("b", 2026, 7, 30),
            entry("c", 2025, 12, 31),
        )
        val months = groupEntries(list, LedgerPeriod.MONTH)
            .filterIsInstance<LedgerRow.Header>()
            .map { it.title }
        assertEquals(listOf("2026-08", "2026-07", "2025-12"), months)

        val years = groupEntries(list, LedgerPeriod.YEAR)
            .filterIsInstance<LedgerRow.Header>()
            .map { it.title }
        assertEquals(listOf("2026", "2025"), years)
    }

    @Test
    fun `内部转账不计入分组合计——它不是真的收支`() {
        val list = listOf(
            entry("a", 2026, 8, 6, amountCents = 20000, isIncome = true, isTransfer = true),
            entry("b", 2026, 8, 6, amountCents = 2500),
        )
        val header = groupEntries(list, LedgerPeriod.DAY)
            .filterIsInstance<LedgerRow.Header>()
            .single()
        assertEquals("充值的 200 元是左右口袋，不计入收入", 0L, header.incomeCents)
        assertEquals(2500L, header.expenseCents)
    }

    @Test
    fun `同一天的记录不会被拆成两个表头`() {
        val list = (1..5).map { entry("e$it", 2026, 8, 6, hour = it) }
        val rows = groupEntries(list, LedgerPeriod.DAY)
        assertEquals(1, rows.count { it is LedgerRow.Header })
        assertEquals(6, rows.size)
    }

    @Test
    fun `时间键与分组表头用的是同一套写法`() {
        // 「按日期筛选」和分组共用 key，否则会出现"选中的日期和表头对不上"
        val e = entry("k", 2026, 8, 6)
        assertEquals("2026-08-06", dayKeyOf(e.epochMillis))
        assertEquals("2026-08", monthKeyOf(e.epochMillis))
        assertEquals("2026", yearKeyOf(e.epochMillis))

        val header = groupEntries(listOf(e), LedgerPeriod.DAY)
            .filterIsInstance<LedgerRow.Header>()
            .single()
        assertEquals("表头标题必须等于日期键", dayKeyOf(e.epochMillis), header.title)
    }

    @Test
    fun `跨时区也要落在同一天`() {
        // 北京时间 08-06 00:30 = UTC 08-05 16:30，按学校时区算必须还是 08-06
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, 7, 6, 0, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("2026-08-06", dayKeyOf(cal.timeInMillis))
        assertEquals(
            "换成 UTC 就是前一天了 —— 正是不能用 UTC 的原因",
            "2026-08-05",
            dayKeyOf(cal.timeInMillis, TimeZone.getTimeZone("UTC")),
        )
    }

    // ------------------------------------------------ 日期条

    private fun noonOf(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(year, month - 1, day, 12, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `日期条：共 31 天，正中是今天，左右各 15 天`() {
        val now = noonOf(2026, 10, 1)
        val items = buildPeriodStrip(LedgerPeriod.DAY, now)

        assertEquals(31, items.size)
        assertEquals("今天", items[15].second)
        assertEquals(dayKeyOf(now), items[15].first)
        assertEquals("2026-09-28", items[12].first) // 左边三天
        assertEquals("10.2", items[16].second)      // 右边一天
        assertEquals("2026-10-16", items[30].first) // 右边十五天
    }

    @Test
    fun `日期条的键必须和分组表头对得上`() {
        // 两边用不同算法算"这一天是哪天"，就会出现"选了 10-01、表头写 09-30"这种鬼问题
        val now = noonOf(2026, 10, 1)
        val e = entry("s1", 2026, 10, 1)

        val dayHeader = groupEntries(listOf(e), LedgerPeriod.DAY)
            .filterIsInstance<LedgerRow.Header>().single().title
        assertTrue(
            "日：表头 $dayHeader 必须能在日期条里找到",
            buildPeriodStrip(LedgerPeriod.DAY, now).any { it.first == dayHeader },
        )

        val monthHeader = groupEntries(listOf(e), LedgerPeriod.MONTH)
            .filterIsInstance<LedgerRow.Header>().single().title
        assertTrue(
            "月：表头 $monthHeader 必须能在日期条里找到",
            buildPeriodStrip(LedgerPeriod.MONTH, now).any { it.first == monthHeader },
        )

        val yearHeader = groupEntries(listOf(e), LedgerPeriod.YEAR)
            .filterIsInstance<LedgerRow.Header>().single().title
        assertTrue(
            "年：表头 $yearHeader 必须能在日期条里找到",
            buildPeriodStrip(LedgerPeriod.YEAR, now).any { it.first == yearHeader },
        )
    }

    @Test
    fun `月和年的条目数与居中项`() {
        val now = noonOf(2026, 10, 1)

        val months = buildPeriodStrip(LedgerPeriod.MONTH, now)
        assertEquals(13, months.size)
        assertEquals("本月", months[6].second)
        assertEquals("2026-10", months[6].first)
        assertEquals("2026-07", months[3].first)

        val years = buildPeriodStrip(LedgerPeriod.YEAR, now)
        assertEquals(7, years.size)
        assertEquals("今年", years[3].second)
        assertEquals("2026", years[3].first)
        assertEquals("2023", years[0].first)
    }

    @Test
    fun `全部周期没有日期条`() {
        assertTrue(buildPeriodStrip(LedgerPeriod.ALL, noonOf(2026, 10, 1)).isEmpty())
    }

    @Test
    fun `跨月边界也对：十月初往前十五天落到九月`() {
        val items = buildPeriodStrip(LedgerPeriod.DAY, noonOf(2026, 10, 1))
        assertEquals("2026-09-16", items[0].first)
        assertEquals("9.30", items[14].second)
    }
}
