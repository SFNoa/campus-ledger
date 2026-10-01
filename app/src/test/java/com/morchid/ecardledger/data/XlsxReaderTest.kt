package com.morchid.ecardledger.data

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * XLSX 支持测试。
 *
 * 夹具是**现场用 zip4j 造的最小 xlsx**（xlsx 本身就是个 zip），
 * 所以这里验证的是「真的能读 xlsx」，而不是「我假定它能读」。
 */
class XlsxReaderTest {

    private val sharedStrings = """
        <?xml version="1.0" encoding="UTF-8"?>
        <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="5" uniqueCount="5">
        <si><t>交易时间</t></si>
        <si><t>交易对方</t></si>
        <si><t>收/支</t></si>
        <si><r><t>南校区</t></r><r><t>2食堂</t></r></si>
        <si><t>金额(元)</t></si>
        </sst>
    """.trimIndent()

    @Test
    fun `共享字符串表：多个 t 片段要拼起来`() {
        val shared = XlsxReader.parseSharedStrings(sharedStrings)
        assertEquals(5, shared.size)
        assertEquals("交易时间", shared[0])
        assertEquals("南校区2食堂", shared[3])
    }

    @Test
    fun `单元格：共享字符串、数字、以及跳过的空列都要对位`() {
        // A1..E1 表头；第 2 行 B 列故意缺失（xlsx 里空单元格直接不出现）
        val sheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>4</v></c><c r="E1" t="s"><v>3</v></c></row>
            <row r="2"><c r="A2" t="inlineStr"><is><t>2026-08-05 12:30:45</t></is></c><c r="C2" t="s"><v>2</v></c><c r="D2"><v>25.5</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()

        val rows = XlsxReader.parseSheet(sheet, XlsxReader.parseSharedStrings(sharedStrings))

        assertEquals(2, rows.size)
        assertEquals(listOf("交易时间", "交易对方", "收/支", "金额(元)", "南校区2食堂"), rows[0])
        assertEquals("2026-08-05 12:30:45", rows[1][0])
        assertEquals("B 列缺失要补空串，不能让 C 列的值串到 B 上", "", rows[1][1])
        assertEquals("收/支", rows[1][2])
        assertEquals("25.5", rows[1][3])
    }

    @Test
    fun `列号换算：A 是 0，Z 是 25，AA 是 26`() {
        assertEquals(0, XlsxReader.columnIndex("A"))
        assertEquals(25, XlsxReader.columnIndex("Z"))
        assertEquals(26, XlsxReader.columnIndex("AA"))
    }

    @Test
    fun `转义字符要还原`() {
        val xml = """<sst><si><t>套餐A&amp;B &lt;大份&gt;</t></si></sst>"""
        assertEquals(listOf("套餐A&B <大份>"), XlsxReader.parseSharedStrings(xml))
    }

    // ------------------------------------------------------------ 端到端：真 xlsx 文件

    /** 用 zip4j 造一个最小但结构正确的 xlsx */
    private fun xlsxFile(rows: List<List<String>>): File {
        // 把出现的所有文本放进共享字符串表，单元格用 t="s" 引用（和真实 xlsx 一样）
        val shared = LinkedHashMap<String, Int>()
        rows.forEach { row -> row.forEach { if (it.isNotBlank()) shared.getOrPut(it) { shared.size } } }

        val sharedXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><sst count="${shared.size}" uniqueCount="${shared.size}">""")
            shared.keys.forEach { append("<si><t>${escape(it)}</t></si>") }
            append("</sst>")
        }
        val sheetXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8"?><worksheet><sheetData>""")
            rows.forEachIndexed { rowIndex, row ->
                append("""<row r="${rowIndex + 1}">""")
                row.forEachIndexed { colIndex, value ->
                    if (value.isNotBlank()) {
                        val ref = columnName(colIndex) + (rowIndex + 1)
                        append("""<c r="$ref" t="s"><v>${shared[value]}</v></c>""")
                    }
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }

        val tmp = File.createTempFile("bill-under-test", ".xlsx")
        tmp.delete()
        val zip = ZipFile(tmp)
        fun add(name: String, content: String) {
            zip.addStream(
                ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)),
                ZipParameters().apply { fileNameInZip = name },
            )
        }
        add("xl/workbook.xml", """<?xml version="1.0"?><workbook/>""")
        add("xl/sharedStrings.xml", sharedXml)
        add("xl/worksheets/sheet1.xml", sheetXml)
        return tmp
    }

    private fun columnName(index: Int): String {
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.insert(0, ('A' + rem))
            n = (n - 1) / 26
        }
        return sb.toString()
    }

    private fun escape(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    @Test
    fun `xlsx 账单能一路读到记录`() {
        val file = xlsxFile(
            listOf(
                listOf("微信支付账单明细", "", "", "", "", ""),
                listOf("交易时间", "交易类型", "交易对方", "商品", "收/支", "金额(元)", "支付方式", "当前状态", "交易单号"),
                listOf("2026-08-05 12:30:45", "商户消费", "南校区2食堂", "餐饮", "支出", "25.50", "零钱", "支付成功", "420000123"),
                listOf("2026-08-06 09:10:00", "微信红包", "张三", "/", "收入", "88.00", "零钱", "已存入零钱", "100003950"),
            ),
        )
        try {
            val read = BillFileReader.read("微信支付账单.xlsx", file.readBytes())
            assertTrue("应当识别成表格：$read", read is BillFileReader.Result.Rows)

            val parsed = BillImporter.parseRows((read as BillFileReader.Result.Rows).rows)
            assertEquals(BillImporter.Platform.WECHAT, parsed.platform)
            assertEquals(2, parsed.records.size)
            assertEquals(2550L, parsed.records[0].amountCents)
            assertEquals("南校区2食堂", parsed.records[0].peer)
            assertEquals(BillImporter.Direction.INCOME, parsed.records[1].direction)
            assertEquals(8800L, parsed.records[1].amountCents)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `xlsx 与 csv 走同一套表头映射，列顺序变了也一样`() {
        val file = xlsxFile(
            listOf(
                listOf("金额(元)", "收/支", "交易时间", "交易对方", "交易类型"),
                listOf("¥12.34", "支出", "2026-08-05 12:30:45", "便利店", "商户消费"),
            ),
        )
        try {
            val read = BillFileReader.read("账单.xlsx", file.readBytes()) as BillFileReader.Result.Rows
            val parsed = BillImporter.parseRows(read.rows)
            assertEquals(1, parsed.records.size)
            assertEquals(1234L, parsed.records[0].amountCents)
            assertEquals("便利店", parsed.records[0].peer)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `不是 xlsx 的 zip 仍然按原样报错，不会误判`() {
        val tmp = File.createTempFile("not-bill", ".zip")
        tmp.delete()
        ZipFile(tmp).addStream(
            ByteArrayInputStream("hello".toByteArray()),
            ZipParameters().apply { fileNameInZip = "readme.md" },
        )
        try {
            val result = BillFileReader.read("x.zip", tmp.readBytes())
            assertTrue("实际：$result", result is BillFileReader.Result.Failed)
            assertFalse(result is BillFileReader.Result.Rows)
        } finally {
            tmp.delete()
        }
    }
}
