package com.morchid.ecardledger.data

import android.content.Context
import com.morchid.ecardledger.data.school.CampusCardProvider
import com.morchid.ecardledger.data.school.CampusCardProviders
import com.morchid.ecardledger.data.school.SchoolProfile
import com.morchid.ecardledger.data.school.SchoolRegistry
import com.morchid.ecardledger.data.school.SchoolTime
import com.morchid.ecardledger.notification.PaymentNotificationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 根据真实流水自动归类。
 * toAccount 是服务端最稳定的商户分组键（实测）：
 *   1000085 → 洗浴，3200010/3200015/3200002/3200003 → 餐饮，0 → 充值
 * 但学校可能调整，所以关键词匹配优先于账号匹配。
 */
object AutoCategory {

    /**
     * 分类树：一级 → 二级（空表示不再细分）。
     *
     * 二级分类以 `"一级/二级"` 的形式存进 `entries.category` —— **不动表结构**，
     * 统计时按一级聚合，需要时再看二级。
     */
    val TREE: Map<String, List<String>> = linkedMapOf(
        "餐饮" to listOf("食堂", "外卖", "饮品", "其他餐饮"),
        "购物" to listOf("衣物", "日用品", "数码", "美妆", "其他购物"),
        "交通" to listOf("公交地铁", "火车", "打车", "单车", "其他交通"),
        "洗浴" to emptyList(),
        "学习" to listOf("图书", "打印", "其他学习"),
        "医疗" to emptyList(),
        "娱乐" to emptyList(),
        "充值" to emptyList(),
        "其他" to emptyList(),
    )

    val DEFAULT_CATEGORIES: List<String> = TREE.keys.toList()

    /** 一级分类名（去掉二级） */
    fun topLevel(category: String): String = category.substringBefore('/')

    /** 二级分类名；没有二级就返回空串 */
    fun subLevel(category: String): String = category.substringAfter('/', "")

    /** 某个一级分类下的二级选项 */
    fun subsOf(top: String): List<String> = TREE[top].orEmpty()

    /**
     * 关键词规则。**顺序有意义**：越具体的越靠前。
     *
     * 真机反馈补充的两条：
     *  - 账单里出现「商户」这种泛称 → 归到**购物**（再按商品名细分到衣物/日用品…）
     *  - 「广铁 / 中铁」这类铁路字样 → 归到**交通**（细分到火车）
     */
    private val keywordRules: List<Pair<String, List<String>>> = listOf(
        // 充值先判：「微信支付转账」这种会和其他规则撞
        "充值" to listOf("充值", "微信支付转账", "补助", "退款"),

        // 交通细分
        "交通/火车" to listOf("广铁", "中铁", "铁路", "12306", "列车", "高铁", "火车", "动车"),
        "交通/打车" to listOf("滴滴", "出租车", "网约车", "T3出行", "曹操出行", "花小猪"),
        "交通/单车" to listOf("哈啰", "青桔", "共享单车", "美团单车"),
        "交通/公交地铁" to listOf("地铁", "公交", "校车", "轨道交通", "巴士", "乘车码"),
        "交通" to listOf("交通", "车费", "过路费", "加油"),

        // 购物细分（在泛称之前判）
        "购物/衣物" to listOf("服饰", "服装", "衣", "鞋", "裤", "帽", "优衣库", "耐克", "阿迪"),
        "购物/日用品" to listOf("日用", "洗护", "纸巾", "清洁", "牙膏", "沐浴", "超市", "便利店"),
        "购物/数码" to listOf("数码", "手机", "电脑", "耳机", "键盘", "显示器"),
        "购物/美妆" to listOf("美妆", "化妆", "护肤", "面膜"),
        // 「商户」这种泛称：账单里很常见，按购物处理
        "购物" to listOf(
            "商户", "商城", "旗舰店", "专卖店", "淘宝", "天猫", "京东", "拼多多",
            "商店", "购物", "百货", "门店",
        ),

        // 餐饮（食堂是"在哪里吃"，只有商户名里写了才敢下这个判断）
        "餐饮/食堂" to listOf("食堂", "档口", "白案", "红案"),
        "餐饮/饮品" to listOf("奶茶", "咖啡", "饮品", "果汁", "茶"),
        "餐饮" to listOf("餐厅", "小吃", "外卖", "餐", "米饭", "面食", "饮"),

        "洗浴" to listOf("洗浴", "浴室", "热水", "开水"),
        "学习" to listOf("图书", "打印", "复印", "教材", "阅览", "文具"),
        "医疗" to listOf("医院", "医务", "药", "门诊", "体检"),
        "娱乐" to listOf("电影", "游戏", "KTV", "健身", "演出", "门票"),
    )

    private val accountRules = mapOf(
        "1000085" to "洗浴",
        // 账号维度只知道"这是餐饮消费"，不该假装知道是食堂 —— 所以这里停在一级
        "3200010" to "餐饮",
        "3200015" to "餐饮",
        "3200002" to "餐饮",
        "3200003" to "餐饮",
    )

    fun guess(merchant: String, kind: String, payName: String, toAccount: String): String {
        if (kind.contains("充值") || payName.contains("转账")) return "充值"
        val haystack = "$merchant $payName"
        for ((category, keywords) in keywordRules) {
            if (keywords.any { haystack.contains(it) }) return category
        }
        accountRules[toAccount]?.let { return it }
        return "其他"
    }
}

data class SyncOutcome(
    val inserted: Int,
    val total: Int,
    val card: CardInfo? = null,
    val message: String? = null,
    /**
     * 被频率限制拦住时的说明。
     * **非 null 表示这次根本没发任何请求**（不是请求失败，而是压根没发），
     * 这是防止触发学校风控的关键。
     */
    val throttled: String? = null,
) {
    companion object {
        fun blocked(reason: String, card: CardInfo?): SyncOutcome =
            SyncOutcome(inserted = 0, total = 0, card = card, message = null, throttled = reason)
    }
}

class LedgerRepository(context: Context) {

    private val appContext = context.applicationContext
    private val db = LedgerDb(appContext)
    private val http = HttpClient()
    private val credentials = CredentialStore(appContext)
    private val throttle = SyncThrottle(db)

    private var token: String? = null
    private var cachedCard: CardInfo? = null

    // ---------------------------------------------------------------- 学校

    /** 当前接入的学校；存在 meta 里，换学校不用重装 App */
    var schoolId: String
        get() = db.getMeta(KEY_SCHOOL_ID) ?: SchoolRegistry.default.id
        set(value) {
            db.putMeta(KEY_SCHOOL_ID, value)
            // 换学校后旧 token 失效
            token = null
            cachedCard = null
        }

    fun schoolProfile(): SchoolProfile = SchoolRegistry.byId(schoolId) ?: SchoolRegistry.default

