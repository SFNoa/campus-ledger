package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 真实联网集成测试：用**生产代码**（HttpClient + AesPwd + EcardApi）走完整流程。
 *
 * 这是本机所能做到的最强验证 —— 不是「逻辑等价」，而是真的跑这份要打进 APK 的代码。
 * 运行方式（凭据只放环境变量，绝不写进代码或提交）：
 *
 *   $env:CSU_USER='学号'; $env:CSU_PASS='密码'
 *   gradle testDebugUnitTest --tests "*EcardLiveIT*"
 *
 * 未设置环境变量时自动跳过，所以 `gradle test` 依旧是纯离线的。
 */
class EcardLiveIT {

    private val user: String = System.getenv("CSU_USER").orEmpty()
    private val pass: String = System.getenv("CSU_PASS").orEmpty()

    @Test
    fun `生产代码能登录并抓回真实校园卡数据`() {
        assumeTrue(
            "未设置 CSU_USER / CSU_PASS，跳过联网集成测试",
            user.isNotEmpty() && pass.isNotEmpty(),
        )

        val http = HttpClient()

        // ---------- 1. 登录 ----------
        val token = EcardApi.login(http, user, pass)
        assertEquals("token 应当是 JWT（两段点号）", 2, token.count { it == '.' })
        println("[live] 登录成功，token 长度 = ${token.length}")

        // ---------- 2. 卡信息 ----------
        val card = EcardApi.queryCard(http, token)
        println("[live] 卡=${card.cardName} 持卡人=${card.ownerName} 余额=${card.balanceCents / 100.0} 元 sno=${card.sno}")
        assertTrue("余额应 >= 0", card.balanceCents >= 0)
        assertTrue("卡名不应为空", card.cardName.isNotEmpty())
        // 实测发现：queryCard 返回的学号/姓名是**服务端脱敏**的（形如 8********7、吕*远），
        // 因此不能与登录账号做全等比较，只能验证它是脱敏格式且非空。
        assertTrue("卡信息里的学号不该为空: '${card.sno}'", card.sno.isNotEmpty())
        assertTrue(
            "学号应当是脱敏格式（含 *）: '${card.sno}'",
            card.sno.contains("*") || card.sno == user,
        )

        // ---------- 3. 流水 ----------
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
        val cal = Calendar.getInstance()
        val to = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -400)
        val from = fmt.format(cal.time)

        val page = EcardApi.queryTurnover(http, token, from, to, 1, 100)
        println("[live] $from ~ $to  total=${page.total} 本页取回=${page.records.size} 条")
        assertTrue("应当取到流水", page.records.isNotEmpty())

        val first = page.records.first()
        println(
            "[live] 首条: ${first.timeText} | ${first.amountCents / 100.0} 元 | " +
                "收入=${first.isIncome} | ${first.kind} | ${first.merchant} | " +
                "交易后余额=${first.balanceCents / 100.0} 元",
        )

        // ---------- 4. 业务规则断言 ----------
        page.records.forEach { r ->
            assertTrue("金额必须为正数（方向由 isIncome 表示）: $r", r.amountCents > 0)
            assertTrue(
                "商户名不该残留 -持卡人消费 后缀: ${r.merchant}",
                !r.merchant.contains("-持卡人消费"),
            )
            assertTrue("orderId 不该为空", r.orderId.isNotEmpty())
            assertTrue("时间文本应当可用: ${r.timeText}", r.timeText.length >= 10)
            assertTrue("交易类型不该为空", r.kind.isNotEmpty())
        }
        assertEquals(
            "orderId 应唯一（去重键）",
            page.records.size,
            page.records.map { it.orderId }.toSet().size,
        )

        val expenses = page.records.filter { !it.isIncome }
        val incomes = page.records.filter { it.isIncome }
        println("[live] 支出 ${expenses.size} 条，合计 ${expenses.sumOf { it.amountCents } / 100.0} 元")
        println("[live] 收入 ${incomes.size} 条，合计 ${incomes.sumOf { it.amountCents } / 100.0} 元")
        // 交叉核对（仅供参考，不硬断言：只有当查询区间覆盖了从空卡开始的全部交易时才相等）
        val net = (incomes.sumOf { it.amountCents } - expenses.sumOf { it.amountCents }) / 100.0
        println("[live] 交叉核对：收入-支出 = $net 元，queryCard 余额 = ${card.balanceCents / 100.0} 元")
        assertTrue(
            "不该出现把「持卡人消费」当商户名的情况",
            page.records.none { it.merchant == "持卡人消费" },
        )
        assertTrue("应当存在支出记录", expenses.isNotEmpty())

        // 实测结论：消费 → typeId=1 → 支出；充值 → typeId=2 → 收入
        val consumptions = page.records.filter { it.kind == "消费" }
        val recharges = page.records.filter { it.kind == "充值" }
        assertTrue("应当有消费记录", consumptions.isNotEmpty())
        assertTrue("消费必须判为支出", consumptions.all { !it.isIncome })
        if (recharges.isNotEmpty()) {
            println("[live] 充值 ${recharges.size} 条 → 全部判为收入: ${recharges.all { it.isIncome }}")
            assertTrue("充值必须判为收入", recharges.all { it.isIncome })
        }

        // 交易后余额应当是个合理值（分）
        assertTrue(
            "交易后余额应当 >= 0 且不至于离谱",
            page.records.all { it.balanceCents in 0..100_000_00L },
        )
    }

    /**
     * 回归测试：同一个进程里**连续两次**登录都必须成功。
     *
     * 线上 bug —— CookieManager 是进程级的，第一次登录成功后 CAS 会话仍然有效，
     * 第二次 GET 登录页会被直接 302 送回 ecard，老代码在那一步抛
     * 「找不到 id=pwdFromId 的表单」。这个用例如果早点写，就不会漏掉。
     */
    @Test
    fun `同一个进程里连续两次登录都要成功`() {
        assumeTrue(
            "未设置 CSU_USER / CSU_PASS，跳过联网集成测试",
            user.isNotEmpty() && pass.isNotEmpty(),
        )

        val http = HttpClient()
        val first = EcardApi.login(http, user, pass)
        val second = EcardApi.login(http, user, pass)

        assertTrue("第一次登录应拿到 token", first.isNotEmpty())
        assertTrue("第二次登录也必须成功（应复用已有 CAS 会话）", second.isNotEmpty())
        assertEquals("token 应当是 JWT", 2, second.count { it == '.' })
        println("[live] 连续两次登录都成功，token 长度 ${first.length} / ${second.length}")

        // 顺带确认第二次之后仍然能正常取数
        val card = EcardApi.queryCard(http, second)
        println("[live] 第二次会话取卡信息：余额=${card.balanceCents / 100.0} 元")
        assertTrue(card.balanceCents >= 0)
    }
}
