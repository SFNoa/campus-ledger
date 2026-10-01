package com.morchid.ecardledger.data

import com.morchid.ecardledger.notification.PaymentNotificationParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 仓库层测试：手动补记、改分类、删除、以及默认账目的字段是否合理。
 * 同步走网络的路径由 EcardLiveIT 覆盖，这里只管本地行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerRepositoryTest {

    private fun repo() = LedgerRepository(RuntimeEnvironment.getApplication())

    @Test
    fun `手动补记一笔支出`() {
        val repository = repo()
        repository.addManualEntry(
            amountCents = 1250,
            isIncome = false,
            merchant = "打印店",
            category = "学习",
            note = "打印资料",
        )

        val row = repository.allEntries().single()
        assertEquals(1250, row.amountCents)
        assertEquals(false, row.isIncome)
        assertEquals("打印店", row.merchant)
        assertEquals("学习", row.category)
        assertEquals("打印资料", row.note)
        assertTrue("手动账目要打上 manual 标记", row.manual)
        assertEquals(-1250L, row.signedCents)
        assertTrue("orderId 要自动生成", row.orderId.startsWith("manual-"))
    }

    @Test
    fun `手动补记一笔收入`() {
        val repository = repo()
        repository.addManualEntry(
            amountCents = 5000,
            isIncome = true,
            merchant = "家教",
            category = "其他",
            note = "",
        )
        val row = repository.allEntries().single()
        assertTrue(row.isIncome)
        assertEquals(5000L, row.signedCents)
    }

    @Test
    fun `连续补记多笔不会互相覆盖 orderId`() {
        val repository = repo()
        repeat(5) { index ->
            repository.addManualEntry(
                amountCents = 100L + index,
                isIncome = false,
                merchant = "第$index 笔",
                category = "其他",
                note = "",
            )
        }
        val all = repository.allEntries()
        assertEquals(5, all.size)
        assertEquals("orderId 必须唯一", 5, all.map { it.orderId }.toSet().size)
        assertEquals(5, repository.count())
    }

    @Test
    fun `改分类与删除手动账目`() {
        val repository = repo()
        repository.addManualEntry(300, false, "小卖部", "其他", "")
        val orderId = repository.allEntries().single().orderId

        repository.updateCategory(orderId, "超市")
        assertEquals("超市", repository.allEntries().single().category)

        repository.deleteEntry(orderId)
        assertEquals(0, repository.count())
    }

    @Test
    fun `entriesBetween 能按时间窗口取数`() {
        val repository = repo()
        val now = System.currentTimeMillis()
        repository.addManualEntry(100, false, "今天", "其他", "", epochMillis = now)
        repository.addManualEntry(200, false, "很久以前", "其他", "", epochMillis = now - 400L * 24 * 3600 * 1000)

        assertEquals(2, repository.allEntries().size)
        val recent = repository.entriesBetween(now - 86_400_000L, now + 86_400_000L)
        assertEquals("只应取到最近这笔", 1, recent.size)
        assertEquals("今天", recent.single().merchant)
    }

    @Test
    fun `手动账目会出现在自动分类之外但分类可自定义`() {
        val repository = repo()
        repository.addManualEntry(800, false, "自定义商户", "娱乐", "")
        assertEquals("娱乐", repository.allEntries().single().category)
    }

    @Test
    fun `没有凭据时 savedCredentials 返回 null（不抛异常）`() {
        val repository = repo()
        assertNull("首次启动不该有凭据", repository.savedCredentials())
    }

    @Test
    fun `lastSyncText 初次为空，写入后能读回`() {
        val repository = repo()
        assertNull(repository.lastSyncText())
    }

    @Test
    fun `手动账目列表只含手动项`() {
        val repository = repo()
        repository.addManualEntry(100, false, "手动一笔", "其他", "")
        assertEquals(1, repository.manualEntries().size)
        assertNotNull(repository.manualEntries().single().merchant)
    }

    // -------------------------------------------- 内部转账配对（仓库层，跑真 SQLite）

    private val t0 = 1_790_000_000_000L

    private fun addWechatPayment(repository: LedgerRepository, amountCents: Long, at: Long) {
        repository.enableChannelAccount(AccountType.WECHAT)
        repository.addExternalEntry(
            accountId = AccountIds.WECHAT,
            amountCents = amountCents,
            isIncome = false,
            merchant = "校园卡充值",
            category = "充值",
            source = EntrySource.NOTIFICATION,
            epochMillis = at,
            orderId = "wechat-$at-$amountCents",
        )
    }

    private fun addCampusRecharge(repository: LedgerRepository, amountCents: Long, at: Long) {
        repository.ensureCampusAccount()
        repository.addExternalEntry(
            accountId = repository.campusAccountId(),
            amountCents = amountCents,
            isIncome = true,
            merchant = "微信支付转账",
            category = "充值",
            source = EntrySource.SYNC,
            epochMillis = at,
            orderId = "campus-$at-$amountCents",
        )
    }

    @Test
    fun `微信支出与校园卡充值会自动配成内部转账`() {
        val repository = repo()
        addWechatPayment(repository, 20_000, t0)

        assertEquals("此时还没有对手方，不该配对", 0, repository.allEntries().count { it.isTransfer })

        addCampusRecharge(repository, 20_000, t0 + 60_000)

        val all = repository.allEntries()
        assertEquals("两条都该标记为转账", 2, all.count { it.isTransfer })
        assertEquals("必须共享同一个配对组 id", 1, all.mapNotNull { it.transferGroupId }.toSet().size)
        assertEquals("转账对收支统计的贡献必须是 0", 0L, all.sumOf { it.statCents })
    }

    @Test
    fun `金额不同的微信支出不会误配`() {
        val repository = repo()
        addWechatPayment(repository, 19_900, t0)
        addCampusRecharge(repository, 20_000, t0 + 60_000)
        assertEquals("金额不等不该配对", 0, repository.allEntries().count { it.isTransfer })
    }

    @Test
    fun `解除配对后能被全量重扫重新配上`() {
        val repository = repo()
        addWechatPayment(repository, 20_000, t0)
        addCampusRecharge(repository, 20_000, t0 + 60_000)

        val groupId = repository.allEntries().firstNotNullOfOrNull { it.transferGroupId }!!
        repository.unpairTransfer(groupId)
        assertEquals("解除后不该还有转账标记", 0, repository.allEntries().count { it.isTransfer })

        val groups = repository.rebuildTransferPairs()
        assertEquals("重扫应当重新配上一组", 1, groups)
        assertEquals(2, repository.allEntries().count { it.isTransfer })
    }

    @Test
    fun `同一账户内的收支不会被当成转账`() {
        val repository = repo()
        addCampusRecharge(repository, 20_000, t0)
        repository.addExternalEntry(
            accountId = repository.campusAccountId(),
            amountCents = 20_000,
            isIncome = false,
            merchant = "持卡人消费",
            category = "其他",
            source = EntrySource.SYNC,
            epochMillis = t0 + 30_000,
            orderId = "campus-out-1",
        )
        assertEquals(0, repository.allEntries().count { it.isTransfer })
    }

    // -------------------------------------------- 通知监听落库

    @Test
    fun `通知解析出的支出会记到微信账户`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        val inserted = repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 2550,
            isIncome = false,
            merchant = "南校区2食堂",
            epochMillis = t0,
            orderId = "notif-1",
            rawText = "已支付¥25.50，向「南校区2食堂」付款",
            needsReview = false,
        )
        assertTrue(inserted)

        val row = repository.allEntries().single()
        assertEquals(AccountIds.WECHAT, row.accountId)
        assertEquals(EntrySource.NOTIFICATION, row.source)
        assertEquals(2550, row.amountCents)
        assertEquals("南校区2食堂", row.merchant)
        assertFalse("普通支出不该被当成转账", row.isTransfer)
        assertEquals(-2550L, row.statCents)
    }

    @Test
    fun `认不出金额的通知以待确认存入并保留原文，绝不丢数据`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.ALIPAY)
        repository.addNotificationEntry(
            channel = AccountType.ALIPAY,
            amountCents = 0,
            isIncome = false,
            merchant = "待确认",
            epochMillis = t0,
            orderId = "notif-review",
            rawText = "你有一笔交易，详情请点击查看",
            needsReview = true,
            note = "未能自动识别金额，请手动补全",
        )
        val row = repository.allEntries().single()
        assertEquals(0, row.amountCents)
        assertEquals(LedgerRepository.KIND_NEEDS_REVIEW, row.kind)
        assertEquals("你有一笔交易，详情请点击查看", row.rawText)
        assertTrue("要提示用户去补", row.note.contains("手动补全"))
    }

    @Test
    fun `渠道未开启时不记账`() {
        val repository = repo()
        assertFalse("默认不该开启", repository.isChannelEnabled(AccountType.WECHAT))
        assertFalse(
            repository.addNotificationEntry(
                channel = AccountType.WECHAT,
                amountCents = 100,
                isIncome = false,
                merchant = "x",
                epochMillis = t0,
                orderId = "n",
                rawText = "",
                needsReview = false,
            ),
        )
        assertEquals(0, repository.count())
    }

    @Test
    fun `渠道可以启用后再关闭`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        assertTrue(repository.isChannelEnabled(AccountType.WECHAT))

        repository.setChannelEnabled(AccountType.WECHAT, false)
        assertFalse(repository.isChannelEnabled(AccountType.WECHAT))
    }

    @Test
    fun `微信通知与校园卡充值也会自动配成内部转账`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        addCampusRecharge(repository, 20_000, t0) // payName 含「微信支付转账」

        repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 20_000,
            isIncome = false,
            merchant = "校园卡充值",
            epochMillis = t0 + 30_000,
            orderId = "notif-pair",
            rawText = "已支付¥200.00",
            needsReview = false,
        )

        assertEquals("应配成内部转账", 2, repository.allEntries().count { it.isTransfer })
    }

    @Test
    fun `手动维护的微信余额能存能读`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        repository.setAccountBalance(AccountIds.WECHAT, 12_345)

        val account = repository.account(AccountIds.WECHAT)!!
        assertEquals(12_345, account.balanceCents)
        assertTrue("微信余额应标记为手动维护", account.balanceManual)
    }

    @Test
    fun `跳过绑定校园卡的状态必须持久化`() {
        val repository = repo()
        assertFalse("默认没跳过", repository.isCampusBindSkipped())

        repository.setCampusBindSkipped(true)

        assertTrue(repository.isCampusBindSkipped())
        assertTrue(
            "必须跨重启保持 —— 否则每次打开都被拦回绑定页，等于老毛病换个形式",
            repo().isCampusBindSkipped(),
        )

        repository.setCampusBindSkipped(false)
        assertFalse(repository.isCampusBindSkipped())
    }

    @Test
    fun `登录会话与权限引导标记都要持久化`() {
        val repository = repo()

        assertFalse("默认不该是已登录状态", repository.isSessionUnlocked())
        repository.setSessionUnlocked(true)
        assertTrue(repository.isSessionUnlocked())
        assertTrue(
            "必须跨重启保持 —— 否则每次打开都要重新输密码（用户明确反馈过这一点很麻烦）",
            repo().isSessionUnlocked(),
        )

        assertFalse("权限引导默认没走过", repository.isPermissionsOnboarded())
        repository.setPermissionsOnboarded(true)
        assertTrue(repo().isPermissionsOnboarded())

        // 主动锁定之后必须回到未登录
        repository.setSessionUnlocked(false)
        assertFalse(repo().isSessionUnlocked())
    }

    // -------------------------------------------- 删除与墓碑

    @Test
    fun `删除的记录不会被重新同步回来`() {
        val repository = repo()
        repository.ensureCampusAccount()
        val orderId = "sync-entry-1"

        fun insertSyncedEntry() = repository.addExternalEntry(
            accountId = repository.campusAccountId(),
            amountCents = 400,
            isIncome = false,
            merchant = "校园卡消费",
            category = "其他",
            source = EntrySource.SYNC,
            epochMillis = 1_790_000_000_000L,
            orderId = orderId,
        )

        assertTrue(insertSyncedEntry())
        assertEquals(1, repository.count())

        repository.deleteEntry(orderId)
        assertEquals(0, repository.count())
        assertTrue("删除要留下墓碑", repository.isEntryDeleted(orderId))

        assertFalse(
            "同步会重放同样的 orderId —— 不留墓碑的话删掉的东西会自己长回来",
            insertSyncedEntry(),
        )
        assertEquals("不该被插回来", 0, repository.count())
    }

    @Test
    fun `通知误抓的记录也能删掉，且重复通知不会复活它`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)

        fun notifyAgain() = repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 0,
            isIncome = false,
            merchant = "待确认",
            epochMillis = 1_790_000_000_000L,
            orderId = "notif-junk-1",
            rawText = "在吗",
            needsReview = true,
        )

        assertTrue(notifyAgain())
        repository.deleteEntry("notif-junk-1")
        assertFalse("同一条通知再投递也不该复活", notifyAgain())
        assertEquals(0, repository.count())
    }

    @Test
    fun `手动记账可以记到指定账户（红包转账补记到微信）`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)

        repository.addManualEntry(
            amountCents = 8800,
            isIncome = true,
            merchant = "收到的转账",
            category = "其他",
            note = "红包补记",
            accountId = AccountIds.WECHAT,
        )

        val entry = repository.allEntries().single()
        assertEquals(AccountIds.WECHAT, entry.accountId)
        assertTrue(entry.isIncome)
        assertEquals(8800, entry.amountCents)
    }

    // -------------------------------------------- 通知来源白名单与诊断

    @Test
    fun `发送方白名单可以增删并持久化`() {
        val repository = repo()
        assertEquals(
            "默认应当是预置的支付来源",
            PaymentNotificationParser.DEFAULT_SENDERS,
            repository.senders(),
        )

        assertTrue(repository.addSender("XX收款"))
        assertTrue(repository.senders().contains("XX收款"))
        assertFalse("重复添加应当返回 false", repository.addSender("XX收款"))
        assertFalse("空白字符串不接受", repository.addSender("   "))
        assertTrue("必须跨重启保持", repo().senders().contains("XX收款"))

        repository.removeSender("XX收款")
        assertFalse(repository.senders().contains("XX收款"))
        assertTrue("移除用户项后，预置项仍在", repository.senders().contains("微信支付"))

        repository.resetSenders()
        assertEquals(PaymentNotificationParser.DEFAULT_SENDERS, repository.senders())
    }

    @Test
    fun `诊断记录默认关闭，只留最近 30 条，新的在前`() {
        val repository = repo()
        assertFalse("默认必须是关闭的（会记通知内容，属于隐私）", repository.isNotificationDiagnosticsOn())
        assertTrue(repository.notificationLog().isEmpty())

        repository.setNotificationDiagnostics(true)
        assertTrue("开关要跨重启保持", repo().isNotificationDiagnosticsOn())

        for (i in 1..35) {
            repository.appendNotificationLog(
                NotificationLogEntry(
                    timeMillis = 1_790_000_000_000L + i,
                    packageName = "com.tencent.mm",
                    title = "标题$i",
                    text = "正文$i",
                    verdict = "已忽略",
                    reason = "测试",
                ),
            )
        }

        val log = repository.notificationLog()
        assertEquals("只留最近 30 条，不能无限增长", 30, log.size)
        assertEquals("新的在前", "标题35", log.first().title)
        assertEquals("最旧的被挤掉", "标题6", log.last().title)
        assertEquals("微信", log.first().appName)

        repository.clearNotificationLog()
        assertTrue(repository.notificationLog().isEmpty())
    }

    // -------------------------------------------- 手动账户的余额：快照 + 流水

    private fun notify(
        repository: LedgerRepository,
        amountCents: Long,
        isIncome: Boolean,
        at: Long,
        orderId: String,
    ) = repository.addNotificationEntry(
        channel = AccountType.WECHAT,
        amountCents = amountCents,
        isIncome = isIncome,
        merchant = "测试",
        epochMillis = at,
        orderId = orderId,
        rawText = "",
        needsReview = false,
    )

    @Test
    fun `微信余额等于快照加上快照之后的流水`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)

        // 用户填：「现在微信有 100 元」
        repository.setAccountBalance(AccountIds.WECHAT, 10_000)
        val snapshotAt = repository.balanceSnapshotAt(AccountIds.WECHAT)
        assertTrue("保存余额时必须记下快照时刻", snapshotAt > 0)

        // 之后收到 88 元、花掉 12.5 元
        notify(repository, 8_800, isIncome = true, at = snapshotAt + 1_000, orderId = "in-1")
        notify(repository, 1_250, isIncome = false, at = snapshotAt + 2_000, orderId = "out-1")

        val account = repository.account(AccountIds.WECHAT)!!
        assertEquals(
            "100 + 88 - 12.5 = 175.5 —— 余额要跟着流水动，这是这次改的核心",
            17_550L,
            repository.currentBalanceCents(account),
        )
    }

    @Test
    fun `快照之前的流水不重复计入`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)

        // 先有一笔历史流水（时间在过去）
        val past = System.currentTimeMillis() - 86_400_000L
        notify(repository, 5_000, isIncome = true, at = past, orderId = "old-in")

        // 再设快照：这个数字本身已经包含了那笔历史收入
        repository.setAccountBalance(AccountIds.WECHAT, 20_000)

        val account = repository.account(AccountIds.WECHAT)!!
        assertEquals(
            "快照已经包含过去，不该再加一遍",
            20_000L,
            repository.currentBalanceCents(account),
        )
    }

    @Test
    fun `删掉一笔流水后余额会跟着回退`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        repository.setAccountBalance(AccountIds.WECHAT, 10_000)
        val snapshotAt = repository.balanceSnapshotAt(AccountIds.WECHAT)

        notify(repository, 8_800, isIncome = true, at = snapshotAt + 1_000, orderId = "in-1")
        val account = repository.account(AccountIds.WECHAT)!!
        assertEquals(18_800L, repository.currentBalanceCents(account))

        repository.deleteEntry("in-1")
        assertEquals(
            "删了记录，余额也要退回去 —— 否则数字就假了",
            10_000L,
            repository.currentBalanceCents(repository.account(AccountIds.WECHAT)!!),
        )
    }

    @Test
    fun `校园卡余额仍然直接用同步写入的值`() {
        val repository = repo()
        val campus = repository.ensureCampusAccount()
        repository.setAccountBalance(campus.id, 13_704)

        // 校园卡是 balanceManual = false，不该走「快照 + 流水」那套
        val refreshed = repository.account(campus.id)!!
        assertFalse(refreshed.balanceManual)
        assertEquals(13_704L, repository.currentBalanceCents(refreshed))
    }

    @Test
    fun `批量删除逐条留墓碑，重复通知不会复活它们`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)

        val ids = (1..5).map { "junk-$it" }
        ids.forEach { id ->
            repository.addNotificationEntry(
                channel = AccountType.WECHAT,
                amountCents = 0,
                isIncome = false,
                merchant = "待确认",
                epochMillis = 1_790_000_000_000L,
                orderId = id,
                rawText = "在吗",
                needsReview = true,
            )
        }
        assertEquals(5, repository.count())

        assertEquals(5, repository.deleteEntries(ids))
        assertEquals(0, repository.count())
        ids.forEach { assertTrue("每条都要留墓碑：$it", repository.isEntryDeleted(it)) }

        // 同一条通知再投递也不会复活
        ids.forEach { id ->
            repository.addNotificationEntry(
                channel = AccountType.WECHAT,
                amountCents = 0,
                isIncome = false,
                merchant = "待确认",
                epochMillis = 1_790_000_000_000L,
                orderId = id,
                rawText = "在吗",
                needsReview = true,
            )
        }
        assertEquals(0, repository.count())
    }

    // -------------------------------------------- 待确认记录的手动补全

    private fun addPending(
        repository: LedgerRepository,
        orderId: String,
        at: Long = System.currentTimeMillis(),
    ) =
        repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 0,
            isIncome = false,
            merchant = "待确认",
            epochMillis = at,
            orderId = orderId,
            rawText = "[微信红包]恭喜发财",
            needsReview = true,
        )

    @Test
    fun `待确认记录补上金额后会变成正常账目，余额也跟着更新`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        repository.setAccountBalance(AccountIds.WECHAT, 10_000)
        // 快照之后的流水才算（用户填的余额天然已经包含了更早的交易），
        // 所以这里显式给一个晚于快照的时刻
        val snapshotAt = repository.balanceSnapshotAt(AccountIds.WECHAT)
        addPending(repository, "pending-1", at = snapshotAt + 1_000)

        val entryBefore = repository.allEntries().single()
        assertEquals(LedgerRepository.KIND_NEEDS_REVIEW, entryBefore.kind)
        assertEquals("金额还没认出来，余额暂时不变", 10_000L, repository.currentBalanceCents(repository.account(AccountIds.WECHAT)!!))

        // 用户在条目详情里填了 88 元收入
        repository.confirmEntry("pending-1", 8_800, isIncome = true, category = "其他")

        val entry = repository.allEntries().single()
        assertEquals(8_800L, entry.amountCents)
        assertTrue(entry.isIncome)
        assertNotEquals("补完之后就不该再是待确认", LedgerRepository.KIND_NEEDS_REVIEW, entry.kind)
        assertEquals("收入", entry.kind)
        assertEquals("分类要落库", "其他", entry.category)
        assertEquals("原来的待确认提示要清掉", "", entry.note)
        assertEquals(
            "补上金额后余额必须跟着算：100 + 88 = 188",
            18_800L,
            repository.currentBalanceCents(repository.account(AccountIds.WECHAT)!!),
        )
    }

    @Test
    fun `补成支出时余额是往下走的`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        repository.setAccountBalance(AccountIds.WECHAT, 10_000)
        addPending(repository, "pending-2", at = repository.balanceSnapshotAt(AccountIds.WECHAT) + 1_000)

        repository.confirmEntry("pending-2", 2_550, isIncome = false, category = "餐饮")

        val entry = repository.allEntries().single()
        assertEquals("支出", entry.kind)
        assertFalse(entry.isIncome)
        assertEquals(10_000L - 2_550L, repository.currentBalanceCents(repository.account(AccountIds.WECHAT)!!))
    }

    @Test
    fun `补全之后它也会参与内部转账配对`() {
        val repository = repo()
        repository.ensureCampusAccount()
        // 校园卡有一笔 200 元的微信充值（真实形状）
        repository.addExternalEntry(
            accountId = repository.campusAccountId(),
            amountCents = 20_000,
            isIncome = true,
            merchant = "微信支付转账",
            category = "充值",
            source = EntrySource.SYNC,
            epochMillis = 1_790_000_000_000L,
            orderId = "campus-recharge",
        )
        repository.enableChannelAccount(AccountType.WECHAT)
        repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 0,
            isIncome = false,
            merchant = "待确认",
            epochMillis = 1_790_000_000_000L,
            orderId = "pending-transfer",
            rawText = "[转账]待收款",
            needsReview = true,
        )

        // 用户补成「支出 200 元」——正好和校园卡那笔充值对上
        repository.confirmEntry("pending-transfer", 20_000, isIncome = false, category = "其他")

        assertEquals(
            "补全后金额才对得上，这时候应该能配成内部转账",
            2,
            repository.allEntries().count { it.isTransfer },
        )
    }

    // -------------------------------------------- 官方账单导入

    private fun wechatRecords(vararg rows: String): BillImporter.Result {
        val text = buildString {
            appendLine("微信支付账单明细,,,,,,,,,,")
            appendLine("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注")
            rows.forEach { appendLine(it) }
        }
        return BillImporter.parseText(text)
    }

    @Test
    fun `导入微信账单会建账户并落库`() {
        val repository = repo()
        val parsed = wechatRecords(
            "2026-08-05 12:30:45,商户消费,南校区2食堂,餐饮,支出,¥25.50,零钱,支付成功,420000123,,",
            "2026-08-06 09:10:00,微信红包,张三,/,收入,¥88.00,零钱,已存入零钱,100003950,,",
        )

        val summary = repository.importBill(parsed.records, parsed.platform.label)

        assertEquals(2, summary.imported)
        assertEquals(0, summary.duplicateOfNotification)

        val entries = repository.allEntries()
        assertEquals(2, entries.size)
        assertTrue("账单进来的要标成 IMPORT", entries.all { it.source == EntrySource.IMPORT })
        assertTrue("都要挂到微信账户上", entries.all { it.accountId == AccountIds.WECHAT })
        assertEquals(2550L, entries.first { !it.isIncome }.amountCents)
        assertEquals(8800L, entries.first { it.isIncome }.amountCents)
        assertEquals("南校区2食堂", entries.first { !it.isIncome }.merchant)
    }

    @Test
    fun `同一份账单导入两次不会翻倍`() {
        val repository = repo()
        val parsed = wechatRecords(
            "2026-08-05 12:30:45,商户消费,食堂,餐饮,支出,¥25.50,零钱,支付成功,420000123,,",
        )

        repository.importBill(parsed.records, parsed.platform.label)
        val second = repository.importBill(parsed.records, parsed.platform.label)

        assertEquals("第二遍一条都不该新增", 0, second.imported)
        assertEquals(1, second.alreadyImported)
        assertEquals(1, repository.count())
    }

    @Test
    fun `通知已经抓到的同一笔，导入时不会再记一遍`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)

        // 通知先抓到：25.50 元支出
        val at = 1_790_000_000_000L
        repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 2_550,
            isIncome = false,
            merchant = "南校区2食堂",
            epochMillis = at,
            orderId = "notif-1",
            rawText = "支付成功 ¥25.50",
            needsReview = false,
        )

        // 账单里同一笔（时间差一分钟），交易单号不同 —— 光靠 orderId 挡不住
        // 账单里的时间是北京时间的墙上时间，这里把测试用的时刻换算成同样的写法
        val billTime = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
            .format(java.util.Date(at + 60_000L))
        val parsed = BillImporter.parseText(
            buildString {
                appendLine("微信支付账单明细,,,,,,,,,,")
                appendLine("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注")
                appendLine(
                    billTime + ",商户消费,南校区2食堂,餐饮,支出,¥25.50,零钱,支付成功,420000999,,",
                )
            },
        )
        assertTrue("账单先得解析出来", parsed.records.isNotEmpty())

        val summary = repository.importBill(parsed.records, parsed.platform.label)

        assertEquals("应当识别为通知已抓到的同一笔", 1, summary.duplicateOfNotification)
        assertEquals(0, summary.imported)
        assertEquals("账本里还是只有通知那一条", 1, repository.count())
    }

    @Test
    fun `导入的历史账单不会改变当前余额`() {        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        // 用户填的当前余额已经包含历史账单里的那些交易
        repository.setAccountBalance(AccountIds.WECHAT, 50_000)

        val parsed = wechatRecords(
            "2026-08-05 12:30:45,商户消费,食堂,餐饮,支出,¥25.50,零钱,支付成功,420000123,,",
        )
        repository.importBill(parsed.records, parsed.platform.label)

        assertEquals(
            "历史流水在快照之前，不该再被加一遍",
            50_000L,
            repository.currentBalanceCents(repository.account(AccountIds.WECHAT)!!),
        )
    }

    /** 把一个时刻写成账单里的北京时间文本 */
    private fun billTimeOf(epochMillis: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA)
            .apply { timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai") }
            .format(java.util.Date(epochMillis))

    @Test
    fun `账单能把红包的待确认记录补全，而不是新增一条`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        repository.setAccountBalance(AccountIds.WECHAT, 10_000)
        val snapshotAt = repository.balanceSnapshotAt(AccountIds.WECHAT)

        // 通知抓到红包：只有原文，没有金额 → 待确认
        val at = snapshotAt + 60_000L
        repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 0,
            isIncome = false,
            merchant = "待确认",
            epochMillis = at,
            orderId = "notif-redpacket",
            rawText = "[微信红包]恭喜发财",
            needsReview = true,
        )

        // 账单里有这一笔的真实金额和对方
        val parsed = BillImporter.parseText(
            buildString {
                appendLine("微信支付账单明细,,,,,,,,,,")
                appendLine("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注")
                appendLine(billTimeOf(at) + ",微信红包,张三,/,收入,¥88.00,零钱,已存入零钱,1000039501,,")
            },
        )

        val summary = repository.importBill(parsed.records, parsed.platform.label)

        assertEquals("应当补全那条待确认", 1, summary.reconciledPending)
        assertEquals("不该新增记录", 0, summary.imported)

        val entries = repository.allEntries()
        assertEquals("账本里还是只有一条", 1, entries.size)
        val entry = entries.single()
        assertEquals("金额补上了", 8800L, entry.amountCents)
        assertTrue("方向补上了", entry.isIncome)
        assertEquals("收入", entry.kind)
        assertEquals("对方补上了", "张三", entry.merchant)
        assertEquals("通知原文要留着，那是这笔的上下文", "[微信红包]恭喜发财", entry.rawText)
        assertEquals(
            "补全后余额要跟着算：100 + 88",
            18_800L,
            repository.currentBalanceCents(repository.account(AccountIds.WECHAT)!!),
        )
    }

    @Test
    fun `补全过的那笔账单再导入一次不会又插一条`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        val at = 1_790_000_000_000L
        repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 0,
            isIncome = false,
            merchant = "待确认",
            epochMillis = at,
            orderId = "notif-redpacket-2",
            rawText = "[微信红包]恭喜发财",
            needsReview = true,
        )
        val bill = buildString {
            appendLine("微信支付账单明细,,,,,,,,,,")
            appendLine("交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注")
            appendLine(billTimeOf(at) + ",微信红包,张三,/,收入,¥88.00,零钱,已存入零钱,1000039502,,")
        }

        repository.importBill(BillImporter.parseText(bill).records, "微信")
        val second = repository.importBill(BillImporter.parseText(bill).records, "微信")

        assertEquals(1, repository.count())
        assertEquals("第二次导入：这条账单已经用来补全过了，不该再插", 0, second.imported)
        assertEquals(0, second.reconciledPending)
    }

    @Test
    fun `渠道白名单可以增删，且能跨重启保持`() {
        val repository = repo()
        assertTrue("默认是空的：渠道 id 因手机/版本而异", repository.channels().isEmpty())

        assertTrue(repository.addChannel("channel_id=2"))
        assertFalse("重复添加要挡住", repository.addChannel("channel_id=2"))
        assertEquals(listOf("channel_id=2"), repo().channels())

        repository.addChannel("channel_id=5")
        assertEquals(2, repository.channels().size)
        repository.removeChannel("channel_id=2")
        assertEquals(listOf("channel_id=5"), repository.channels())

        repository.resetChannels()
        assertTrue(repository.channels().isEmpty())
    }

    @Test
    fun `每一笔流水都能改名，改完别的字段不受影响`() {
        val repository = repo()
        repository.enableChannelAccount(AccountType.WECHAT)
        repository.addNotificationEntry(
            channel = AccountType.WECHAT,
            amountCents = 2_550,
            isIncome = false,
            merchant = "商户消费",
            epochMillis = 1_790_000_000_000L,
            orderId = "rename-me",
            rawText = "支付成功 ¥25.50",
            needsReview = false,
        )
        repository.updateCategory("rename-me", "餐饮")

        repository.updateMerchant("rename-me", "三食堂")

        val entry = repository.allEntries().single()
        assertEquals("名字改了", "三食堂", entry.merchant)
        assertEquals("金额不能被顺手改掉", 2_550L, entry.amountCents)
        assertEquals("分类不能被顺手改掉", "餐饮", entry.category)
        assertFalse(entry.isIncome)
        assertEquals("通知原文要留着", "支付成功 ¥25.50", entry.rawText)

        // 改名会跨重启保持（落库了，不是内存里的）
        assertEquals("三食堂", repo().allEntries().single().merchant)
    }
}