    /** 校园卡数据源：上层不关心是哪家厂商，只认这个接口 */
    private fun provider(): CampusCardProvider = CampusCardProviders.of(schoolId)

    // ---------------------------------------------------------------- 凭据

    fun savedCredentials(): Pair<String, String>? = credentials.load()

    /** @return 是否成功加密保存；Keystore 不可用时返回 false，不抛异常 */
    fun saveCredentials(username: String, password: String): Boolean =
        credentials.save(username, password)
    fun clearCredentials() {
        credentials.clear()
        // 同时丢掉 CAS 会话 cookie：否则「退出」后再次登录会复用旧会话、根本不校验密码。
        // 退出就该是真的退出。
        http.clearCookies()
        token = null
        cachedCard = null
    }

    fun lastSyncText(): String? = db.getMeta(KEY_LAST_SYNC)

    /** 上次同步时间的毫秒值；从未同步过或时间串解析不了时返回 null */
    fun lastSyncMillis(): Long? {
        val text = db.getMeta(KEY_LAST_SYNC) ?: return null
        return runCatching {
            SimpleDateFormat(SYNC_STAMP_PATTERN, Locale.CHINA).parse(text)?.time
        }.getOrNull()
    }

    fun updateCategory(orderId: String, category: String) = db.updateCategory(orderId, category)
    fun updateNote(orderId: String, note: String) = db.updateNote(orderId, note)

    /** 改一笔记录的名称（交易对方/商户） */
    fun updateMerchant(orderId: String, merchant: String) = db.updateMerchant(orderId, merchant)

    /**
     * 补全一条「待确认」记录（通知抓到了、但金额没认出来）。
     *
     * 补上金额和收支方向之后它就变成正常账目；因为账户余额是
     * 「快照 + 快照之后的流水」算出来的，**余额会自动跟着更新**，不用另外处理。
     */
    fun confirmEntry(
        orderId: String,
        amountCents: Long,
        isIncome: Boolean,
        category: String,
    ) {
        db.confirmEntry(
            orderId = orderId,
            amountCents = amountCents,
            isIncome = isIncome,
            kind = if (isIncome) "收入" else "支出",
            category = category.ifBlank { "其他" },
        )
        // 金额变了，之前可能配过的内部转账要重新评估
        tryPairTransfer(orderId)
    }

    /**
     * 删除任意一条记录（不再限于手动记的）。
     *
     * 起因是真机反馈：通知误抓了一堆记录，而界面上**只有手动记的才能删**，
     * 用户只能眼看着垃圾堆着。删除会留下墓碑，所以同步/重复通知不会把它插回来。
     */
    fun deleteEntry(orderId: String) = db.delete(orderId)

    /**
     * 批量删除（待确认的旧消息一次清掉）。
     * 每条都会留墓碑 —— 否则重复通知或者下一次同步会把它们又插回来。
     */
    fun deleteEntries(orderIds: Collection<String>): Int {
        var deleted = 0
        orderIds.forEach { id ->
            if (id.isNotBlank()) {
                db.delete(id)
                deleted++
            }
        }
        return deleted
    }

    /** 这条记录是否已被删除（墓碑），测试与排查用 */
    fun isEntryDeleted(orderId: String): Boolean = db.isDeleted(orderId)

    fun manualEntries(): List<LedgerEntry> = db.all().filter { it.manual }

    fun allEntries(): List<LedgerEntry> = db.all()

    fun entriesBetween(fromMillis: Long, toMillis: Long): List<LedgerEntry> =
        db.between(fromMillis, toMillis)

    fun count(): Int = db.count()

    /**
     * 释放数据库连接。
     * 常驻组件（通知监听服务）持有一个仓库实例，销毁时要关掉，
     * 否则每条通知都新建一个连接会泄漏。
     */
    fun close() {
        runCatching { db.close() }
    }

    // ---------------------------------------------------------------- 账单导入

    /** 一次导入的结果，直接显示给用户 */
    data class ImportSummary(
        val platformLabel: String,
        val imported: Int,
        /** 与已有的通知记录重复（同一笔钱已经抓到了） */
        val duplicateOfNotification: Int,
        /** 这份账单之前导入过（交易单号已存在） */
        val alreadyImported: Int,
        /** 账单把已有的「待确认」记录补全了（红包/转账通知没金额，账单里有） */
        val reconciledPending: Int,
        val skippedRows: Int,
        val warnings: List<String>,
    )

