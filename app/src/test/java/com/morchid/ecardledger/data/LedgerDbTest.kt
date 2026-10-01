package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * SQLite 持久化层测试（Robolectric 在 JVM 上提供真实的 SQLite）。
 *
 * 重点验证「重复同步不会产生重复账目」——这依赖 order_id 的 UNIQUE 约束
 * 加 insertWithOnConflict(CONFLICT_IGNORE)。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerDbTest {

    private lateinit var db: LedgerDb

    @Before
    fun setUp() {
        db = LedgerDb(RuntimeEnvironment.getApplication())
    }

    private fun entry(
        orderId: String,
        amountCents: Long = 400,
        isIncome: Boolean = false,
        category: String = "餐饮",
        epochMillis: Long = 1_790_000_000_000,
        manual: Boolean = false,
    ) = LedgerEntry(
        orderId = orderId,
        timeText = "2026-09-28 12:02:13",
        epochMillis = epochMillis,
        amountCents = amountCents,
        isIncome = isIncome,
        kind = if (isIncome) "充值" else "消费",
        merchant = "南校区2食堂2楼白案组",
        payName = "持卡人消费",
        balanceCents = 13830,
        toAccount = "3200010",
        category = category,
        note = "",
        manual = manual,
    )

    @Test
    fun `插入后能完整读回`() {
        assertTrue(db.insert(entry("A1")))

        assertEquals(1, db.count())
        val row = db.all().single()
        assertEquals("A1", row.orderId)
        assertEquals("2026-09-28 12:02:13", row.timeText)
        assertEquals(1_790_000_000_000, row.epochMillis)
        assertEquals(400, row.amountCents)
        assertFalse(row.isIncome)
        assertEquals("南校区2食堂2楼白案组", row.merchant)
        assertEquals(13830, row.balanceCents)
        assertEquals("3200010", row.toAccount)
        assertEquals("餐饮", row.category)
    }

    @Test
    fun `同一 orderId 重复插入被忽略，金额保持第一次的值`() {
        assertTrue("首次插入应当成功", db.insert(entry("A1", amountCents = 400)))
        assertFalse("同一 orderId 第二次插入应当被忽略", db.insert(entry("A1", amountCents = 999)))

        assertEquals("重复同步不能产生第二行", 1, db.count())
        assertEquals("已有记录不该被覆盖", 400, db.all().single().amountCents)
    }

    @Test
    fun `不同 orderId 各自成行`() {
        assertTrue(db.insert(entry("A1")))
        assertTrue(db.insert(entry("A2")))
        assertTrue(db.insert(entry("A3")))
        assertEquals(3, db.count())
    }

    @Test
    fun `收入标记能正确往返`() {
        db.insert(entry("IN1", isIncome = true, category = "充值"))
        db.insert(entry("OUT1", isIncome = false))

        val all = db.all()
        assertEquals(1, all.count { it.isIncome })
        assertEquals(1, all.count { !it.isIncome })
        // 有符号金额：收入为正、支出为负
        assertEquals(400L, all.first { it.isIncome }.signedCents)
        assertEquals(-400L, all.first { !it.isIncome }.signedCents)
    }

    @Test
    fun `改分类和备注会落盘`() {
        db.insert(entry("A1", category = "其他"))
        db.updateCategory("A1", "学习")
        db.updateNote("A1", "买教材")

        val row = db.all().single()
        assertEquals("学习", row.category)
        assertEquals("买教材", row.note)
    }

    @Test
    fun `between 按时间区间过滤`() {
        db.insert(entry("OLD", epochMillis = 1_000))
        db.insert(entry("MID", epochMillis = 2_000))
        db.insert(entry("NEW", epochMillis = 3_000))

        assertEquals(listOf("MID"), db.between(1_500, 2_500).map { it.orderId })
        // between 与 all() 一致，按时间倒序返回
        assertEquals(listOf("NEW", "MID"), db.between(1_500, 9_999).map { it.orderId })
        assertEquals(0, db.between(10_000, 20_000).size)
    }

    @Test
    fun `查询按时间倒序`() {
        db.insert(entry("OLD", epochMillis = 1_000))
        db.insert(entry("NEW", epochMillis = 3_000))
        db.insert(entry("MID", epochMillis = 2_000))
        assertEquals(listOf("NEW", "MID", "OLD"), db.all().map { it.orderId })
    }

    @Test
    fun `删除单条`() {
        db.insert(entry("A1"))
        db.insert(entry("A2"))
        db.delete("A1")
        assertEquals(listOf("A2"), db.all().map { it.orderId })
    }

    @Test
    fun `meta 键值能往返并覆盖`() {
        assertEquals(null, db.getMeta("last_sync"))
        db.putMeta("last_sync", "2026-09-28 13:00")
        assertEquals("2026-09-28 13:00", db.getMeta("last_sync"))
        db.putMeta("last_sync", "2026-09-28 14:00")
        assertEquals("2026-09-28 14:00", db.getMeta("last_sync"))
    }

    @Test
    fun `usedCategories 按出现次数倒序且跳过空分类`() {
        db.insert(entry("A1", category = "餐饮"))
        db.insert(entry("A2", category = "餐饮"))
        db.insert(entry("A3", category = "洗浴"))
        db.insert(entry("A4", category = ""))

        assertEquals(listOf("餐饮", "洗浴"), db.usedCategories())
    }

    @Test
    fun `最近一笔带余额的流水能被取到（App 重启后兜底显示余额）`() {
        db.insert(entry("OLD", epochMillis = 1_000L).copy(balanceCents = 111))
        db.insert(entry("MID", epochMillis = 2_000L).copy(balanceCents = 222))
        db.insert(entry("NEW", epochMillis = 3_000L).copy(balanceCents = 333))
        assertEquals(
            "all() 按时间倒序，第一笔带余额的就是最新那笔",
            333L,
            db.all().first { it.balanceCents > 0 }.balanceCents,
        )
    }

    @Test
    fun `手动记账余额为 0，不会被误当成兜底余额`() {
        db.insert(entry("MANUAL", epochMillis = 9_000L, manual = true).copy(balanceCents = 0))
        assertEquals(0, db.all().count { it.balanceCents > 0 })
    }

    @Test
    fun `关掉再打开同一个库，数据还在（真的落盘了）`() {
        db.insert(entry("A1"))
        db.close()

        val reopened = LedgerDb(RuntimeEnvironment.getApplication())
        assertEquals("重开数据库后数据应当还在", 1, reopened.count())
        assertEquals("A1", reopened.all().single().orderId)
    }
}
