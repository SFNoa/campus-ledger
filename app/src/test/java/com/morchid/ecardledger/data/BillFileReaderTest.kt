package com.morchid.ecardledger.data

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.EncryptionMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 账单文件读取测试：直接 CSV / 加密 zip / 错误密码 / 压缩包里没有账单。
 *
 * zip 是用 zip4j 现场造出来的（和微信邮件里那种带密码的 zip 同构），
 * 所以这里的「能不能解密」是真的验证过，不是假定。
 */
class BillFileReaderTest {

    private val csv = """
        微信支付账单明细,,,,,,,,,,
        微信昵称：[张三],,,,,,,,,,
        交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
        2026-08-05 12:30:45,商户消费,食堂,餐饮,支出,¥25.50,零钱,支付成功,420000123,,
    """.trimIndent()

    private fun zipFileOf(content: String, innerName: String, password: String?): File {
        // 先落一个真实的 CSV 文件，再用 addFile 打包 ——
        // addStream 那条路试过两种写法，zip4j 都不给加密（自检用例会说 isEncrypted=false），
        // addFile 是 zip4j 最常规的路径，加密参数确实生效。
        val csvFile = File.createTempFile("bill-source", ".csv")
        csvFile.writeText(content, Charsets.UTF_8)

        val tmp = File.createTempFile("bill-under-test", ".zip")
        tmp.delete() // zip4j 要求目标不存在
        val zipFile = if (password == null) {
            ZipFile(tmp)
        } else {
            ZipFile(tmp, password.toCharArray())
        }
        val params = ZipParameters().apply {
            fileNameInZip = innerName
            if (password != null) {
                // ⚠️ 两个开关都要开：zip4j 2.11 里 isEncryptFiles 默认 false，
                //    只设 encryptionMethod=AES 的话打出来的是**明文包**（这条是自检用例抓到的）
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
            }
        }
        zipFile.addFile(csvFile, params)
        csvFile.delete()
        return tmp
    }

    private fun zipOf(content: String, innerName: String, password: String?): ByteArray {
        val file = zipFileOf(content, innerName, password)
        return try {
            file.readBytes()
        } finally {
            file.delete()
        }
    }

    @Test
    fun `测试夹具自检：带密码造出来的 zip 必须真的是加密的`() {
        val file = zipFileOf(csv, "账单.csv", "1234")
        try {
            val header = ZipFile(file).fileHeaders.first()
            assertTrue(
                "zip4j 自己都说这个条目没加密，那后面的密码用例就是假通过",
                header.isEncrypted,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `直接给 CSV 时按文本读`() {
        val result = BillFileReader.read("微信支付账单.csv", csv.toByteArray(Charsets.UTF_8))
        assertTrue(result is BillFileReader.Result.Text)
        val text = result as BillFileReader.Result.Text
        assertFalse("不是从 zip 里出来的", text.fromZip)
        assertTrue(text.content.contains("南校区".take(0) + "食堂"))
    }

    @Test
    fun `加密 zip 用正确密码能解出账单`() {
        val bytes = zipOf(csv, "微信支付账单(20260801-20260831).csv", "1234")

        val result = BillFileReader.read("微信支付账单.zip", bytes, "1234")

        assertTrue("密码对了就该成功，实际：$result", result is BillFileReader.Result.Text)
        val text = result as BillFileReader.Result.Text
        assertTrue(text.fromZip)
        assertEquals("微信支付账单(20260801-20260831).csv", text.innerName)
        assertTrue(text.content.contains("交易时间"))
    }

    @Test
    fun `没给密码时说清要密码，而不是报一堆异常`() {
        val bytes = zipOf(csv, "账单.csv", "1234")
        val result = BillFileReader.read("微信支付账单.zip", bytes, password = null)
        assertTrue("应当提示需要密码，实际：$result", result is BillFileReader.Result.NeedPassword)
    }

    @Test
    fun `密码错了也提示要密码`() {
        val bytes = zipOf(csv, "账单.csv", "正确密码")
        val result = BillFileReader.read("微信支付账单.zip", bytes, "错误密码")
        assertTrue("实际：$result", result is BillFileReader.Result.NeedPassword)
    }

    @Test
    fun `压缩包里没有账单时给出可读原因`() {
        val bytes = zipOf("hello", "readme.md", "1234")
        val result = BillFileReader.read("x.zip", bytes, "1234")
        assertTrue(result is BillFileReader.Result.Failed)
        assertTrue((result as BillFileReader.Result.Failed).reason.contains("CSV"))
    }

    @Test
    fun `靠文件头就能认出 zip，不依赖扩展名`() {
        val bytes = zipOf(csv, "账单.csv", null)
        assertTrue("PK 开头的就是 zip", BillFileReader.looksLikeZip("没有扩展名", bytes))
        assertFalse(BillFileReader.looksLikeZip("账单.csv", csv.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `无密码的 zip 也能读`() {
        val bytes = zipOf(csv, "账单.csv", null)
        val result = BillFileReader.read("账单.zip", bytes)
        assertTrue("实际：$result", result is BillFileReader.Result.Text)
    }
}