    /**
     * 导入一份账单（微信/支付宝导出的 CSV/XLSX）。
     *
     * 对账分三层，都是为了「不重复、也不漏」：
     *  1. **同一份账单重复导入** —— 用交易单号当 orderId，数据库唯一约束直接挡住
     *  2. **补全已有的「待确认」** —— 红包/转账的通知没有金额，账单里有。
     *     这时候应当把那条待确认**补全**（金额、方向、对方都填上），而不是新增一条 ——
     *     否则用户会同时看到「待确认 0 元」和「收入 88 元」两条，还得自己删一条。
     *  3. **通知已经完整抓到的** —— 同账户 + 同方向 + 同金额 + 时间相差 ≤2 小时 → 跳过，
     *     因为通知那条已经记过了（两边 orderId 形态不同，光靠 id 挡不住）
     *
     * 这样通知负责实时、账单负责补全，两边不会重复计账。
     */
    fun importBill(
        records: List<BillImporter.BillRecord>,
        platformLabel: String,
        skippedRows: Int = 0,
        warnings: List<String> = emptyList(),
    ): ImportSummary {
        if (records.isEmpty()) {
            return ImportSummary(platformLabel, 0, 0, 0, 0, skippedRows, warnings)
        }

        // 账单里的时间是北京时间的墙上时间，按学校时区解析（与校园卡流水同一套规则）
        val zone = schoolProfile().timeZoneId
        val existing = db.all()
        val notificationEntries = existing.filter { it.source == EntrySource.NOTIFICATION }

        var imported = 0
        var duplicateOfNotification = 0
        var alreadyImported = 0
        var reconciledPending = 0
        // 这一轮里已经被补全的待确认，别被第二条账单记录又匹配一次
        val consumedPending = HashSet<String>()

        for (record in records) {
            val accountId = channelAccountIdFor(record.platform) ?: continue
            val epoch = SchoolTime.parseWallClock(normalizeTime(record.timeText), zone)
                ?: System.currentTimeMillis()
            val isIncome = record.direction == BillImporter.Direction.INCOME

            // 二层：能不能补全一条金额为 0 的「待确认」（红包/转账就是这种）
            val pending = notificationEntries.firstOrNull { n ->
                n.kind == KIND_NEEDS_REVIEW &&
                    n.orderId !in consumedPending &&
                    n.accountId == accountId &&
                    n.amountCents == 0L &&
                    kotlin.math.abs(n.epochMillis - epoch) <= NOTIFICATION_MATCH_WINDOW_MS
            }
            if (pending != null) {
                db.confirmEntry(
                    orderId = pending.orderId,
                    amountCents = record.amountCents,
                    isIncome = isIncome,
                    kind = if (isIncome) "收入" else "支出",
                    category = AutoCategory.guess(
                        record.peer + record.item, record.kind, record.method, "",
                    ),
                    merchant = record.peer.ifBlank { record.item }.ifBlank { null },
                )
                // 账单这条也算处理过了，否则下次导入又会插一条重复的
                db.markOrderHandled(billOrderId(record))
                consumedPending += pending.orderId
                reconciledPending++
                continue
            }

            // 三层：和通知抓到的同一笔比对
            val looksLikeNotificationDuplicate = notificationEntries.any { n ->
                n.accountId == accountId &&
                    n.isIncome == isIncome &&
                    n.amountCents == record.amountCents &&
                    kotlin.math.abs(n.epochMillis - epoch) <= NOTIFICATION_MATCH_WINDOW_MS
            }
            if (looksLikeNotificationDuplicate) {
                duplicateOfNotification++
                continue
            }

            val orderId = billOrderId(record)
            val inserted = db.insert(
                LedgerEntry(
                    orderId = orderId,
                    timeText = normalizeTime(record.timeText),
                    epochMillis = epoch,
                    amountCents = record.amountCents,
                    isIncome = isIncome,
                    kind = record.kind.ifBlank { "账单导入" },
                    merchant = record.peer.ifBlank { record.item.ifBlank { "未知商户" } },
                    payName = record.kind.ifBlank { record.method },
                    balanceCents = 0,
                    toAccount = "",
                    category = AutoCategory.guess(
                        record.peer + record.item, record.kind, record.method, "",
                    ),
                    note = listOf(record.item, record.note)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(" · "),
                    manual = false,
                    accountId = accountId,
                    source = EntrySource.IMPORT,
                    rawText = listOf(record.timeText, record.peer, record.item, record.status)
                        .filter { it.isNotBlank() }
                        .joinToString(" | "),
                ),
            )
            if (inserted) imported++ else alreadyImported++
        }

        // 导进来的金额可能正好和校园卡充值是一笔，重新扫一遍配对
        rebuildTransferPairs()

        return ImportSummary(
            platformLabel = platformLabel,
            imported = imported,
            duplicateOfNotification = duplicateOfNotification,
            alreadyImported = alreadyImported,
            reconciledPending = reconciledPending,
            skippedRows = skippedRows,
            warnings = warnings,
        )
    }

    /** 账单里的时间各式各样，统一成 yyyy-MM-dd HH:mm:ss */
    private fun normalizeTime(raw: String): String {
        val t = raw.trim().replace('/', '-')
        return when {
            t.length >= 19 -> t.substring(0, 19)
            t.length == 16 -> "$t:00"
            else -> t
        }
    }

    private fun billOrderId(record: BillImporter.BillRecord): String {
        val idPart = record.orderId.ifBlank {
            // 没有交易单号就用内容拼一个稳定的键，仍然能挡住重复导入
            "${record.timeText}-${record.amountCents}-${record.peer}-${record.item}"
        }
        return "bill-${record.platform.name.lowercase()}-$idPart"
    }

    private fun channelAccountIdFor(platform: BillImporter.Platform): String? = when (platform) {
        BillImporter.Platform.WECHAT -> ensureChannelAccount(AccountType.WECHAT).id
        BillImporter.Platform.ALIPAY -> ensureChannelAccount(AccountType.ALIPAY).id
        BillImporter.Platform.UNKNOWN -> null
    }

    /** 账户已存在就保持原样（包括用户关掉的状态），不存在才创建 */
    private fun ensureChannelAccount(type: AccountType): Account {
        val id = if (type == AccountType.WECHAT) AccountIds.WECHAT else AccountIds.ALIPAY
        return db.account(id) ?: enableChannelAccount(type)
    }

    // ---------------------------------------------------------------- 账户

    fun accounts(): List<Account> = db.accounts()

    fun account(id: String): Account? = db.account(id)

    fun campusAccountId(school: String = schoolId): String = AccountIds.campus(school)

    /** 保证校园卡账户存在（首次启动或换学校时调用） */
    fun ensureCampusAccount(school: String = schoolId): Account {
        val id = AccountIds.campus(school)
        val profile = SchoolRegistry.byId(school) ?: SchoolRegistry.default
        db.ensureAccount(
            Account(
                id = id,
                name = profile.displayName + "校园卡",
                type = AccountType.CAMPUS_CARD,
                schoolId = profile.id,
                balanceManual = false,
                enabled = true,
                sortOrder = 0,
            ),
        )
        return db.account(id)!!
    }

    /**
     * 启用一个通知渠道账户（微信/支付宝）。
     * 余额由用户手动维护，所以这里只在账户不存在时创建，不覆盖已填过的余额。
     */
    fun enableChannelAccount(type: AccountType, balanceCents: Long? = null): Account {
        val id = when (type) {
            AccountType.WECHAT -> AccountIds.WECHAT
            AccountType.ALIPAY -> AccountIds.ALIPAY
            else -> throw IllegalArgumentException("不是通知渠道账户：$type")
        }
        val name = if (type == AccountType.WECHAT) "微信" else "支付宝"
        val existing = db.account(id)
        val account = Account(
            id = id,
            name = name,
            type = type,
            schoolId = null,
            balanceCents = balanceCents ?: existing?.balanceCents ?: 0L,
            balanceManual = true,
            enabled = true,
            sortOrder = if (type == AccountType.WECHAT) 1 else 2,
        )
        if (existing == null) db.ensureAccount(account) else db.updateAccount(account)
        return db.account(id)!!
    }

    /**
     * 微信/支付宝余额录入。
     *
     * **同时记录快照时刻**：用户填的是「现在这一刻的余额」，
     * 之后这个账户的每一笔收支都会自动加减（见 [currentBalanceCents]）。
     * 老实现只存了一个数字，所以流水进进出出、余额纹丝不动 —— 那是错的。
     */
    fun setAccountBalance(accountId: String, balanceCents: Long) {
        db.updateAccountBalance(accountId, balanceCents)
        db.putMeta(snapshotKey(accountId), throttle.now().toString())
    }

    /** 该账户余额快照的时刻（0 表示没设过） */
    fun balanceSnapshotAt(accountId: String): Long =
        db.getMeta(snapshotKey(accountId))?.toLongOrNull() ?: 0L

