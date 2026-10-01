package com.morchid.ecardledger.data

/**
 * 最小 XLSX 读取：把 `xl/worksheets/sheet1.xml` + `xl/sharedStrings.xml` 读成二维表格。
 *
 * ## 为什么要自己写
 *
 * xlsx 实质是个 zip（所以 [BillFileReader] 那层本来就能打开它），难点只在里面的 XML。
 * 引入 Apache POI 会给一个 9MB 的 App 加上十几 MB 的依赖，而账单只需要读**纯文本单元格** ——
 * 不值得。这里用正则解析那两段结构规整的 XML，够用且没有任何新依赖。
 *
 * 解析完交给 [BillImporter.parseRows]，和 CSV 走**同一套**表头映射逻辑，
 * 所以 xlsx 与 csv 的行为天然一致。
 */
object XlsxReader {

    private val ROW = Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL)
    private val SHARED_ITEM = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
    private val TEXT_NODE = Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("""<c\b([^>]*?)(?:/>|>(.*?)</c>)""", RegexOption.DOT_MATCHES_ALL)
    private val CELL_REF = Regex("""r="([A-Z]+)\d+"""")
    private val CELL_TYPE = Regex("""t="([^"]+)"""")
    private val VALUE_NODE = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
    private val INLINE_TEXT = Regex("""<is>.*?<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)

    /** 是否是 xlsx 的内脏（zip 里有没有 xl/workbook.xml） */
    fun looksLikeXlsx(entryNames: Collection<String>): Boolean =
        entryNames.any { it.equals("xl/workbook.xml", ignoreCase = true) }

    /** 共享字符串表：每个 `<si>` 里的所有 `<t>` 拼起来就是一条 */
    internal fun parseSharedStrings(xml: String): List<String> =
        SHARED_ITEM.findAll(xml).map { si ->
            TEXT_NODE.findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
        }.toList()

    internal fun parseSheet(xml: String, shared: List<String>): List<List<String>> {
        val rows = ArrayList<List<String>>()
        ROW.findAll(xml).forEach { rowMatch ->
            val cells = ArrayList<Pair<Int, String>>()
            CELL.findAll(rowMatch.groupValues[1]).forEach { cell ->
                val attrs = cell.groupValues[1]
                val body = cell.groupValues[2]
                val ref = CELL_REF.find(attrs)?.groupValues?.get(1) ?: return@forEach
                val type = CELL_TYPE.find(attrs)?.groupValues?.get(1).orEmpty()
                val value = when (type) {
                    // s = 指向共享字符串表
                    "s" -> VALUE_NODE.find(body)?.groupValues?.get(1)
                        ?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                    "inlineStr" -> INLINE_TEXT.find(body)?.groupValues?.get(1).orEmpty()
                    else -> VALUE_NODE.find(body)?.groupValues?.get(1).orEmpty()
                }
                cells += columnIndex(ref) to unescape(value)
            }
            if (cells.isEmpty()) {
                rows.add(emptyList())
            } else {
                // 空单元格在 xlsx 里是**直接不出现**的，所以要按下标补齐
                val width = (cells.maxOf { it.first }) + 1
                val line = MutableList(width) { "" }
                cells.forEach { (index, value) -> if (index < width) line[index] = value }
                rows.add(line)
            }
        }
        return rows
    }

    /** "A" → 0，"B" → 1，"AA" → 26 */
    internal fun columnIndex(letters: String): Int {
        var n = 0
        letters.uppercase().forEach { c ->
            if (c in 'A'..'Z') n = n * 26 + (c - 'A' + 1)
        }
        return (n - 1).coerceAtLeast(0)
    }

    private fun unescape(text: String): String = text
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
}
