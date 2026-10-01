package com.morchid.ecardledger.data

/**
 * 账单文件（微信/支付宝导出的 CSV）解析。
 *
 * ## 为什么这么写
 *
 * 微信账单前面有 17~18 行表头与账户元信息，支付宝最多 23 行 ——
 * 这两个数字来自 [double-entry-generator](https://github.com/deb-sig/double-entry-generator)
 * 的实现（`pkg/provider/wechat` 里写的是 `LineNum <= 17/18`，alipay 是 `<= 23`）。
 *
 * 但**故意不照抄这些魔法数字**，而是：
 *  1. 找到「表头行」——第一个同时含「交易时间」和「收/支」的行
 *  2. 按**列名**建索引（金额/金额(元)、交易单号/交易订单号 这类差异用别名兜住）
 *
 * 这样版本一变、列顺序一调整也不会崩，比写死行数和下标稳得多。
 *
 * ## 编码
 *
 * 微信导出的 CSV 是 UTF-8（带 BOM），支付宝历史上是 GBK。
 * 所以先按 UTF-8 解码，失败再回退 GBK。
 */
object BillImporter {

    /** 平台 */
    enum class Platform(val label: String) {
        WECHAT("微信"),
        ALIPAY("支付宝"),
        UNKNOWN("未知"),
    }

    enum class Direction { INCOME, EXPENSE, IGNORED }

    data class BillRecord(
        val platform: Platform,
        val timeText: String,
        val direction: Direction,
        val amountCents: Long,
        val kind: String,
        val peer: String,
        val item: String,
        val method: String,
        val status: String,
        /** 交易单号 / 交易订单号 —— 去重键 */
        val orderId: String,
        val note: String,
    )

