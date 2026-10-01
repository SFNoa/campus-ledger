package com.morchid.ecardledger.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite 存储。
 *
 * v1 → v2 的变化（对应「多账户 + 内部转账」扩展）：
 *  - 新增 `accounts` 表：校园卡/微信/支付宝各自独立记账，余额分开维护
 *  - `entries` 增加 account_id / source / is_transfer / transfer_group_id / raw_text
 *  - 老数据（没有 account_id 的）在迁移时统一归到校园卡账户，并把原来的余额 meta 搬过来
 */
class LedgerDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE entries (
                id                INTEGER PRIMARY KEY AUTOINCREMENT,
                order_id          TEXT    NOT NULL UNIQUE,
                time_text         TEXT    NOT NULL,
                epoch_millis      INTEGER NOT NULL,
                amount_cents      INTEGER NOT NULL,
                is_income         INTEGER NOT NULL,
                kind              TEXT    NOT NULL DEFAULT '',
                merchant          TEXT    NOT NULL DEFAULT '',
                pay_name          TEXT    NOT NULL DEFAULT '',
                balance_cents     INTEGER NOT NULL DEFAULT 0,
                to_account        TEXT    NOT NULL DEFAULT '',
                category          TEXT    NOT NULL DEFAULT '',
                note              TEXT    NOT NULL DEFAULT '',
                manual            INTEGER NOT NULL DEFAULT 0,
                account_id        TEXT    NOT NULL DEFAULT '',
                source            TEXT    NOT NULL DEFAULT 'SYNC',
                is_transfer       INTEGER NOT NULL DEFAULT 0,
                transfer_group_id TEXT,
                raw_text          TEXT    NOT NULL DEFAULT ''
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_entries_epoch ON entries(epoch_millis DESC)")
        db.execSQL("CREATE INDEX idx_entries_account ON entries(account_id, epoch_millis DESC)")
        createAccountsTable(db)
        createLocalAccountTable(db)
        createDeletedOrdersTable(db)
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    /**
     * 用户删除过的记录 id（墓碑表）。
     * 只存 id，不存内容 —— 删了就是删了。
     */
    private fun createDeletedOrdersTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS deleted_orders (
                order_id   TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            createDeletedOrdersTable(db)
        }
        if (oldVersion < 3) {
            createLocalAccountTable(db)
        }
        if (oldVersion < 2) {
            createAccountsTable(db)
            db.execSQL("ALTER TABLE entries ADD COLUMN account_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE entries ADD COLUMN source TEXT NOT NULL DEFAULT 'SYNC'")
            db.execSQL("ALTER TABLE entries ADD COLUMN is_transfer INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE entries ADD COLUMN transfer_group_id TEXT")
            db.execSQL("ALTER TABLE entries ADD COLUMN raw_text TEXT NOT NULL DEFAULT ''")
            // 老数据全部属于校园卡账户
            db.execSQL(
                "UPDATE entries SET account_id = ? WHERE account_id = ''",
                arrayOf(AccountIds.MIGRATION_DEFAULT_CAMPUS),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS idx_entries_account ON entries(account_id, epoch_millis DESC)",
            )
            // 把原来存在 meta 里的校园卡余额搬进账户表（余额不再散落在 meta 里）
            val legacyBalance = runCatching {
                db.rawQuery("SELECT v FROM meta WHERE k = 'card_balance_cents'", null).use {
                    if (it.moveToFirst()) it.getString(0)?.toLongOrNull() else null
                }
            }.getOrNull()
            db.execSQL(
                """
                INSERT OR IGNORE INTO accounts
                    (id, name, type, school_id, balance_cents, balance_manual, enabled, sort_order)
                VALUES (?, ?, ?, ?, ?, 0, 1, 0)
                """.trimIndent(),
                arrayOf(
                    AccountIds.MIGRATION_DEFAULT_CAMPUS,
                    "校园卡",
                    AccountType.CAMPUS_CARD.name,
                    "csu",
                    (legacyBalance ?: 0L).toString(),
                ),
            )
        }
    }

    /**
     * 本机账号表。**单行表**（`CHECK (id = 1)`），因为一个 App 只需要一个本机账号。
     * 用表而不是 meta，是为了让「本机账号」成为一等概念，并且带上创建/修改时间。
     */
    private fun createLocalAccountTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS local_account (
                id            INTEGER PRIMARY KEY CHECK (id = 1),
                username      TEXT    NOT NULL,
                password_hash TEXT    NOT NULL,
                salt          TEXT    NOT NULL,
                iterations    INTEGER NOT NULL,
                created_at    INTEGER NOT NULL,
                updated_at    INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    private fun createAccountsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS accounts (
                id             TEXT    PRIMARY KEY,
                name           TEXT    NOT NULL,
                type           TEXT    NOT NULL,
                school_id      TEXT,
                balance_cents  INTEGER NOT NULL DEFAULT 0,
                balance_manual INTEGER NOT NULL DEFAULT 1,
                enabled        INTEGER NOT NULL DEFAULT 1,
                sort_order     INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------ 账户

    fun accounts(): List<Account> {
        val out = ArrayList<Account>()
        readableDatabase.query(
            "accounts", null, null, null, null, null, "sort_order ASC, id ASC",
        ).use { c ->
            while (c.moveToNext()) out += c.toAccount()
        }
        return out
    }

    fun account(id: String): Account? =
        readableDatabase.query("accounts", null, "id = ?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst()) it.toAccount() else null
        }

    /** 不存在才插入；已存在则保留原记录（不覆盖用户改过的余额） */
    fun ensureAccount(account: Account): Boolean {
        val values = ContentValues().apply {
            put("id", account.id)
            put("name", account.name)
            put("type", account.type.name)
            put("school_id", account.schoolId)
            put("balance_cents", account.balanceCents)
            put("balance_manual", if (account.balanceManual) 1 else 0)
            put("enabled", if (account.enabled) 1 else 0)
            put("sort_order", account.sortOrder)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "accounts", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
        return rowId != -1L
    }

    fun updateAccountBalance(id: String, balanceCents: Long) {
        writableDatabase.update(
            "accounts", ContentValues().apply { put("balance_cents", balanceCents) },
            "id = ?", arrayOf(id),
        )
    }

    fun updateAccount(account: Account) {
        writableDatabase.update(
            "accounts",
            ContentValues().apply {
                put("name", account.name)
                put("balance_cents", account.balanceCents)
                put("balance_manual", if (account.balanceManual) 1 else 0)
                put("enabled", if (account.enabled) 1 else 0)
                put("sort_order", account.sortOrder)
            },
            "id = ?", arrayOf(account.id),
        )
    }

    fun deleteAccount(id: String) {
        writableDatabase.delete("accounts", "id = ?", arrayOf(id))
    }

    private fun android.database.Cursor.toAccount(): Account {
        fun col(n: String) = getColumnIndexOrThrow(n)
        return Account(
            id = getString(col("id")),
            name = getString(col("name")),
            type = runCatching { AccountType.valueOf(getString(col("type"))) }
                .getOrDefault(AccountType.OTHER),
            schoolId = getString(col("school_id")),
            balanceCents = getLong(col("balance_cents")),
            balanceManual = getInt(col("balance_manual")) == 1,
            enabled = getInt(col("enabled")) == 1,
            sortOrder = getInt(col("sort_order")),
        )
    }

    // ------------------------------------------------------------ 写入

    /** @return true 表示新插入，false 表示 orderId 已存在或被用户删除过 */
    fun insert(entry: LedgerEntry): Boolean {
        // 用户删掉的记录不要自己回来：
        // 同步会重放校园卡流水、重复通知会重放同样的指纹，所以必须查墓碑。
        if (isDeleted(entry.orderId)) return false
        val values = ContentValues().apply {
            put("order_id", entry.orderId)
            put("time_text", entry.timeText)
            put("epoch_millis", entry.epochMillis)
            put("amount_cents", entry.amountCents)
            put("is_income", if (entry.isIncome) 1 else 0)
            put("kind", entry.kind)
            put("merchant", entry.merchant)
            put("pay_name", entry.payName)
            put("balance_cents", entry.balanceCents)
            put("to_account", entry.toAccount)
            put("category", entry.category)
            put("note", entry.note)
            put("manual", if (entry.manual) 1 else 0)
            put("account_id", entry.accountId)
            put("source", entry.source.name)
            put("is_transfer", if (entry.isTransfer) 1 else 0)
            put("transfer_group_id", entry.transferGroupId)
            put("raw_text", entry.rawText)
        }
        val rowId = writableDatabase.insertWithOnConflict(
            "entries", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
        return rowId != -1L
    }

    fun updateCategory(orderId: String, category: String) {
        writableDatabase.update(
            "entries", ContentValues().apply { put("category", category) },
            "order_id = ?", arrayOf(orderId),
        )
    }

    fun updateNote(orderId: String, note: String) {
        writableDatabase.update(
            "entries", ContentValues().apply { put("note", note) },
            "order_id = ?", arrayOf(orderId),
        )
    }

    /**
     * 改一笔记录的**名称**（交易对方/商户）。
     *
     * 账单和通知里的名字经常没法看：微信写「商户消费」「微信支付转账」，
     * 校园卡写「POS消费」—— 改成「三食堂」「充饭卡」是很自然的需求。
     */
    fun updateMerchant(orderId: String, merchant: String) {
        writableDatabase.update(
            "entries", ContentValues().apply { put("merchant", merchant) },
            "order_id = ?", arrayOf(orderId),
        )
    }

    /**
     * 补全一条「待确认」记录：写金额与收支方向，并把待确认标记去掉。
     *
     * 补完之后它就变成一条正常账目 —— 账户余额是「快照 + 流水」算出来的，
     * 所以金额一进来余额立刻跟着变，不需要另外改余额。
     */
    fun confirmEntry(
        orderId: String,
        amountCents: Long,
        isIncome: Boolean,
        kind: String,
        category: String,
        /** 账单对账时顺手补上商户名；null 表示保留原值 */
        merchant: String? = null,
    ) {
        writableDatabase.update(
            "entries",
            ContentValues().apply {
                put("amount_cents", amountCents)
                put("is_income", if (isIncome) 1 else 0)
                put("kind", kind)
                put("category", category)
                put("note", "")
                if (!merchant.isNullOrBlank()) put("merchant", merchant)
            },
            "order_id = ?", arrayOf(orderId),
        )
    }

    /**
     * 记下「这个 orderId 已经处理过了」，重复导入时不要再插一条。
     *
     * 复用 `deleted_orders` 表（它本来就是「这个 id 别再插进来」的墓碑表）。
     * 账单对账时，某条账单已经把一笔「待确认」补全了，就该把账单自己的交易单号
     * 也记在这里 —— 否则下次导入同一份账单又会插一条重复的。
     */
    fun markOrderHandled(orderId: String) {
        val values = ContentValues().apply {
            put("order_id", orderId)
            put("deleted_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "deleted_orders", null, values, SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** 把两条记录标记为同一笔内部转账 */
    fun markTransfer(orderIds: List<String>, groupId: String) {
        if (orderIds.isEmpty()) return
        val placeholders = orderIds.joinToString(",") { "?" }
        writableDatabase.execSQL(
            "UPDATE entries SET is_transfer = 1, transfer_group_id = ? WHERE order_id IN ($placeholders)",
            (listOf(groupId) + orderIds).toTypedArray(),
        )
    }

    /** 取消转账标记（用户手工解除配对时用） */
    fun clearTransferMarks(groupId: String) {
        writableDatabase.execSQL(
            "UPDATE entries SET is_transfer = 0, transfer_group_id = NULL WHERE transfer_group_id = ?",
            arrayOf(groupId),
        )
    }

    fun byOrderId(orderId: String): LedgerEntry? =
        readableDatabase.query("entries", null, "order_id = ?", arrayOf(orderId), null, null, null)
            .use { if (it.moveToFirst()) it.toEntry() else null }

    fun transferGroup(groupId: String): List<LedgerEntry> =
        query("transfer_group_id = ?", arrayOf(groupId), 50)

    /**
     * 删除一条记录，并**留下墓碑**。
     *
     * 墓碑是必需的：校园卡流水会在下次同步时按 orderId 重放，通知也会因为重复投递而重放，
     * 不留墓碑的话删掉的东西会自己长回来 —— 用户会以为「删了没用」。
     */
    fun delete(orderId: String) {
        writableDatabase.delete("entries", "order_id = ?", arrayOf(orderId))
        writableDatabase.insertWithOnConflict(
            "deleted_orders", null,
            ContentValues().apply {
                put("order_id", orderId)
                put("deleted_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    /** 这个 orderId 是否被用户删除过 */
    fun isDeleted(orderId: String): Boolean =
        readableDatabase.rawQuery(
            "SELECT 1 FROM deleted_orders WHERE order_id = ?",
            arrayOf(orderId),
        ).use { it.moveToFirst() }

    // ------------------------------------------------------------ 查询

    fun all(limit: Int = 2000): List<LedgerEntry> = query(null, null, limit)

    fun byAccount(accountId: String, limit: Int = 2000): List<LedgerEntry> =
        query("account_id = ?", arrayOf(accountId), limit)

    fun between(fromMillis: Long, toMillis: Long, limit: Int = 5000): List<LedgerEntry> =
        query("epoch_millis >= ? AND epoch_millis < ?", arrayOf("$fromMillis", "$toMillis"), limit)

    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM entries", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    fun countByAccount(accountId: String): Int =
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM entries WHERE account_id = ?", arrayOf(accountId),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private fun query(selection: String?, args: Array<String>?, limit: Int): List<LedgerEntry> {
        val out = ArrayList<LedgerEntry>()
        readableDatabase.query(
            "entries", null, selection, args, null, null, "epoch_millis DESC", "$limit",
        ).use { c ->
            while (c.moveToNext()) out += c.toEntry()
        }
        return out
    }

    private fun android.database.Cursor.toEntry(): LedgerEntry {
        fun col(n: String) = getColumnIndexOrThrow(n)
        return LedgerEntry(
            id = getLong(col("id")),
            orderId = getString(col("order_id")),
            timeText = getString(col("time_text")),
            epochMillis = getLong(col("epoch_millis")),
            amountCents = getLong(col("amount_cents")),
            isIncome = getInt(col("is_income")) == 1,
            kind = getString(col("kind")),
            merchant = getString(col("merchant")),
            payName = getString(col("pay_name")),
            balanceCents = getLong(col("balance_cents")),
            toAccount = getString(col("to_account")),
            category = getString(col("category")),
            note = getString(col("note")),
            manual = getInt(col("manual")) == 1,
            accountId = getString(col("account_id")).ifEmpty { AccountIds.MIGRATION_DEFAULT_CAMPUS },
            source = runCatching { EntrySource.valueOf(getString(col("source"))) }
                .getOrDefault(EntrySource.SYNC),
            isTransfer = getInt(col("is_transfer")) == 1,
            transferGroupId = getString(col("transfer_group_id")),
            rawText = getString(col("raw_text")),
        )
    }

    fun usedCategories(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT category, COUNT(*) c FROM entries WHERE category <> '' GROUP BY category ORDER BY c DESC",
            null,
        ).use { c -> while (c.moveToNext()) out += c.getString(0) }
        return out
    }

    // ------------------------------------------------------------ meta

    fun putMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "meta", null,
            ContentValues().apply { put("k", key); put("v", value) },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getMeta(key: String): String? =
        readableDatabase.rawQuery("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    // ------------------------------------------------------------ 本机账号

    fun localAccount(): LocalAccount? =
        readableDatabase.rawQuery(
            "SELECT username, password_hash, salt, iterations, created_at, updated_at " +
                "FROM local_account WHERE id = 1",
            null,
        ).use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                LocalAccount(
                    username = c.getString(0),
                    passwordHash = c.getString(1),
                    salt = c.getString(2),
                    iterations = c.getInt(3),
                    createdAt = c.getLong(4),
                    updatedAt = c.getLong(5),
                )
            }
        }

    /** 单行表：写就是覆盖那唯一一行 */
    fun saveLocalAccount(account: LocalAccount) {
        writableDatabase.execSQL(
            """
            INSERT OR REPLACE INTO local_account
                (id, username, password_hash, salt, iterations, created_at, updated_at)
            VALUES (1, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                account.username,
                account.passwordHash,
                account.salt,
                account.iterations,
                account.createdAt,
                account.updatedAt,
            ),
        )
    }

    fun deleteLocalAccount() {
        writableDatabase.execSQL("DELETE FROM local_account")
    }

    private companion object {
        const val DB_NAME = "ecard_ledger.db"
        const val DB_VERSION = 4
    }
}