    /**
     * 账户**当前**余额。
     *
     * - 校园卡（`balanceManual = false`）：直接是同步写入的权威余额
     * - 微信/支付宝（`balanceManual = true`）：**快照 + 快照之后的流水**
     *
     * 这样通知抓进来的收支会自动改变余额，删掉一笔记录余额也会跟着回退。
     * 快照之前的流水不再重复计入（那些已经包含在快照里了）。
     */
    fun currentBalanceCents(account: Account, entries: List<LedgerEntry>? = null): Long {
        if (!account.balanceManual) return account.balanceCents
        val since = balanceSnapshotAt(account.id)
        val list = entries ?: db.byAccount(account.id)
        val delta = list
            .filter { it.accountId == account.id && it.epochMillis > since }
            .sumOf { it.signedCents }
        return account.balanceCents + delta
    }

    /** 快照之后这个账户发生了几笔（界面上用来说明余额为什么变了） */
    fun entriesSinceSnapshot(accountId: String): Int {
        val since = balanceSnapshotAt(accountId)
        return db.byAccount(accountId).count { it.epochMillis > since }
    }

    private fun snapshotKey(accountId: String) = "balance_snapshot_at:$accountId"

    fun accountEntries(accountId: String): List<LedgerEntry> = db.byAccount(accountId)

    /**
     * 校园卡最近一次同步到的余额（分）。App 重启后卡信息不在内存里，用它兜底显示。
     * v2 起余额存在账户表里；仍兼容 v1 的老数据（meta 或最近一笔带余额的流水）。
     */
    fun lastKnownBalanceCents(school: String = schoolId): Long? {
        db.account(AccountIds.campus(school))?.let { if (it.balanceCents > 0) return it.balanceCents }
        db.getMeta(KEY_CARD_BALANCE)?.toLongOrNull()?.let { return it }
        return db.all().firstOrNull { it.balanceCents > 0 }?.balanceCents
    }

    // ---------------------------------------------------------------- 内部转账

    /**
     * 新插入一笔后，尝试与另一个账户里方向相反的那笔配成「内部转账」。
     * 典型场景：微信支出 ⇄ 校园卡充值。配对后两条都标记为转账，不再计入收支统计。
     * @return 配对组的 id；没配上返回 null
     */
    fun tryPairTransfer(orderId: String): String? {
        val fresh = db.byOrderId(orderId) ?: return null
        if (fresh.isTransfer || fresh.transferGroupId != null) return null
        val window = TransferMatcher.DEFAULT_WINDOW_MILLIS
        val candidates = db.between(fresh.epochMillis - window, fresh.epochMillis + window)
        val counterpart = TransferMatcher.findCounterpart(fresh, candidates) ?: return null
        val groupId = groupIdFor(fresh.orderId, counterpart.orderId)
        db.markTransfer(listOf(fresh.orderId, counterpart.orderId), groupId)
        return groupId
    }

    /**
     * 全量重扫配对。导入账单、或首次开启通知监听之后调用，
     * 把当时因为「对手方还没记进来」而没配上的补上。
     * @return 新增的配对组数
     */
    fun rebuildTransferPairs(): Int {
        val entries = db.all(limit = MAX_SCAN).sortedBy { it.epochMillis }
        val used = HashSet<String>()
        var groups = 0
        for (entry in entries) {
            if (entry.isTransfer || entry.transferGroupId != null || used.contains(entry.orderId)) continue
            val window = TransferMatcher.DEFAULT_WINDOW_MILLIS
            val candidates = db.between(entry.epochMillis - window, entry.epochMillis + window)
                .filter { !used.contains(it.orderId) && it.transferGroupId == null }
            val counterpart = TransferMatcher.findCounterpart(entry, candidates) ?: continue
            db.markTransfer(
                listOf(entry.orderId, counterpart.orderId),
                groupIdFor(entry.orderId, counterpart.orderId),
            )
            used += entry.orderId
            used += counterpart.orderId
            groups++
        }
        return groups
    }

    /** 用户手工解除配对 */
    fun unpairTransfer(groupId: String) = db.clearTransferMarks(groupId)

    /**
     * 手动把一条记录标成（或取消）"内部转账"，即不计入收支。
     *
     * 为什么必须有手动这条路：自动配对要求**两条腿都看得到**。
     * 银行卡 → 微信零钱充值、提现到银行卡、信用卡还款这些，另一半只存在于银行短信里，
     * 本 App 永远看不到，所以自动配对不可能成功 —— 只能让用户自己说一声。
     *
     * 用 `manual-<orderId>` 当分组号，和自动配对的 `transfer-a+b` 区分开：
     * 取消时按分组号清，不会误伤自动配好的那一对。
     */
    /** 监听服务每次被系统绑上时记一笔，用于自检"权限在但服务没绑" */
    fun markListenerConnected() {
        db.putMeta(KEY_LISTENER_CONNECTED_AT, System.currentTimeMillis().toString())
    }

    /** 上次连上的时间戳；0 = 从没连上过 */
    fun listenerLastConnectedAt(): Long =
        db.getMeta(KEY_LISTENER_CONNECTED_AT)?.toLongOrNull() ?: 0L

    fun markAsTransfer(orderId: String, on: Boolean) {
        val groupId = "manual-$orderId"
        if (on) db.markTransfer(listOf(orderId), groupId) else db.clearTransferMarks(groupId)
    }

    fun transferGroup(groupId: String): List<LedgerEntry> = db.transferGroup(groupId)

    private fun groupIdFor(a: String, b: String): String = "transfer-$a+$b"

    // ---------------------------------------------------------------- 本机账号

    fun hasLocalAccount(): Boolean = db.localAccount() != null

    fun localUsername(): String? = db.localAccount()?.username