    data class Result(
        val platform: Platform,
        val records: List<BillRecord>,
        /** 跳过的行数（表头、汇总、不计收支、格式不对…） */
        val skippedRows: Int,
        /** 表头行本身，排查问题时看它最有用 */
        val headerLine: String,
        val warnings: List<String>,
    ) {
        val expenseCents: Long get() = records.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }
        val incomeCents: Long get() = records.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents }
    }

    // ------------------------------------------------------------ 列名（含别名）

    private val TIME_KEYS = listOf("交易时间", "交易创建时间", "付款时间")
    private val DIRECTION_KEYS = listOf("收/支", "收支", "收/支类型")
    private val AMOUNT_KEYS = listOf("金额(元)", "金额（元）", "金额", "发生金额")
    private val KIND_KEYS = listOf("交易类型", "交易分类", "类型")
    private val PEER_KEYS = listOf("交易对方", "对方", "交易对象")
    private val ITEM_KEYS = listOf("商品", "商品说明", "商品名称")
    private val METHOD_KEYS = listOf("支付方式", "收/付款方式", "付款方式", "收付款方式")
    private val STATUS_KEYS = listOf("当前状态", "交易状态", "状态")
    private val ORDER_KEYS = listOf("交易单号", "交易订单号", "交易号", "订单号")
    private val NOTE_KEYS = listOf("备注", "说明")

    /** 「不计收支」的转账/还款之类：不是真实收支，不导入 */
    private const val IGNORED_DIRECTION = "不计收支"

    /** 这些状态说明交易没真正发生 */
    private val DEAD_STATUS = listOf("已关闭", "已取消", "交易关闭", "已失败", "支付失败")

    // ------------------------------------------------------------ 入口

    fun parse(bytes: ByteArray): Result = parseText(decode(bytes))

    /**
     * 这段文本看起来像不像一份账单（有没有表头）。
     *
     * 用途很关键：zip 密码不对时，底层解压库**不一定抛异常**，
     * 有时只是解出一堆乱码。所以解密之后要用「内容对不对」来兜底判断，
     * 而不是只依赖异常类型。
     */
    fun looksLikeBill(text: String): Boolean {
        if (text.isBlank()) return false
        return text.lineSequence().any { line ->
            TIME_KEYS.any { line.contains(it) } && DIRECTION_KEYS.any { line.contains(it) }
        }
    }

    fun parseText(text: String): Result =
        parseRows(text.split('\n').map { splitCsvLine(it.trimEnd('\r')) })

    /**
     * 按「表格」解析 —— CSV 和 XLSX 都走这里，行为因此天然一致。
     */
    fun parseRows(rows: List<List<String>>): Result {
        val warnings = ArrayList<String>()

        val headerIndex = rows.indexOfFirst { cells ->
            cells.any { cell -> TIME_KEYS.any { cell.contains(it) } } &&
                cells.any { cell -> DIRECTION_KEYS.any { cell.contains(it) } }
        }
        if (headerIndex < 0) {
            return Result(
                platform = Platform.UNKNOWN,
                records = emptyList(),
                skippedRows = rows.count { line -> line.any { it.isNotBlank() } },
                headerLine = "",
                warnings = listOf(
                    "没找到表头行（应当有一行同时包含「交易时间」和「收/支」）。" +
                        "请确认导出的是 CSV 或 XLSX 账单，而不是截图或 PDF。",
                ),
            )
        }

        val header = rows[headerIndex]
        val platform = detectPlatform(header)
        val index = ColumnIndex(header)

        if (index.amount < 0 || index.direction < 0) {
            warnings += "表头里没找到金额列或收支列，已按空处理。表头：${header.joinToString(",").take(120)}"
        }

        val records = ArrayList<BillRecord>()
        var skipped = 0

        for (i in (headerIndex + 1) until rows.size) {
            val cells = rows[i]
            // 空行（含文件末尾那个换行）不计入「跳过」——这个数字是用来说明
            // 「表格里有多少行被拒绝」的，掺进空行就没意义了
            if (cells.all { it.isBlank() }) continue

            val direction = index.cell(cells, index.direction)
            val amountText = index.cell(cells, index.amount)
            val amountCents = parseAmountCents(amountText)

            // 表尾的汇总行、说明行
            if (direction.isBlank() && amountCents == null) { skipped++; continue }
            if (direction.contains(IGNORED_DIRECTION)) { skipped++; continue }
            if (amountCents == null || amountCents == 0L) { skipped++; continue }

            val status = index.cell(cells, index.status)
            if (DEAD_STATUS.any { status.contains(it) }) { skipped++; continue }

            val dir = when {
                direction.contains("收入") || direction.contains("收") && !direction.contains("支") -> Direction.INCOME
                direction.contains("支出") || direction.contains("支") && !direction.contains("收") -> Direction.EXPENSE
                else -> {
                    warnings += "第 ${i + 1} 行的收支写法看不懂：「$direction」，已跳过"
                    skipped++
                    continue
                }
            }

            records += BillRecord(
                platform = platform,
                timeText = index.cell(cells, index.time),
                direction = dir,
                amountCents = amountCents,
                kind = index.cell(cells, index.kind),
                peer = index.cell(cells, index.peer),
                item = index.cell(cells, index.item),
                method = index.cell(cells, index.method),
                status = status,
                orderId = index.cell(cells, index.order),
                note = index.cell(cells, index.note),
            )
        }

        if (records.isEmpty()) {
            warnings += "表头找到了，但一条有效记录都没解析出来。可能列名和预期不一致。"
        }

        return Result(
            platform = platform,
            records = records,
            skippedRows = skipped,
            headerLine = header.joinToString(","),
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------ 内部

    /** 先 UTF-8，失败再 GBK（支付宝的老账单是 GBK） */
    internal fun decode(bytes: ByteArray): String {
        // 去掉 UTF-8 BOM
        val body = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            bytes.copyOfRange(3, bytes.size)
        } else {
            bytes
        }

        val strictUtf8 = Charsets.UTF_8.newDecoder()
        return runCatching { strictUtf8.decode(java.nio.ByteBuffer.wrap(body)).toString() }
            .getOrElse {
                runCatching { String(body, charset("GBK")) }
                    .getOrElse { String(body, Charsets.ISO_8859_1) }
            }
    }

    internal fun detectPlatform(headerCells: List<String>): Platform {
        val joined = headerCells.joinToString(",")
        return when {
            joined.contains("支付方式") || joined.contains("当前状态") -> Platform.WECHAT
            joined.contains("交易分类") || joined.contains("收/付款方式") -> Platform.ALIPAY
            else -> Platform.UNKNOWN
        }
    }

    /**
     * 按列名找下标。同名/别名都试一遍，找第一个命中的。
     * 找不到返回 -1（调用方按空字符串处理）。
     */
    internal class ColumnIndex(header: List<String>) {
        val time = find(header, TIME_KEYS)
        val direction = find(header, DIRECTION_KEYS)
        val amount = find(header, AMOUNT_KEYS)
        val kind = find(header, KIND_KEYS)
        val peer = find(header, PEER_KEYS)
        val item = find(header, ITEM_KEYS)
        val method = find(header, METHOD_KEYS)
        val status = find(header, STATUS_KEYS)
        val order = find(header, ORDER_KEYS)
        val note = find(header, NOTE_KEYS)

        fun cell(cells: List<String>, index: Int): String =
            if (index in cells.indices) cells[index].trim() else ""

        private fun find(header: List<String>, keys: List<String>): Int {
            for (key in keys) {
                val i = header.indexOfFirst { it.trim() == key.trim() }
                if (i >= 0) return i
            }
            // 精确匹配不到再退一步做包含匹配（表头里常带单位或空格）
            for (key in keys) {
                val i = header.indexOfFirst { it.contains(key) }
                if (i >= 0) return i
            }
            return -1
        }
    }

    /**
     * 按 CSV 规则切一行：支持双引号包裹、以及引号里的逗号和转义引号（""）。
     * 微信的「商品」列里就常带逗号。
     */
    internal fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"'); i++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                c == ',' && !inQuotes -> {
                    out += sb.toString(); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /**
     * 「¥1,234.56」「-25.00」「￥12」→ 分。
     *
     * ⚠️ **一律返回绝对值**：收支方向由「收/支」那一列说了算，金额里的负号只是同一件事的
     * 另一种写法。早期版本保留了负号，于是「-25.00 + 支出」会算出正数，
     * 在统计里就变成了收入 —— 真机反馈的「有的收入会变成支出」就是这么来的。
     */
    internal fun parseAmountCents(text: String): Long? {
        val cleaned = text
            .replace("¥", "").replace("￥", "")
            .replace(",", "").replace("，", "")
            .replace("元", "")
            .replace("+", "")
            .replace("-", "")
            .replace("−", "") // 全角减号
            .trim()
        if (cleaned.isEmpty()) return null
        val parts = cleaned.split('.')
        val yuan = parts[0].toLongOrNull() ?: return null
        val fraction = parts.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
        return Math.abs(yuan) * 100 + fraction
    }
}
