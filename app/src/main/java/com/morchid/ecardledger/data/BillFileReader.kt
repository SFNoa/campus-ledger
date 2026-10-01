package com.morchid.ecardledger.data

import net.lingala.zip4j.io.inputstream.ZipInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * 账单文件的读取：可能是**加密 zip**，也可能是直接的 CSV。
 *
 * 微信「下载账单」发到邮箱的是一个**带密码的 zip**（密码由微信单独发给你），
 * 支付宝类似。`java.util.zip` 不支持加密压缩包，所以这里用 zip4j。
 *
 * 这一层刻意把「是不是 zip」「要不要密码」和「账单怎么解析」分开：
 * 上面的流程只管拿到文本，解析交给 [BillImporter]。
 */
object BillFileReader {

    sealed interface Result {
        /** 拿到账单文本了（CSV） */
        data class Text(val content: String, val fromZip: Boolean, val innerName: String = "") : Result

        /** 拿到账单表格了（XLSX：它本身就是 zip，里面是 XML） */
        data class Rows(val rows: List<List<String>>, val innerName: String) : Result

        /** 是加密 zip，需要密码 */
        data class NeedPassword(val innerName: String = "") : Result

        data class Failed(val reason: String) : Result
    }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\u0003\u0004"

    private val BILL_EXTENSIONS = listOf(".csv", ".txt")

    fun looksLikeZip(fileName: String?, bytes: ByteArray): Boolean {
        if (bytes.size >= 4 && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }) return true
        return fileName?.lowercase()?.endsWith(".zip") == true
    }

    /**
     * @param password zip 密码；不是 zip 时忽略
     */
    fun read(fileName: String?, bytes: ByteArray, password: String? = null): Result {
        if (!looksLikeZip(fileName, bytes)) {
            return Result.Text(BillImporter.decode(bytes), fromZip = false)
        }

        val inner = unzip(bytes, password)
        return when (inner) {
            is UnzipResult.Ok -> {
                val text = BillImporter.decode(inner.bytes)
                // **用内容兜底**：密码不对时底层库不一定抛异常（有时只解出乱码），
                // 所以解出来必须真的像一份账单，否则就当成「密码不对」再问一次。
                if (BillImporter.looksLikeBill(text)) {
                    Result.Text(text, fromZip = true, innerName = inner.name)
                } else {
                    Result.NeedPassword(inner.name)
                }
            }
            is UnzipResult.Xlsx -> Result.Rows(inner.rows, inner.name)
            is UnzipResult.NeedPassword -> Result.NeedPassword(inner.name)
            is UnzipResult.Failed -> Result.Failed(inner.reason)
        }
    }

    private sealed interface UnzipResult {
        data class Ok(val bytes: ByteArray, val name: String) : UnzipResult
        data class Xlsx(val rows: List<List<String>>, val name: String) : UnzipResult
        data class NeedPassword(val name: String = "") : UnzipResult
        data class Failed(val reason: String) : UnzipResult
    }

    private fun unzip(bytes: ByteArray, password: String?): UnzipResult {
        val pwd = password?.takeIf { it.isNotEmpty() }?.toCharArray()
        return try {
            ZipInputStream(ByteArrayInputStream(bytes), pwd).use { zip ->
                var entry = zip.nextEntry
                var sawAnyFile = false
                var firstName = ""
                // xlsx 的三块内脏（xlsx 本身就是 zip，所以走的是同一条解压路径）
                var sharedStringsXml: String? = null
                var sheetXml: String? = null
                var sheetName = ""
                var hasWorkbook = false

                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (firstName.isEmpty()) firstName = entry.fileName
                        val name = entry.fileName.lowercase()
                        when {
                            BILL_EXTENSIONS.any { name.endsWith(it) } -> {
                                val out = readAll(zip)
                                return UnzipResult.Ok(out, entry.fileName)
                            }
                            name == "xl/workbook.xml" -> hasWorkbook = true
                            name == "xl/sharedstrings.xml" ->
                                sharedStringsXml = String(readAll(zip), Charsets.UTF_8)
                            name.startsWith("xl/worksheets/") && name.endsWith(".xml") && sheetXml == null -> {
                                sheetXml = String(readAll(zip), Charsets.UTF_8)
                                sheetName = entry.fileName
                            }
                            else -> Unit
                        }
                        sawAnyFile = true
                    }
                    entry = zip.nextEntry
                }

                if (hasWorkbook && sheetXml != null) {
                    val shared = sharedStringsXml?.let { XlsxReader.parseSharedStrings(it) } ?: emptyList()
                    return UnzipResult.Xlsx(XlsxReader.parseSheet(sheetXml, shared), sheetName)
                }
                if (!sawAnyFile) {
                    UnzipResult.Failed("这个压缩包里没有文件")
                } else {
                    UnzipResult.Failed(
                        "压缩包里没找到账单（里面是：$firstName）。" +
                            "需要 CSV 或 XLSX 格式的账单文件。",
                    )
                }
            }
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            // zip4j 在「没给密码 / 密码不对」时的报错花样不少：
            //  - "Wrong Password" / "empty or null password"
            //  - AES 是校验整个条目，密码不对会表现为 MAC 或 CRC 校验失败
            // 所以这里一并当成「需要密码」，让用户重试 —— 比抛个技术异常给他看强。
            val lower = message.lowercase()
            if (lower.contains("password") ||
                lower.contains("wrong") ||
                lower.contains("encrypted") ||
                lower.contains("mac") ||
                lower.contains("crc") ||
                lower.contains("verification failed")
            ) {
                UnzipResult.NeedPassword()
            } else {
                UnzipResult.Failed("解压失败：${message.ifBlank { e.javaClass.simpleName }}")
            }
        }
    }

    private fun readAll(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = zip.read(buffer)
            if (n <= 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