    /** 注册本机账号。@return 成功返回 null，失败返回可直接显示给用户的原因 */
    fun registerLocalAccount(username: String, password: String, confirm: String): String? {
        LocalAuth.validateRegistration(username, password, confirm)?.let { return it }
        if (hasLocalAccount()) return "本机已经有账号了，请直接登录"

        val salt = LocalAuth.newSalt()
        val now = System.currentTimeMillis()
        db.saveLocalAccount(
            LocalAccount(
                username = username.trim(),
                passwordHash = LocalAuth.hash(password, salt),
                salt = salt,
                iterations = LocalAuth.PBKDF2_ITERATIONS,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return null
    }

    /** 校验本机账号登录。@return 成功返回 null，失败返回原因 */
    fun verifyLocalLogin(username: String, password: String): String? {
        val account = db.localAccount() ?: return "本机还没有账号，请先注册"

        // 注意：用户名不对时**也要走一遍哈希计算**，并且返回与「密码错」完全相同的提示。
        // 否则可以从响应快慢 / 提示差异反推出「哪个用户名存在」。
        val nameMatches = account.username == username.trim()
        val passwordMatches = LocalAuth.verify(
            password, account.salt, account.iterations, account.passwordHash,
        )
        return if (nameMatches && passwordMatches) null else "用户名或密码不正确"
    }

    /** 修改本机账号密码。@return 成功返回 null，失败返回原因 */
    fun changeLocalPassword(oldPassword: String, newPassword: String, confirm: String): String? {
        val account = db.localAccount() ?: return "本机还没有账号"
        if (!LocalAuth.verify(oldPassword, account.salt, account.iterations, account.passwordHash)) {
            return "当前密码不正确"
        }
        LocalAuth.validatePassword(newPassword)?.let { return it }
        if (newPassword != confirm) return "两次输入的新密码不一致"

        // 改密码时顺便换盐：旧哈希即便泄漏也不能复用
        val salt = LocalAuth.newSalt()
        db.saveLocalAccount(
            account.copy(
                passwordHash = LocalAuth.hash(newPassword, salt),
                salt = salt,
                iterations = LocalAuth.PBKDF2_ITERATIONS,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return null
    }

    /**
     * 注销本机账号。只删账号本身，**账本数据不动**。
     * （真要清空数据得在系统设置里清除 App 数据，这是刻意分开的两件事。）
     */
    fun deleteLocalAccount() = db.deleteLocalAccount()

    /**
     * 用户是否明确表示过「先不绑定校园卡」。
     *
     * **必须持久化**：只存在内存里的话，每次冷启动都会把人再拦回绑定页 ——
     * 那就等于把「一进来只有校园卡登录」的老毛病换个形式又犯一遍。
     * 想绑的时候，主页上有「去绑定校园卡」。
     */
    fun isCampusBindSkipped(): Boolean = db.getMeta(KEY_BIND_SKIPPED) == "1"

    fun setCampusBindSkipped(skipped: Boolean) {
        db.putMeta(KEY_BIND_SKIPPED, if (skipped) "1" else "0")
    }

    /**
     * 登录会话是否保持。
     *
     * 用户的明确要求：「每次进入都要重新输密码很麻烦」。所以注册/解锁后把会话记下来，
     * 重启直接进；**只有用户主动点「锁定」才需要重新输密码**。
     *
     * ⚠️ 诚实说明：这只是**界面层**的锁。账本数据本身没有加密，
     *    能拿到设备数据目录的人可以直接读数据库、也可以把这个标记改回去。
     *    它防的是「手机被别人顺手拿到」，不是拿到设备数据的攻击者。
     *    要做到后者得用密码派生密钥加密数据库，那是另一个量级的改动。
     */
    fun isSessionUnlocked(): Boolean = db.getMeta(KEY_SESSION_UNLOCKED) == "1"

    fun setSessionUnlocked(unlocked: Boolean) {
        db.putMeta(KEY_SESSION_UNLOCKED, if (unlocked) "1" else "0")
    }

    /**
     * 首次运行的权限引导是否走过了。
     * 只问一次 —— 之后想改去「我的 → 绑定中心 → 通知权限、教程与自检」。
     */
    fun isPermissionsOnboarded(): Boolean = db.getMeta(KEY_PERMISSIONS_DONE) == "1"

    fun setPermissionsOnboarded(done: Boolean) {
        db.putMeta(KEY_PERMISSIONS_DONE, if (done) "1" else "0")
    }

    // ---------------------------------------------------------------- 通知来源白名单

    /**
     * 支付通知的发送方白名单（通知**标题**里出现即认为是支付通知）。
     *
     * 这是「先筛一道」的硬门槛：聊天消息的标题是联系人/群名，过不了这一关。
     * 用户可以增删 —— 因为没人能凭空知道每个微信版本把支付通知的标题写成什么。
     */
    fun senders(): List<String> {
        val stored = db.getMeta(KEY_NOTIF_SENDERS)
        if (stored.isNullOrBlank()) return PaymentNotificationParser.DEFAULT_SENDERS
        val list = stored.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return list.ifEmpty { PaymentNotificationParser.DEFAULT_SENDERS }
    }

    /** @return true 表示新增成功；false 表示已存在或为空 */
    fun addSender(sender: String): Boolean {
        val name = sender.trim()
        if (name.isEmpty()) return false
        val current = senders()
        if (current.any { it.equals(name, ignoreCase = true) }) return false
        db.putMeta(KEY_NOTIF_SENDERS, (current + name).joinToString("\n"))
        return true
    }

    fun removeSender(sender: String) {
        db.putMeta(KEY_NOTIF_SENDERS, senders().filterNot { it == sender }.joinToString("\n"))
    }

    fun resetSenders() {
        db.putMeta(KEY_NOTIF_SENDERS, "")
    }

    /**
     * 支付通知的**渠道白名单**（通知渠道 id 命中即认为是支付通知）。
     *
     * 比标题白名单更硬的结构性信号：微信支付走的是它自己的通知渠道，
     * 和聊天消息根本不是一个渠道 —— 标题可以被版本改得面目全非，渠道 id 稳定得多。
     *
     * 默认是空的：渠道 id 因手机/版本而异，只能让用户从诊断里看到后自己加。
     */
    fun channels(): List<String> {
        val stored = db.getMeta(KEY_NOTIF_CHANNELS) ?: return emptyList()
        return stored.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** @return true 表示新增成功；false 表示已存在或为空 */
    fun addChannel(channelId: String): Boolean {
        val id = channelId.trim()
        if (id.isEmpty()) return false
        val current = channels()
        if (current.any { it.equals(id, ignoreCase = true) }) return false
        db.putMeta(KEY_NOTIF_CHANNELS, (current + id).joinToString("\n"))
        return true
    }

    fun removeChannel(channelId: String) {
        db.putMeta(KEY_NOTIF_CHANNELS, channels().filterNot { it == channelId }.joinToString("\n"))
    }

    fun resetChannels() {
        db.putMeta(KEY_NOTIF_CHANNELS, "")
    }

    // ---------------------------------------------------------------- 主题

    /** 主题色（[com.morchid.ecardledger.ui.AkAccent] 的枚举名）；空串 = 用默认的水色 */
    fun themeAccent(): String = db.getMeta(KEY_THEME_ACCENT).orEmpty()

    fun setThemeAccent(name: String) {
        db.putMeta(KEY_THEME_ACCENT, name)
    }

    /** 底色（[com.morchid.ecardledger.ui.AkBase] 的枚举名）；空串 = 用默认的半透明 */
    fun themeBase(): String = db.getMeta(KEY_THEME_BASE).orEmpty()

    fun setThemeBase(name: String) {
        db.putMeta(KEY_THEME_BASE, name)
    }

    // ---------------------------------------------------------------- 资产页：今日 / 省钱计划 / 图片

    /**
     * 今日收支与省钱计划。
     *
     * 口径：**不含内部转账**（左右口袋，不是真花钱），也**不含待确认**（金额还没认出来）。
     */
    data class TodayTotals(
        val incomeCents: Long,
        val spentCents: Long,
        val budgetCents: Long,
    ) {
        val hasBudget: Boolean get() = budgetCents > 0

        /** 超出每日额度的部分；没设额度或没超就是 0 */
        val overBudgetCents: Long
            get() = if (hasBudget) (spentCents - budgetCents).coerceAtLeast(0L) else 0L

        val isOverBudget: Boolean get() = overBudgetCents > 0L
    }

    /** 今日 00:00（按学校时区）的毫秒时间戳 —— 「今日」是本地日历概念，不能用 UTC 算 */
    fun startOfTodayMillis(): Long {
        val zone = java.util.TimeZone.getTimeZone(schoolProfile().timeZoneId)
        val cal = java.util.Calendar.getInstance(zone)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    fun todayTotals(): TodayTotals {
        val start = startOfTodayMillis()
        val today = db.all().filter {
            it.epochMillis >= start &&
                it.kind != KIND_NEEDS_REVIEW &&
                !it.isTransfer &&
                it.accountId in enabledAccountIds()
        }
        return TodayTotals(
            incomeCents = today.filter { it.isIncome }.sumOf { it.amountCents },
            spentCents = today.filter { !it.isIncome }.sumOf { it.amountCents },
            budgetCents = effectiveBudgetCents(),
        )
    }

    private fun enabledAccountIds(): Set<String> =
        db.accounts().filter { it.enabled }.map { it.id }.toSet()

    /** 存下来的额度（分）。**不受开关影响** —— 「自由支出」只是关掉它，不清空数字 */
    fun dailyBudgetCents(): Long =
        db.getMeta(KEY_DAILY_BUDGET)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

    /** 省钱计划是否启用（false = 自由支出，但额度还留着） */
    fun isBudgetEnabled(): Boolean = (db.getMeta(KEY_BUDGET_ENABLED) ?: "1") == "1"

    /** 生效中的额度：关掉省钱计划就是 0（资产页据此决定显不显示那张卡） */
    fun effectiveBudgetCents(): Long = if (isBudgetEnabled()) dailyBudgetCents() else 0L

    /** 保存额度并启用省钱计划 */
    fun setDailyBudget(cents: Long) {
        val value = cents.coerceAtLeast(0L)
        db.putMeta(KEY_DAILY_BUDGET, value.toString())
        db.putMeta(KEY_BUDGET_ENABLED, if (value > 0L) "1" else "0")
    }

    /**
     * 只切开关，**不动额度**。
     * 真机反馈：「点了自由支出再切回省钱计划，原来存的额度就没了」——
     * 因为老实现是直接把额度写成 0。现在额度留着，切回去还在。
     */
    fun setBudgetEnabled(enabled: Boolean) {
        db.putMeta(KEY_BUDGET_ENABLED, if (enabled) "1" else "0")
    }

    // ---------------------------------------------------------------- 资产页顶部轮换图

    /**
     * 轮换图的引用列表。
     *
     * 每一项是 `asset:文件名`（打包进 App 的预置图）或 `file:绝对路径`（用户自己传的）。
     * 默认给两张预置风景图；用户加过之后就只用自己的列表（可以删到只剩自己的）。
     */
    fun heroImages(): List<String> {
        val stored = db.getMeta(KEY_HERO_IMAGES)
        if (stored.isNullOrBlank()) return DEFAULT_HERO_IMAGES
        val list = stored.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        return list.ifEmpty { DEFAULT_HERO_IMAGES }
    }

    /** @return true 表示新增成功；false 表示已存在或无效 */
    fun addHeroImage(ref: String): Boolean {
        val value = ref.trim()
        if (value.isEmpty()) return false
        val current = heroImages()
        if (current.any { it == value }) return false
        db.putMeta(KEY_HERO_IMAGES, (current + value).joinToString("\n"))
        return true
    }

    fun removeHeroImage(ref: String) {
        db.putMeta(KEY_HERO_IMAGES, heroImages().filterNot { it == ref }.joinToString("\n"))
    }

    fun resetHeroImages() {
        db.putMeta(KEY_HERO_IMAGES, "")
    }

    // ---------------------------------------------------------------- 头像

    /** 头像引用：`file:绝对路径`；空串表示没设 */
    fun avatarRef(): String = db.getMeta(KEY_AVATAR)?.trim().orEmpty()

    fun setAvatar(ref: String) {
        db.putMeta(KEY_AVATAR, ref.trim())
    }

    // ---------------------------------------------------------------- 通知诊断

    /**
     * 通知诊断开关，**默认关闭**。
     * 打开后会把收到的通知标题/正文（截断）记在本机，便于排查「为什么这条没记上」。
     */
    fun isNotificationDiagnosticsOn(): Boolean = db.getMeta(KEY_NOTIF_DIAGNOSTICS) == "1"

    fun setNotificationDiagnostics(on: Boolean) {
        db.putMeta(KEY_NOTIF_DIAGNOSTICS, if (on) "1" else "0")
    }

    /** 最近的判定记录（新的在前） */
    fun notificationLog(): List<NotificationLogEntry> =
        db.getMeta(KEY_NOTIF_LOG)
            ?.split('\n')
            ?.mapNotNull { line -> parseLogLine(line) }
            ?.reversed()
            ?: emptyList()

    fun appendNotificationLog(entry: NotificationLogEntry) {
        val line = listOf(
            entry.timeMillis.toString(),
            entry.packageName,
            entry.title.take(60),
            entry.text.take(80),
            entry.verdict,
            entry.reason.take(60),
            entry.channelId.take(60),
        ).joinToString(LOG_SEPARATOR)
        val existing = db.getMeta(KEY_NOTIF_LOG)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        // 只留最近的若干条：诊断是临时工具，不该无限增长
        val kept = (existing + line).takeLast(MAX_LOG_ENTRIES)
        db.putMeta(KEY_NOTIF_LOG, kept.joinToString("\n"))
    }

    fun clearNotificationLog() {
        db.putMeta(KEY_NOTIF_LOG, "")
    }

    private fun parseLogLine(line: String): NotificationLogEntry? {
        val parts = line.split(LOG_SEPARATOR)
        if (parts.size < 6) return null
        return runCatching {
            NotificationLogEntry(
                timeMillis = parts[0].toLong(),
                packageName = parts[1],
                title = parts[2],
                text = parts[3],
                verdict = parts[4],
                reason = parts[5],
                // 老记录没有这一列（第 7 列是后加的），拿不到就当空
                channelId = parts.getOrNull(6).orEmpty(),
            )
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 频率限制

    /**
     * 现在能不能同步。给界面用：可以据此禁用按钮、或显示「还要等 X 分钟」。
     * 真正的拦截在 [sync] 里，这里只是让界面提前知道。
     */
    fun syncThrottleDecision(manual: Boolean = true): SyncThrottlePolicy.Decision =
        throttle.syncDecision(manual)

    fun loginThrottleDecision(): SyncThrottlePolicy.Decision = throttle.loginDecision()

    /** 被限制时给出人话说明；没被限制返回 null */
    fun throttleDescription(manual: Boolean = true): String? {
        val now = throttle.now()

        // 1. 风控冻结：学校给了明确时间，优先说这个
        throttle.blockedUntil()?.let { until ->
            if (now < until) return SyncThrottlePolicy.describeFreeze(now, until)
        }

        // 2. 同步层面的间隔（手动 60 秒 / 自动 6 小时）
        throttle.syncDecision(manual).takeIf { !it.allowed }?.let {
            return SyncThrottlePolicy.describeDecision(now, it)
        }

        // 3. 登录层面的限制（最小间隔 / 失败退避 / 每日上限）。
        //    同步必然要登录，所以这里必须一并算 ——
        //    否则界面会显示「可以同步」，点下去才被拦住（设备验证时踩到过）。
        throttle.loginDecision().takeIf { !it.allowed }?.let {
            return SyncThrottlePolicy.describeDecision(now, it)
        }

        return null
    }

    /** 解绑校园卡时清掉限制状态 */
    fun resetThrottle() = throttle.reset()

    // ---------------------------------------------------------------- 同步

    /** 登录并抓取流水；必须在 IO 线程调用（本函数自己切到 IO） */
    suspend fun sync(
        username: String,
        password: String,
        daysBack: Int = 365,
        manual: Boolean = true,
    ): SyncOutcome =
        withContext(Dispatchers.IO) {
            val profile = schoolProfile()
            val now = throttle.now()

            // ⚠️ 频率限制必须在这里判断 —— 也就是**在发出任何网络请求之前**。
            //    先请求、失败了再判断「是不是太频繁」是没用的：那次访问已经被服务端算进去了，
            //    而它正是触发学校风控（账号频繁访问 → 冻结）的原因。
            val syncDecision = throttle.syncDecision(manual = manual)
            if (!syncDecision.allowed) {
                return@withContext SyncOutcome.blocked(
                    SyncThrottlePolicy.describeDecision(now, syncDecision),
                    cachedCard,
                )
            }

            val loginDecision = throttle.loginDecision()
            if (!loginDecision.allowed) {
                val until = throttle.blockedUntil()
                val text = if (until != null && now < until) {
                    // 学校明确给了冻结时间，就说清是什么情况、还要等多久
                    SyncThrottlePolicy.describeFreeze(now, until)
                } else {
                    SyncThrottlePolicy.describeDecision(now, loginDecision)
                }
                return@withContext SyncOutcome.blocked(text, cachedCard)
            }

            val account = ensureCampusAccount()
            val api = provider()

            // 先记账再发请求：失败的尝试也要计入，否则限制形同虚设
            throttle.noteLoginAttempt()
            val freshToken = try {
                api.login(http, username, password)
            } catch (e: AuthException) {
                // 只把「认证层面」的失败计入退避：这类才是真正会触发风控的尝试。
                // 断网、超时之类不算，否则网络不稳的用户会被自己的退避规则锁住。
                throttle.noteLoginFailure(e.message)
                throw e
            }
            throttle.noteLoginSuccess()
            token = freshToken
            val card = api.queryCard(http, freshToken)
            cachedCard = card
            // v2 起余额归账户表管；meta 仍然写一份，兼容老版本的读取路径
            db.updateAccountBalance(account.id, card.balanceCents)
            db.putMeta(KEY_CARD_BALANCE, card.balanceCents.toString())

            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val calendar = Calendar.getInstance()
            val to = fmt.format(calendar.time)
            calendar.add(Calendar.DAY_OF_YEAR, -daysBack)
            val from = fmt.format(calendar.time)

            val records = ArrayList<TurnoverRecord>()
            var page = 1
            var reportedTotal = 0
            while (page <= MAX_PAGES) {
                val result = api.queryTurnover(http, freshToken, from, to, page, PAGE_SIZE)
                reportedTotal = result.total
                records += result.records
                if (result.records.size < PAGE_SIZE) break
                page++
            }

            var inserted = 0
            val insertedOrders = ArrayList<String>()
            // 服务端给的是学校本地时间的字符串，必须按学校时区解析，
            // 不能按设备时区（否则设备时区一变，时间戳和配对窗口就全错位）
            val schoolZone = SchoolTime.zoneOf(profile.timeZoneId)
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .apply { timeZone = schoolZone }
            for (record in records) {
                val category = AutoCategory.guess(
                    record.merchant, record.kind, record.payName, record.toAccount,
                )
                val entry = LedgerEntry(
                    orderId = record.orderId.ifEmpty { "${record.timeText}-${record.amountCents}" },
                    timeText = record.timeText,
                    epochMillis = parseTime(record.timeText, timeFormat),
                    amountCents = record.amountCents,
                    isIncome = record.isIncome,
                    kind = record.kind,
                    merchant = record.merchant,
                    payName = record.payName,
                    balanceCents = record.balanceCents,
                    toAccount = record.toAccount,
                    category = category,
                    note = "",
                    manual = false,
                    accountId = account.id,
                    source = EntrySource.SYNC,
                )
                if (db.insert(entry)) {
                    inserted++
                    insertedOrders += entry.orderId
                }
            }

            // 充值很可能与微信/支付宝那边构成内部转账，插完后统一尝试配对
            var paired = 0
            for (orderId in insertedOrders) {
                if (tryPairTransfer(orderId) != null) paired++
            }

            val stamp = SimpleDateFormat(SYNC_STAMP_PATTERN, Locale.CHINA).format(Date())
            db.putMeta(KEY_LAST_SYNC, stamp)
            throttle.noteSyncSuccess()

            SyncOutcome(
                inserted = inserted,
                total = if (reportedTotal > 0) reportedTotal else records.size,
                card = card,
                message = "抓取 $from ~ $to，服务端共 $reportedTotal 条，新增 $inserted 条" +
                    if (paired > 0) "（其中 $paired 笔识别为内部转账）" else "",
            )
        }

    /** 新增一笔手动账目 */
    fun addManualEntry(
        amountCents: Long,
        isIncome: Boolean,
        merchant: String,
        category: String,
        note: String,
        epochMillis: Long = System.currentTimeMillis(),
        accountId: String = campusAccountId(),
    ) {
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .format(Date(epochMillis))
        val orderId = "manual-$epochMillis-${(0..9999).random()}"
        db.insert(
            LedgerEntry(
                orderId = orderId,
                timeText = timeText,
                epochMillis = epochMillis,
                amountCents = amountCents,
                isIncome = isIncome,
                kind = if (isIncome) "手动收入" else "手动支出",
                merchant = merchant.ifBlank { "手动记账" },
                payName = "手动记账",
                balanceCents = 0,
                toAccount = "",
                category = category,
                note = note,
                manual = true,
                accountId = accountId,
                source = EntrySource.MANUAL,
            ),
        )
        tryPairTransfer(orderId)
    }

    // ------------------------------------------------------ 外部记录（通知 / 账单导入）

    /**
     * 写入一笔来自外部渠道的记录。
     *
     * 通知监听和将来的「官方账单导入」**共用这一个入口** —— 两者只有 [source] 不同，
     * 落库、去重、内部转账配对的逻辑完全一致。这就是为账单导入预留的扩展点。
     *
     * @param orderId 去重键，由调用方生成稳定值（通知用内容指纹，导入用账单流水号）
     * @return true 表示是新记录，false 表示重复被忽略
     */
    fun addExternalEntry(
        accountId: String,
        amountCents: Long,
        isIncome: Boolean,
        merchant: String,
        category: String,
        source: EntrySource,
        epochMillis: Long,
        orderId: String,
        rawText: String = "",
        note: String = "",
        kindOverride: String? = null,
    ): Boolean {
        val timeText = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
            .format(Date(epochMillis))
        val inserted = db.insert(
            LedgerEntry(
                orderId = orderId,
                timeText = timeText,
                epochMillis = epochMillis,
                amountCents = amountCents,
                isIncome = isIncome,
                kind = kindOverride ?: if (isIncome) "收入" else "支出",
                merchant = merchant.ifBlank { "未知商户" },
                payName = merchant,
                balanceCents = 0,
                toAccount = "",
                category = category,
                note = note,
                manual = false,
                accountId = accountId,
                source = source,
                rawText = rawText,
            ),
        )
        // 很可能正是「微信/支付宝 → 校园卡充值」的一半，插完立刻尝试配对
        if (inserted) tryPairTransfer(orderId)
        return inserted
    }

    // ------------------------------------------------------ 通知监听

    fun accountIdForChannel(type: AccountType): String? = when (type) {
        AccountType.WECHAT -> AccountIds.WECHAT
        AccountType.ALIPAY -> AccountIds.ALIPAY
        else -> null
    }

    /** 用户没开启这个渠道就不记账 */
    fun isChannelEnabled(type: AccountType): Boolean {
        val id = accountIdForChannel(type) ?: return false
        return db.account(id)?.enabled == true
    }

    fun setChannelEnabled(type: AccountType, enabled: Boolean): Account {
        val account = enableChannelAccount(type)
        val updated = account.copy(enabled = enabled)
        db.updateAccount(updated)
        return updated
    }

    /**
     * 通知监听落库。
     *
     * **识别不出来的通知不丢弃**：以金额 0、类型「待确认」存下来，原文放进 rawText，
     * 用户可以在列表里看到并手动补全。宁可多一条待办，也不要静默丢账。
     */
    fun addNotificationEntry(
        channel: AccountType,
        amountCents: Long,
        isIncome: Boolean,
        merchant: String,
        epochMillis: Long,
        orderId: String,
        rawText: String,
        needsReview: Boolean,
        note: String = "",
    ): Boolean {
        val accountId = accountIdForChannel(channel) ?: return false
        // 双保险：服务层已经判断过一次，这里再挡一道，
        // 保证「渠道没开启就不会有任何调用路径能写进账本」
        if (!isChannelEnabled(channel)) return false
        val category = if (needsReview) {
            "其他"
        } else {
            AutoCategory.guess(merchant, "", merchant, "")
        }
        return addExternalEntry(
            accountId = accountId,
            amountCents = amountCents,
            isIncome = isIncome,
            merchant = merchant,
            category = category,
            source = EntrySource.NOTIFICATION,
            epochMillis = epochMillis,
            orderId = orderId,
            rawText = rawText,
            note = note,
            kindOverride = if (needsReview) KIND_NEEDS_REVIEW else null,
        )
    }

    private fun parseTime(text: String, format: SimpleDateFormat): Long =
        runCatching { format.parse(text)?.time ?: 0L }.getOrDefault(0L)

    companion object {
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 30
        private const val KEY_LAST_SYNC = "last_sync"
        private const val KEY_CARD_BALANCE = "card_balance_cents"
        private const val SYNC_STAMP_PATTERN = "yyyy-MM-dd HH:mm"

        /** 全量重扫配对时最多看多少条 */
        private const val MAX_SCAN = 5000

        /** 账单导入时判断「这笔通知已经抓到了」的时间窗口 */
        private const val NOTIFICATION_MATCH_WINDOW_MS = 2 * 60 * 60 * 1000L

        private const val KEY_SCHOOL_ID = "school_id"
        private const val KEY_BIND_SKIPPED = "campus_bind_skipped"
        private const val KEY_SESSION_UNLOCKED = "local_session_unlocked"
        private const val KEY_PERMISSIONS_DONE = "permissions_onboarded"
        private const val KEY_NOTIF_SENDERS = "notif_senders"
        private const val KEY_NOTIF_CHANNELS = "notif_channels"
        private const val KEY_DAILY_BUDGET = "daily_budget_cents"
        private const val KEY_BUDGET_ENABLED = "daily_budget_enabled"
        private const val KEY_LISTENER_CONNECTED_AT = "listener_connected_at"
        private const val KEY_HERO_IMAGES = "hero_images"
        private const val KEY_THEME_ACCENT = "theme_accent"
        private const val KEY_THEME_BASE = "theme_base"
        private const val KEY_AVATAR = "avatar_ref"

        /** 预置的两张风景图（打包在 assets 里） */
        val DEFAULT_HERO_IMAGES = listOf("asset:aiwan1.jpg", "asset:yuelu1.jpg")
        private const val KEY_NOTIF_DIAGNOSTICS = "notif_diagnostics"
        private const val KEY_NOTIF_LOG = "notif_diag_log"

        /** 诊断记录里的字段分隔符：用一个正常文本里不会出现的控制字符 */
        private const val LOG_SEPARATOR = "\u0001"
        private const val MAX_LOG_ENTRIES = 30

        /** 通知里没能识别出金额/方向时，用这个 kind 标出来，界面上会显示「待确认」 */
        const val KIND_NEEDS_REVIEW = "待确认"

        /** 自动同步间隔：超过这么多小时没同步过，进入 App 就自动拉一次 */
        const val AUTO_SYNC_INTERVAL_HOURS = 6

        /**
         * 进入 App 时是否需要自动同步。纯函数，便于单测。
         *  - 从未同步过 → true
         *  - 上次同步时间在未来（设备时钟回拨）→ true
         *  - 距上次同步达到阈值 → true
         */
        fun shouldAutoSync(
            lastSyncMillis: Long?,
            nowMillis: Long,
            thresholdHours: Int = AUTO_SYNC_INTERVAL_HOURS,
        ): Boolean {
            if (lastSyncMillis == null) return true
            val elapsed = nowMillis - lastSyncMillis
            return elapsed < 0 || elapsed >= thresholdHours * 3_600_000L
        }
    }
}
