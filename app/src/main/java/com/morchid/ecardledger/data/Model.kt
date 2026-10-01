package com.morchid.ecardledger.data

/** 账户类型。校园卡按学校区分，微信/支付宝是手动维护余额的账户。 */
enum class AccountType { CAMPUS_CARD, WECHAT, ALIPAY, OTHER }

/** 一条记录的来源，决定它是怎么进到账本里的 */
enum class EntrySource {
    /** 从学校网站同步来的校园卡流水 */
    SYNC,

    /** 监听微信/支付宝通知自动记的 */
    NOTIFICATION,

    /** 从官方账单文件导入的（预留） */
    IMPORT,

    /** 用户手动补记 */
    MANUAL,
}

/**
 * 资金账户。一个 App 里可以有多个账户：某校的校园卡、微信、支付宝……
 * 余额有两种维护方式：校园卡由同步写入，微信/支付宝由用户手动输入。
 */
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    /** 仅校园卡账户有：绑定到哪所学校的适配器（见 SchoolRegistry） */
    val schoolId: String? = null,
    val balanceCents: Long = 0,
    /** true = 用户手动维护余额；false = 由同步写入（校园卡） */
    val balanceManual: Boolean = true,
    /** 是否在账户切换器里显示 */
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

object AccountIds {
    const val WECHAT = "wechat"
    const val ALIPAY = "alipay"

    /** 校园卡账户 id 与学校绑定，方便将来支持多所学校 */
    fun campus(schoolId: String): String = "campus:$schoolId"

    /**
     * v1 数据库里的记录没有 account_id，迁移时统一归到这个账户。
     * 这是历史遗留常量，只用于一次性迁移。
     */
    const val MIGRATION_DEFAULT_CAMPUS = "campus:csu"
}

/**
 * 一条账目。
 *
 * 不变式：
 *  - 金额一律以「分」存整数，避免浮点误差
 *  - [signedCents] 表示对账户余额的影响（收入为正、支出为负）
 *  - [statCents] 表示对**收支统计**的贡献：内部转账（如微信→校园卡充值）为 0，
 *    否则同一笔钱会在两个账户里各算一次，把总收支双双虚增
 */
data class LedgerEntry(
    val id: Long = 0,
    /** 去重键：校园卡用服务端流水号、通知用内容指纹、手动用时间戳 */
    val orderId: String,
    val timeText: String,
    val epochMillis: Long,
    val amountCents: Long,
    val isIncome: Boolean,
    val kind: String,
    val merchant: String,
    val payName: String,
    /** 交易后余额（校园卡同步时有；通知/手动记的没有，为 0） */
    val balanceCents: Long,
    /** 校园卡服务端的商户分组键，其他来源为空 */
    val toAccount: String,
    val category: String,
    val note: String,
    val manual: Boolean,

    // ---------------- v2 新增 ----------------
    val accountId: String = AccountIds.MIGRATION_DEFAULT_CAMPUS,
    val source: EntrySource = EntrySource.SYNC,
    /** 内部转账：计入账户余额，但**不计入**收支统计 */
    val isTransfer: Boolean = false,
    /** 同一笔转账的两条记录共享这个 id */
    val transferGroupId: String? = null,
    /** 原始信息（通知原文等），便于排查误识别 */
    val rawText: String = "",
) {
    /** 对账户余额的影响：收入为正、支出为负 */
    val signedCents: Long get() = if (isIncome) amountCents else -amountCents

    /** 对收支统计的贡献：转账不计入 */
    val statCents: Long get() = if (isTransfer) 0L else signedCents

    val amountYuan: Double get() = amountCents / 100.0
}

/**
 * 本机账号：只属于这个 App 的账号，**不联网、不上传、不需要服务器**。 *
 * 它有两个作用：
 *  1. 给账本上一把锁 —— 打开 App 输的是你自己设的密码，不是学校的密码
 *  2. 作为「绑定项」（校园卡 / 微信 / 支付宝）的归属主体
 *
 * 密码用 PBKDF2-HMAC-SHA256 加盐哈希后存本地，**明文永不落盘**（见 `LocalAuth`）。
 * 代价是：纯本地账号**没有「找回密码」** —— 忘了只能清数据重来。
 * 这是刻意的：留了后门，锁就白上了。
 */
data class LocalAccount(
    val username: String,
    /** PBKDF2 派生密钥，Base64 */
    val passwordHash: String,
    /** 随机盐，Base64 */
    val salt: String,
    val iterations: Int,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 通知诊断记录：一条通知的判定结果。
 *
 * 为什么需要它：**没人能凭空知道每个微信版本把支付通知的标题写成什么**。
 * 与其猜，不如把真实通知的标题记下来给用户看 —— 看到没认出来的，
 * 一键把这个发送方加进白名单即可。
 *
 * ⚠️ 只在用户主动打开「通知诊断」时才记录，且只存在本机；默认关闭。
 */
data class NotificationLogEntry(
    val timeMillis: Long,
    val packageName: String,
    /** 通知标题（发送方）—— 判断白名单主要看它 */
    val title: String,
    /** 通知正文（截断保存） */
    val text: String,
    /** 判定结果：已记账 / 待确认 / 已忽略 */
    val verdict: String,
    val reason: String,
    /**
     * 通知渠道 id。
     *
     * 比标题更硬的结构性信号：微信支付走的是它自己的渠道，和聊天消息不是一个渠道。
     * 记录下来一是排查方便，二是将来可以做「渠道白名单」。
     */
    val channelId: String = "",
) {
    /** 通知来源的应用名（简写） */
    val appName: String
        get() = when (packageName) {
            "com.tencent.mm" -> "微信"
            "com.eg.android.AlipayGphone" -> "支付宝"
            else -> packageName
        }
}
