package com.morchid.ecardledger.ui

import android.app.Application
import android.content.Context
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.morchid.ecardledger.data.Account
import com.morchid.ecardledger.data.AccountType
import com.morchid.ecardledger.data.BillFileReader
import com.morchid.ecardledger.data.BillImporter
import com.morchid.ecardledger.data.ImageStore
import com.morchid.ecardledger.data.CardInfo
import com.morchid.ecardledger.data.LedgerEntry
import com.morchid.ecardledger.data.LedgerRepository
import com.morchid.ecardledger.data.NotificationLogEntry
import com.morchid.ecardledger.notification.NotificationParse
import com.morchid.ecardledger.notification.ParsedPayment
import com.morchid.ecardledger.notification.PaymentNotificationParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val loggedIn: Boolean = false,
    val loading: Boolean = false,
    val username: String = "",
    val card: CardInfo? = null,
    /** 卡信息只在内存里；App 重启后用它记录「最近一笔流水的余额」兜底展示 */
    val lastKnownBalanceCents: Long? = null,
    val entries: List<LedgerEntry> = emptyList(),
    val lastSync: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** 自动同步失败时的**非弹窗**提示：自动动作不该用模态框打扰用户 */
    val syncError: String? = null,
    /**
     * 现在不能同步的原因（频率限制/风控冻结），带「还要等多久」。
     * 非 null 时界面应当禁用同步按钮。
     */
    val syncBlockedReason: String? = null,
    /** 所有账户：校园卡 / 微信 / 支付宝…… */
    val accounts: List<Account> = emptyList(),
    /** null = 看全部账户 */
    val selectedAccountId: String? = null,
    /** 用户是否已授予通知使用权（决定能不能自动记账） */
    val notificationAccessGranted: Boolean = false,
    /** 监听服务此刻是否被系统绑着（同进程标记） */
    val listenerConnected: Boolean = false,
    /** 上次连上的时间，0 = 从没连上 */
    val listenerLastConnectedAt: Long = 0L,

    // ------------------------------------------------ 账单导入
    val importRunning: Boolean = false,
    /** 是加密 zip，等用户输密码 */
    val importNeedPassword: Boolean = false,
    /** 选中的文件名，显示给用户确认选对了 */
    val importFileName: String = "",
    val importMessage: String? = null,
    val importError: String? = null,

    // ------------------------------------------------ 本机账号（App 自己的账号）
    /** 本机账号是否已解锁。冷启动一律为 false —— 所以要输自己设的密码 */
    val unlocked: Boolean = false,
    /** 本机是否已经注册过账号。false 时界面走「注册」，true 时走「登录」 */
    val hasLocalAccount: Boolean = false,
    val localUsername: String = "",
    /** 用户是否明确表示过「先不绑定校园卡」（持久化，不再每次启动都拦） */
    val campusBindSkipped: Boolean = false,
    /** 当前接入的学校名（多校适配后由 SchoolRegistry 提供） */
    val schoolName: String = "",
    /** 当前选中的院校 id（绑定中心的选择器用） */
    val schoolId: String = "",
    /** 首次运行的权限引导是否走过了 */
    val permissionsOnboarded: Boolean = false,
    /** 是否已把本 App 加入电池优化白名单（不豁免的话后台容易被杀，漏记通知） */
    val batteryExempt: Boolean = false,
    /** 支付通知的发送方白名单（通知标题里出现即认为是支付通知） */
    val notifSenders: List<String> = emptyList(),
    /** 渠道白名单：比标题更硬的结构性信号 */
    val notifChannels: List<String> = emptyList(),
    /** 「通知诊断」是否打开 */
    val notifDiagnostics: Boolean = false,
    /** 最近的判定记录（新的在前）。默认关闭时不记录。 */
    val notifLog: List<NotificationLogEntry> = emptyList(),
    /**
     * 各账户**当前**余额（账户 id → 分）。
     *
     * 和 `Account.balanceCents` 的区别：微信/支付宝是「快照 + 快照之后的流水」，
     * 所以通知抓进来的收支会让余额跟着动。
     */
    val accountBalances: Map<String, Long> = emptyMap(),
    /** 只看「待确认」的记录（通知抓来的、金额没认出来的那些） */
    val reviewOnly: Boolean = false,

    // ------------------------------------------------ 资产页
    /** 今日收支 + 省钱计划额度 */
    val today: LedgerRepository.TodayTotals = LedgerRepository.TodayTotals(0, 0, 0),
    /** 顶部轮换图（`asset:` 或 `file:` 引用） */
    val heroImages: List<String> = emptyList(),
    /** 头像引用；空串表示没设 */
    val avatarRef: String = "",
    /** 今日流水（资产页用；按时间倒序） */
    val todayEntries: List<LedgerEntry> = emptyList(),
    /** 主题色枚举名；空串 = 默认水色 */
    val themeAccent: String = "",
    /** 存下来的额度（分）：即使用户选了「自由支出」也保留着，切回来还在 */
    val budgetSavedCents: Long = 0,
    /** 底色枚举名；空串 = 默认半透明 */
    val themeBase: String = "",
) {

    /**
     * 需要提醒用户去开自启动：**权限给了，但服务没绑上**。
     * 留 2 分钟宽限，避免刚开机、服务还没绑好时误报。
     */
    val listenerNeedsAttention: Boolean
        get() = notificationAccessGranted &&
            !listenerConnected &&
            (listenerLastConnectedAt == 0L ||
                System.currentTimeMillis() - listenerLastConnectedAt > 120_000L)

    /** 自检面板上显示的文字 */
    val listenerLastConnectedText: String
        get() = if (listenerLastConnectedAt == 0L) {
            "从未连上"
        } else {
            java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(listenerLastConnectedAt))
        }

    /** 当前筛选下要显示的记录 */
    val visibleEntries: List<LedgerEntry>
        get() {
            var list = entries
            if (reviewOnly) list = list.filter { it.kind == LedgerRepository.KIND_NEEDS_REVIEW }
            if (selectedAccountId != null) list = list.filter { it.accountId == selectedAccountId }
            return list
        }

    /** 待确认（通知没解析出金额）的条数，界面上要提示用户去补 */
    val needsReviewCount: Int
        get() = entries.count { it.kind == LedgerRepository.KIND_NEEDS_REVIEW }

    fun accountOf(id: String): Account? = accounts.firstOrNull { it.id == id }
}

class LedgerViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = LedgerRepository(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val saved = repo.savedCredentials()
        // 这里**只放"身份与开关"这类初始值**；所有从仓库读出来的数据交给 refresh() 统一补齐。
        //
        // 为什么不在这里手写全量：以前这里是一份完整构造，每加一个新字段都要记得改两处，
        // 漏过一次 —— 资产页的顶部轮换图在冷启动时永远是空的（真机验证时才发现）。
        // 现在 refresh() 是唯一的填充点，不会再漏。
        _state.value = UiState(
            loggedIn = saved != null,
            username = saved?.first.orEmpty(),
            lastKnownBalanceCents = repo.lastKnownBalanceCents(),
            // 记住登录：注册/解锁过就一直保持，只有主动点「锁定」才要重新输密码。
            // （用户明确反馈「每次进入都要重新输入密码 很麻烦」）
            unlocked = repo.hasLocalAccount() && repo.isSessionUnlocked(),
            hasLocalAccount = repo.hasLocalAccount(),
            localUsername = repo.localUsername().orEmpty(),
            campusBindSkipped = repo.isCampusBindSkipped(),
            schoolName = repo.schoolProfile().displayName,
            schoolId = repo.schoolId,
            permissionsOnboarded = repo.isPermissionsOnboarded(),
            batteryExempt = isBatteryExempt(),
            notifDiagnostics = repo.isNotificationDiagnosticsOn(),
        )
        // 记录、账户、余额、今日收支、轮换图、头像…… 全在这一步填上
        refresh()
        // 还没有本机账号、或还没解锁时不该自动同步 —— 用户还没真正进到 App 里。
        // 用 quiet 模式：失败只在内联位置提示，不弹对话框打断用户。
        if (_state.value.hasLocalAccount && saved != null &&
            LedgerRepository.shouldAutoSync(repo.lastSyncMillis(), System.currentTimeMillis())
        ) {
            syncNow(quiet = true)
        }
    }

    /** 首次登录：验证凭据 + 同步流水 */
    fun login(username: String, password: String, remember: Boolean) {
        if (username.isBlank() || password.isEmpty()) {
            _state.value = _state.value.copy(error = "请填写学号和密码")
            return
        }
        _state.value = _state.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            try {
                val outcome = repo.sync(username, password, manual = true)
                // 被频率限制拦住：这次**没发任何请求**，直接告诉用户等多久
                outcome.throttled?.let {
                    _state.value = _state.value.copy(loading = false, error = it)
                    return@launch
                }
                val saved = if (remember) repo.saveCredentials(username, password) else false
                _state.value = _state.value.copy(
                    loading = false,
                    loggedIn = true,
                    username = username,
                    card = outcome.card,
                    entries = repo.allEntries(),
                    lastSync = repo.lastSyncText(),
                    // 密钥库不可用时只提示一句，不影响已经成功的登录
                    message = (outcome.message ?: "") + if (remember && !saved) {
                        "\n\n注意：密码没能保存到本机（系统密钥库不可用），下次打开需要重新登录。"
                    } else {
                        ""
                    },
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * 同步流水。
     *
     * @param quiet 自动同步用：失败只写 [UiState.syncError]（内联提示），不弹对话框；
     *              成功也不弹「新增 N 条」的提示。手动点「同步」时传 false。
     */
    fun syncNow(quiet: Boolean = false) {
        val saved = repo.savedCredentials()
        if (saved == null) {
            _state.value = _state.value.copy(
                error = if (quiet) null else "凭据没保存，请重新登录",
                syncError = if (quiet) "凭据没保存，无法自动同步" else null,
            )
            return
        }
        _state.value = _state.value.copy(
            loading = true, error = null, message = null, syncError = null,
        )
        viewModelScope.launch {
            try {
                val outcome = repo.sync(saved.first, saved.second, manual = !quiet)
                val throttleNotice = outcome.throttled
                _state.value = _state.value.copy(
                    loading = false,
                    card = outcome.card,
                    entries = repo.allEntries(),
                    lastSync = repo.lastSyncText(),
                    lastKnownBalanceCents = repo.lastKnownBalanceCents(),
                    // 手动点同步被限流 → 用「提示」弹窗说清等多久；
                    // 自动同步被限流 → 只做内联提示，不打断用户
                    message = when {
                        quiet -> null
                        throttleNotice != null -> throttleNotice
                        else -> outcome.message
                    },
                    syncError = if (quiet) throttleNotice else null,
                    syncBlockedReason = repo.throttleDescription(manual = true),
                )
            } catch (e: Exception) {
                val text = e.message ?: e.javaClass.simpleName
                _state.value = _state.value.copy(
                    loading = false,
                    error = if (quiet) null else text,
                    syncError = if (quiet) text else null,
                    syncBlockedReason = repo.throttleDescription(manual = true),
                )
            }
        }
    }

    fun refresh() {
        _state.value = _state.value.copy(
            entries = repo.allEntries(),
            lastSync = repo.lastSyncText(),
            accounts = repo.accounts(),
            notificationAccessGranted = hasNotificationAccess(),
            listenerConnected = com.morchid.ecardledger.notification.ListenerState.connected,
            listenerLastConnectedAt = repo.listenerLastConnectedAt(),
            syncBlockedReason = repo.throttleDescription(manual = true),
            schoolName = repo.schoolProfile().displayName,
            schoolId = repo.schoolId,
            batteryExempt = isBatteryExempt(),
            notifSenders = repo.senders(),
            notifChannels = repo.channels(),
            notifLog = repo.notificationLog(),
            accountBalances = balancesOf(repo.accounts()),
            today = repo.todayTotals(),
            heroImages = repo.heroImages(),
            avatarRef = repo.avatarRef(),
            todayEntries = entriesToday(),
            themeAccent = repo.themeAccent(),
            themeBase = repo.themeBase(),
            budgetSavedCents = repo.dailyBudgetCents(),
        )
    }

    /** 今日流水（不含待确认与内部转账 —— 和资产页的今日收支口径一致） */
    private fun entriesToday(): List<LedgerEntry> {
        val start = repo.startOfTodayMillis()
        return repo.allEntries()
            .filter { it.epochMillis >= start && it.kind != LedgerRepository.KIND_NEEDS_REVIEW }
            .sortedByDescending { it.epochMillis }
    }

    /**
     * 一次取全部记录算各账户当前余额 —— 避免每个账户各查一次库。
     * 记录数不多（上限 2000），这样最省事也不会明显卡。
     */
    private fun balancesOf(accounts: List<Account>): Map<String, Long> {
        val entries = repo.allEntries()
        return accounts.associate { it.id to repo.currentBalanceCents(it, entries) }
    }

    /** 批量删除（一次性清掉一堆待确认的旧消息） */
    fun deleteEntries(orderIds: Collection<String>) {
        if (orderIds.isEmpty()) return
        val n = repo.deleteEntries(orderIds)
        refresh()
        _state.value = _state.value.copy(message = "已删除 $n 条记录")
    }

    /** 待确认的记录 id（供「全部清理」用） */
    fun pendingReviewIds(): List<String> =
        repo.allEntries()
            .filter { it.kind == LedgerRepository.KIND_NEEDS_REVIEW }
            .map { it.orderId }

    // ---------------------------------------------------------------- 通知来源与诊断

    fun setNotificationDiagnostics(on: Boolean) {
        repo.setNotificationDiagnostics(on)
        refresh()
        _state.value = _state.value.copy(
            message = if (on) {
                "已打开通知诊断。之后收到的微信/支付宝通知都会记下标题和判定结果" +
                    "（只存本机，方便排查为什么某条没记上）。"
            } else {
                "已关闭通知诊断。"
            },
        )
    }

    /** 把某个发送方加进白名单 —— 诊断页看到没认出来的标题时用 */
    fun addSender(sender: String) {
        val added = repo.addSender(sender)
        refresh()
        _state.value = _state.value.copy(
            message = if (added) {
                "已把「$sender」加入支付通知白名单。之后来自它的通知就会正常识别。"
            } else {
                "「$sender」已经在白名单里了。"
            },
        )
    }

    fun removeSender(sender: String) {
        repo.removeSender(sender)
        refresh()
        _state.value = _state.value.copy(message = "已从白名单移除「$sender」")
    }

    fun resetSenders() {
        repo.resetSenders()
        refresh()
        _state.value = _state.value.copy(message = "白名单已恢复默认")
    }

    /**
     * 把某个**通知渠道**加进白名单。
     *
     * 渠道比标题硬：微信支付的通知走独立渠道，和聊天消息不是一个渠道。
     * 诊断页里能看到每条通知的渠道 id。
     */
    fun addChannel(channelId: String) {        val added = repo.addChannel(channelId)
        refresh()
        _state.value = _state.value.copy(
            message = if (added) {
                "已把渠道「$channelId」加入白名单。这个渠道上的通知都会被当成支付通知。"
            } else {
                "渠道「$channelId」已经在白名单里了。"
            },
        )
    }

    fun removeChannel(channelId: String) {
        repo.removeChannel(channelId)
        refresh()
        _state.value = _state.value.copy(message = "已移除渠道白名单「$channelId」")
    }

    fun resetChannels() {
        repo.resetChannels()
        refresh()
        _state.value = _state.value.copy(message = "渠道白名单已清空")
    }

    // ---------------------------------------------------------------- 资产页

    /**
     * 设置每日平均花费上限（省钱计划）。
     * 传空或 0 表示取消计划。
     */
    fun setDailyBudget(yuanText: String) {
        val yuan = yuanText.trim().toDoubleOrNull()
        if (yuanText.isNotBlank() && (yuan == null || yuan < 0)) {
            _state.value = _state.value.copy(error = "每日额度要填一个正数")
            return
        }
        val cents = if (yuan == null) 0L else Math.round(yuan * 100)
        repo.setDailyBudget(cents)
        refresh()
        _state.value = _state.value.copy(
            message = if (cents == 0L) {
                "已取消省钱计划，不再提醒每日花费。"
            } else {
                "省钱计划已设定：每天平均不超过 ¥" + "%.2f".format(cents / 100.0) +
                    "。今天已花 ¥" + "%.2f".format(repo.todayTotals().spentCents / 100.0) + "。"
            },
        )
    }

    /** 切换花费方式：省钱计划 / 自由支出（**额度保留**，不清空） */
    fun setBudgetMode(enabled: Boolean) {
        repo.setBudgetEnabled(enabled)
        refresh()
    }

    /** 换底色（白/半透明/雾灰/深青） */
    fun setThemeBase(name: String) {
        repo.setThemeBase(name)
        refresh()
        _state.value = _state.value.copy(message = "底色已切换")
    }

    /** 换主题色（持久化；界面由 MainActivity 按这个值重建主题） */
    fun setThemeAccent(name: String) {
        repo.setThemeAccent(name)
        refresh()
        _state.value = _state.value.copy(message = "主题色已切换")
    }

    /** 把用户选的图加进顶部轮换；失败会给提示 */
    fun addHeroImage(uri: android.net.Uri) {
        viewModelScope.launch {
            val ref = withContext(Dispatchers.IO) {
                ImageStore.importFromUri(getApplication(), uri, "hero")
            }
            if (ref == null) {
                _state.value = _state.value.copy(error = "这张图读不出来，换一张试试")
                return@launch
            }
            repo.addHeroImage(ref)
            refresh()
            _state.value = _state.value.copy(message = "已加入顶部轮换图")
        }
    }

    fun removeHeroImage(ref: String) {
        repo.removeHeroImage(ref)
        refresh()
        _state.value = _state.value.copy(message = "已移除这张轮换图")
    }

    fun resetHeroImages() {
        repo.resetHeroImages()
        refresh()
        _state.value = _state.value.copy(message = "轮换图已恢复成预置的两张")
    }

    /** 上传头像 */
    fun setAvatar(uri: android.net.Uri) {
        viewModelScope.launch {
            val ref = withContext(Dispatchers.IO) {
                ImageStore.importFromUri(getApplication(), uri, "avatar")
            }
            if (ref == null) {
                _state.value = _state.value.copy(error = "这张图读不出来，换一张试试")
                return@launch
            }
            repo.setAvatar(ref)
            refresh()
            _state.value = _state.value.copy(message = "头像已更新")
        }
    }

    fun clearNotificationLog() {
        repo.clearNotificationLog()
        refresh()
    }

    /** 诊断记录已复制到剪贴板（用户会把它贴出来排查） */
    fun notifyDiagnosticsCopied() {
        _state.value = _state.value.copy(
            message = "诊断记录已复制。\n\n" +
                "如果某条真实收支被显示成「已忽略」，把它贴给我，我照真实格式补规则。",
        )
    }

    // ---------------------------------------------------------------- 账单导入

    /** 上次选的文件，输密码后要接着读它 */
    private var pendingImportUri: android.net.Uri? = null

    /**
     * 导入账单。第一次调用可以不传密码；如果是加密 zip，会回到「等密码」状态，
     * 用户输完密码再调一次即可。
     */
    fun importBill(uri: android.net.Uri, password: String? = null) {
        pendingImportUri = uri
        _state.value = _state.value.copy(
            importRunning = true,
            importNeedPassword = false,
            importMessage = null,
            importError = null,
        )
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { readAndImport(uri, password) }
                    .getOrElse { "读取失败：${it.message ?: it.javaClass.simpleName}" }
            }
            when (outcome) {
                null -> Unit // 成功，readAndImport 里已经把状态写好了
                NEED_PASSWORD -> _state.value = _state.value.copy(
                    importRunning = false,
                    importNeedPassword = true,
                )
                else -> _state.value = _state.value.copy(
                    importRunning = false,
                    importError = outcome,
                )
            }
        }
    }

    /** @return null 表示成功（状态已写好）；返回字符串表示错误信息 */
    private fun readAndImport(uri: android.net.Uri, password: String?): String? {
        val app = getApplication<Application>()
        val name = queryDisplayName(uri)
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return "读不到这个文件（可能是权限被回收了，重新选一次）"

        return when (val read = BillFileReader.read(name, bytes, password)) {
            is BillFileReader.Result.NeedPassword -> NEED_PASSWORD
            is BillFileReader.Result.Failed -> read.reason
            is BillFileReader.Result.Rows -> {
                val parsed = BillImporter.parseRows(read.rows)
                importParsed(parsed, fromZipNote = "XLSX：${read.innerName}", fileName = name)
            }
            is BillFileReader.Result.Text -> {
                val parsed = BillImporter.parseText(read.content)
                importParsed(
                    parsed = parsed,
                    fromZipNote = if (read.fromZip) "压缩包里的 ${read.innerName}" else "",
                    fileName = name,
                )
            }
        }
    }

    /** 解析结果入库并写好界面提示；返回 null 表示成功，否则是错误信息 */
    private fun importParsed(
        parsed: BillImporter.Result,
        fromZipNote: String,
        fileName: String,
    ): String? {
        if (parsed.records.isEmpty()) {
            val why = parsed.warnings.firstOrNull() ?: "没解析出任何记录"
            return "这份文件里没找到账单记录：$why"
        }
        val summary = repo.importBill(
            records = parsed.records,
            platformLabel = parsed.platform.label,
            skippedRows = parsed.skippedRows,
            warnings = parsed.warnings,
        )
        val text = buildString {
            append("${summary.platformLabel}账单导入完成\n\n")
            append("新增 ${summary.imported} 条")
            if (summary.reconciledPending > 0) {
                append("\n补全 ${summary.reconciledPending} 条待确认：红包/转账通知没有金额，账单里有")
            }
            if (summary.alreadyImported > 0) {
                append("\n其中 ${summary.alreadyImported} 条之前已经导入过")
            }
            if (summary.duplicateOfNotification > 0) {
                append("\n跳过 ${summary.duplicateOfNotification} 条：通知已经抓到过同一笔（同金额、同方向、时间相近）")
            }
            if (summary.skippedRows > 0) {
                append("\n忽略 ${summary.skippedRows} 行：不计收支、已关闭、或表尾汇总")
            }
            if (fromZipNote.isNotBlank()) append("\n\n来源：$fromZipNote")
            append("\n\n提示：账单里的历史记录不会改变现在的余额 —— 你填的余额快照已经包含它们了。")
        }
        refresh()
        _state.value = _state.value.copy(
            importRunning = false,
            importNeedPassword = false,
            importFileName = fileName,
            importMessage = text,
        )
        return null
    }

    /** 从 content:// 拿文件名（拿不到就返回空） */
    private fun queryDisplayName(uri: android.net.Uri): String {
        val app = getApplication<Application>()
        return runCatching {
            app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull().orEmpty()
    }

    fun dismissImportNotice() {
        _state.value = _state.value.copy(importMessage = null, importError = null)
    }

    private companion object {
        const val NEED_PASSWORD = "\u0000need-password"
    }

    // ---------------------------------------------------------------- 本机账号

    /**
     * 注册本机账号。
     *
     * PBKDF2 要跑 10 万轮（几十到一两百毫秒），所以放到后台线程，
     * 别让它卡住输入框的响应。
     */
    fun registerLocal(username: String, password: String, confirm: String) {
        _state.value = _state.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            val error = withContext(Dispatchers.Default) {
                runCatching { repo.registerLocalAccount(username, password, confirm) }
                    .getOrElse { it.message ?: "注册失败" }
            }
            _state.value = if (error != null) {
                _state.value.copy(loading = false, error = error)
            } else {
                repo.setSessionUnlocked(true)
                _state.value.copy(
                    loading = false,
                    unlocked = true,
                    hasLocalAccount = true,
                    localUsername = repo.localUsername().orEmpty(),
                    message = "账号已创建。\n\n接下来可以绑定校园卡或微信来自动记账；" +
                        "不绑定也可以，用「手动记一笔」先记着。",
                )
            }
        }
    }

    fun loginLocal(username: String, password: String) {
        _state.value = _state.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            val error = withContext(Dispatchers.Default) {
                runCatching { repo.verifyLocalLogin(username, password) }
                    .getOrElse { it.message ?: "登录失败" }
            }
            _state.value = if (error != null) {
                _state.value.copy(loading = false, error = error)
            } else {
                repo.setSessionUnlocked(true)
                _state.value.copy(
                    loading = false,
                    unlocked = true,
                    localUsername = repo.localUsername().orEmpty(),
                )
            }
        }
    }

    /**
     * 锁定 App：下次打开要重新输密码。
     *
     * 注意这是**用户主动**的动作。平时重启/从后台回来都不再要求密码
     * （已按用户反馈改成「记住登录」）。校园卡绑定不受影响。
     */
    fun lockLocal() {
        repo.setSessionUnlocked(false)
        _state.value = _state.value.copy(
            unlocked = false,
            error = null,
            message = null,
            syncError = null,
        )
    }

    /** 「先跳过，稍后再绑定」—— 记下来，别每次启动都拦 */
    fun skipCampusBinding() {
        repo.setCampusBindSkipped(true)
        _state.value = _state.value.copy(campusBindSkipped = true, error = null)
    }

    /** 从主页的「去绑定校园卡」进来 */
    /**
     * 选定院校后再去登录。
     *
     * 真机反馈：绑定中心不该"直接绑中南大学"，应该是用户先添加/选择高校。
     * 好在整条链路本来就是按 schoolId 走的（`CampusCardProviders.of(schoolId)`），
     * 所以这里只要把 schoolId 写进去，后面的登录、同步、账户 id 全都会跟着换。
     */
    fun selectSchool(id: String) {
        repo.schoolId = id
        refresh()
    }

    fun openCampusBinding() {
        repo.setCampusBindSkipped(false)
        _state.value = _state.value.copy(campusBindSkipped = false, error = null, message = null)
    }

    fun changeLocalPassword(oldPassword: String, newPassword: String, confirm: String) {
        _state.value = _state.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch {
            val error = withContext(Dispatchers.Default) {
                runCatching { repo.changeLocalPassword(oldPassword, newPassword, confirm) }
                    .getOrElse { it.message ?: "修改失败" }
            }
            _state.value = if (error != null) {
                _state.value.copy(loading = false, error = error)
            } else {
                _state.value.copy(loading = false, message = "密码已更新")
            }
        }
    }

    // ---------------------------------------------------------------- 账户

    /** 通知使用权是否已授予。App 自己开不了，只能引导用户去系统设置里勾。 */
    fun hasNotificationAccess(): Boolean = runCatching {
        val app = getApplication<Application>()
        NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)
    }.getOrDefault(false)

    /**
     * 本 App 是否已被允许忽略电池优化。
     * 不豁免的话，系统可能在后台把通知监听服务杀掉，导致漏记。
     */
    fun isBatteryExempt(): Boolean = runCatching {
        val app = getApplication<Application>()
        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(app.packageName)
    }.getOrDefault(false)

    /** 首次权限引导走完了（或用户明确跳过） */
    fun completePermissionsOnboarding() {
        repo.setPermissionsOnboarded(true)
        _state.value = _state.value.copy(permissionsOnboarded = true, error = null, message = null)
    }

    fun selectAccount(accountId: String?) {
        _state.value = _state.value.copy(selectedAccountId = accountId)
    }

    /** 切换「只看待确认」筛选 */
    fun toggleReviewOnly() {
        _state.value = _state.value.copy(reviewOnly = !_state.value.reviewOnly)
    }

    /** 一键清理所有待确认记录（通知误抓的旧消息） */
    fun deleteAllPendingReview() {
        val ids = pendingReviewIds()
        if (ids.isEmpty()) {
            _state.value = _state.value.copy(message = "没有待确认的记录")
            return
        }
        val n = repo.deleteEntries(ids)
        refresh()
        _state.value = _state.value.copy(message = "已清理 $n 条待确认记录")
    }

    fun setChannelEnabled(type: AccountType, enabled: Boolean) {
        runCatching { repo.setChannelEnabled(type, enabled) }
            .onFailure { _state.value = _state.value.copy(error = it.message ?: "开启失败") }
        refresh()
    }

    /** 微信/支付宝余额是手动维护的（拿不到自动余额） */
    fun setAccountBalance(accountId: String, balanceYuanText: String) {
        val yuan = balanceYuanText.trim().toDoubleOrNull()
        if (yuan == null || yuan < 0) {
            _state.value = _state.value.copy(error = "余额请填不小于 0 的数字")
            return
        }
        repo.setAccountBalance(accountId, Math.round(yuan * 100))
        refresh()
        _state.value = _state.value.copy(message = "余额已更新")
    }

    // ---------------------------------------------------------------- 内部转账

    /** 手动标记/取消"内部转账"（不计入收支） */
    fun setManualTransfer(orderId: String, on: Boolean) {
        repo.markAsTransfer(orderId, on)
        refresh()
    }

    fun unpairTransfer(groupId: String) {
        repo.unpairTransfer(groupId)
        refresh()
        _state.value = _state.value.copy(message = "已解除配对，这两笔会重新计入收支统计")
    }

    /** 导入账单/刚开启通知之后，把之前没配上的补一遍 */
    fun pairTransfersNow() {
        val groups = repo.rebuildTransferPairs()
        refresh()
        _state.value = _state.value.copy(
            message = if (groups > 0) "又配对出 $groups 笔内部转账" else "没有新的可配对记录",
        )
    }

    fun transferGroup(groupId: String): List<LedgerEntry> = repo.transferGroup(groupId)

    // ---------------------------------------------------------------- 通知自检

    /**
     * 模拟一条支付通知，验证「通知 → 解析 → 入库」链路是否通。
     * 真机上没法随便造一条微信通知，所以给用户一个自检入口。
     */
    fun simulateNotification(channel: AccountType, title: String, body: String) {
        val pkg = if (channel == AccountType.WECHAT) "com.tencent.mm" else "com.eg.android.AlipayGphone"
        val now = System.currentTimeMillis()

        when (val result = PaymentNotificationParser.parse(pkg, title, body, repo.senders())) {
            // 与支付无关——自检时要把这件事说清楚，不然用户以为「怎么没反应」
            NotificationParse.Ignore -> {
                _state.value = _state.value.copy(
                    message = "这条通知与支付无关，会被直接忽略、不记账。\n\n" +
                        "（聊天消息里有「50 元」这类内容也不会被记成账 —— 这是刻意的）",
                )
            }

            is NotificationParse.NeedsReview -> {
                val inserted = repo.addNotificationEntry(
                    channel = result.accountType,
                    amountCents = 0,
                    isIncome = false,
                    merchant = "待确认",
                    epochMillis = now,
                    orderId = "sim-$now",
                    rawText = result.rawText,
                    needsReview = true,
                    note = result.reason,
                )
                refresh()
                _state.value = _state.value.copy(
                    message = if (inserted) {
                        "自检成功：这条通知本身不带金额，已记成「待确认」。\n\n${result.reason}"
                    } else {
                        "这条已存在，没有重复记"
                    },
                )
            }

            is NotificationParse.Parsed -> {
                val payment = result.payment
                val inserted = repo.addNotificationEntry(
                    channel = payment.accountType,
                    amountCents = payment.amountCents,
                    isIncome = payment.isIncome,
                    merchant = payment.merchant,
                    epochMillis = now,
                    orderId = "sim-$now",
                    rawText = payment.rawText,
                    needsReview = payment.confidence == ParsedPayment.Confidence.LOW,
                    note = if (payment.confidence == ParsedPayment.Confidence.LOW) {
                        "方向未确认（自检）"
                    } else {
                        "自检生成"
                    },
                )
                refresh()
                _state.value = _state.value.copy(
                    message = if (inserted) {
                        "自检成功：${payment.merchant} ${payment.amountCents / 100.0} 元，" +
                            "已记入${if (payment.accountType == AccountType.WECHAT) "微信" else "支付宝"}"
                    } else {
                        "这条已存在，没有重复记"
                    },
                )
            }
        }
    }

    fun setCategory(orderId: String, category: String) {
        repo.updateCategory(orderId, category)
        refresh()
    }

    /**
     * 给一笔记录改名。
     *
     * 账单/通知里的名字经常没法看（「商户消费」「微信支付转账」「POS消费」），
     * 改成自己认得的说法（「三食堂」「充饭卡」）是刚需。
     */
    fun renameEntry(orderId: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) {
            _state.value = _state.value.copy(error = "名称不能为空")
            return
        }
        repo.updateMerchant(orderId, name)
        refresh()
        _state.value = _state.value.copy(message = "已改名为「$name」")
    }

    /**
     * 补全一条「待确认」记录：用户手动填金额 + 选支出/收入。
     *
     * 补完之后它就是一条正常账目，**账户余额会自动跟着更新**
     * （余额是「快照 + 快照之后的流水」算出来的，不需要另外改余额）。
     */
    fun confirmEntry(
        orderId: String,
        amountYuanText: String,
        isIncome: Boolean,
        category: String,
    ) {
        val yuan = amountYuanText.trim().toDoubleOrNull()
        if (yuan == null || yuan <= 0) {
            _state.value = _state.value.copy(error = "金额要填正数")
            return
        }
        repo.confirmEntry(
            orderId = orderId,
            amountCents = Math.round(yuan * 100),
            isIncome = isIncome,
            category = category,
        )
        refresh()
        _state.value = _state.value.copy(message = "已补全，余额已跟着更新")
    }

    fun addManualEntry(
        amountYuanText: String,
        isIncome: Boolean,
        merchant: String,
        category: String,
        note: String,
        accountId: String? = null,
    ) {
        val yuan = amountYuanText.toDoubleOrNull()
        if (yuan == null || yuan <= 0) {
            _state.value = _state.value.copy(error = "金额要填正数")
            return
        }
        // accountId 为空时用仓库默认（校园卡账户）
        val target = accountId ?: repo.campusAccountId()
        repo.addManualEntry(
            amountCents = Math.round(yuan * 100),
            isIncome = isIncome,
            merchant = merchant.trim(),
            category = category,
            note = note.trim(),
            accountId = target,
        )
        val accountName = repo.account(target)?.name ?: "账户"
        _state.value = _state.value.copy(message = "已记一笔（$accountName）")
        refresh()
    }

    fun deleteEntry(orderId: String) {
        repo.deleteEntry(orderId)
        refresh()
    }

    /**
     * 解除校园卡绑定：清掉学校凭据，**保留已经同步进来的账目**。
     *
     * ⚠️ 不能像老代码那样用 `UiState(entries = ...)` 重建整个状态 ——
     * 那会把 unlocked / hasLocalAccount 这些本机账号字段一起清掉，
     * 用户会被莫名其妙弹回本机登录页（这是加锁之后才出现的坑）。
     *
     * 另外**有意不重置频率限制**：退避和冻结冷却针对的是「学校账号」，
     * 解绑再绑不代表可以立刻重试一轮，那正是触发风控的动作。
     */
    fun unbindCampus() {
        repo.clearCredentials()
        _state.value = _state.value.copy(
            loggedIn = false,
            username = "",
            card = null,
            entries = repo.allEntries(),
            accounts = repo.accounts(),
            message = "已解除校园卡绑定。已经同步进来的记录都还在，随时可以重新绑定。",
        )
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null, error = null)
    }
}