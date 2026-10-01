package com.morchid.ecardledger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CAS 会话复用的回归测试（离线，用假 HttpClient 复现线上 bug）。
 *
 * 线上现象：进入 App 后**第二次**登录/同步失败，提示
 * 「登录页结构变了：找不到 id=pwdFromId 的表单」。
 *
 * 原因：CookieManager 是进程级的。第一次登录成功后 CAS 会话仍然有效，
 * 于是第二次 GET 登录页会被 CAS **直接 302 送回 ecard** 并带上新的 synjones-auth，
 * 页面里根本没有账号密码表单。老代码在那一步直接抛异常。
 *
 * 这个测试文件就是当时缺掉的那块覆盖。
 */
class CasSessionReuseTest {

    /** 按顺序返回预先编排好的响应，并记录调用，模拟真实的「GET 登录页 → POST 凭据 → 跟随重定向」 */
    private class FakeHttp(private val queue: MutableList<HttpResponse>) : HttpClient() {
        val calls = mutableListOf<String>()
        var lastForm: Map<String, String>? = null

        override fun request(
            url: String,
            method: String,
            form: Map<String, String>?,
            headers: Map<String, String>,
        ): HttpResponse {
            calls += "$method $url"
            if (form != null) lastForm = form
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    private fun loginPageHtml(): String =
        javaClass.classLoader!!.getResourceAsStream("cas-login.html")!!
            .readBytes().toString(Charsets.UTF_8)

    private val tokenUrl =
        "https://ecard.csu.edu.cn/plat-pc/?name=loginTransit&synjones-auth=AAA.BBB.CCC"

    // ------------------------------------------------------------ 第一次登录

    @Test
    fun `第一次登录：登录页有表单，走完整的密码提交流程`() {
        val http = FakeHttp(
            mutableListOf(
                HttpResponse(200, "https://ca.csu.edu.cn/authserver/login?service=x", loginPageHtml(), emptyList()),
                HttpResponse(200, tokenUrl, "<html>ecard</html>", emptyList()),
            ),
        )

        assertEquals("AAA.BBB.CCC", EcardApi.login(http, "8207260917", "pw"))

        assertEquals("应当是「GET 登录页 + POST 凭据」两次请求", 2, http.calls.size)
        assertTrue("第二次必须是 POST", http.calls[1].startsWith("POST"))
    }

    @Test
    fun `提交的字段正确：有 username 与加密后的 password，且绝不含明文密码`() {
        val http = FakeHttp(
            mutableListOf(
                HttpResponse(200, "https://ca.csu.edu.cn/authserver/login?service=x", loginPageHtml(), emptyList()),
                HttpResponse(200, tokenUrl, "<html>ecard</html>", emptyList()),
            ),
        )
        EcardApi.login(http, "8207260917", "MySecret123")

        val form = http.lastForm!!
        assertEquals("8207260917", form["username"])
        assertTrue("password 字段应当存在", form.containsKey("password"))
        assertTrue(
            "password 应当是 base64 密文，而不是明文",
            form["password"]!!.length > 40 && form["password"] != "MySecret123",
        )
        assertFalse("任何字段都不该出现明文密码", form.values.any { it == "MySecret123" })
        assertEquals("cllt 必须是 userNameLogin", "userNameLogin", form["cllt"])
        assertFalse("站点会 disable 掉 passwordText，不该提交", form.containsKey("passwordText"))
    }

    // ------------------------------------------------------------ 第二次登录（线上 bug）

    @Test
    fun `第二次登录：CAS 会话仍在，直接复用 token，不再要求表单`() {
        // 这一次 GET 直接被 302 送回 ecard，最终 URL 带 token，页面里没有登录表单
        val http = FakeHttp(
            mutableListOf(
                HttpResponse(
                    200,
                    "https://ecard.csu.edu.cn/plat-pc/?name=loginTransit&synjones-auth=DDD.EEE.FFF",
                    "<html>ecard</html>",
                    emptyList(),
                ),
            ),
        )

        assertEquals(
            "会话仍在时必须复用已有会话拿到 token，而不是报「找不到表单」",
            "DDD.EEE.FFF",
            EcardApi.login(http, "8207260917", "pw"),
        )
        assertEquals("复用会话时只应发生一次 GET，不该再提交密码", 1, http.calls.size)
        assertEquals(null, http.lastForm)
    }

    @Test
    fun `连续两次登录都成功（同一条会话链）`() {
        val http = FakeHttp(
            mutableListOf(
                HttpResponse(200, "https://ca.csu.edu.cn/authserver/login?service=x", loginPageHtml(), emptyList()),
                HttpResponse(200, tokenUrl, "<html>ecard</html>", emptyList()),
                // 第二次 GET 直接被送走
                HttpResponse(
                    200,
                    "https://ecard.csu.edu.cn/plat-pc/?name=loginTransit&synjones-auth=DDD.EEE.FFF",
                    "<html>ecard</html>",
                    emptyList(),
                ),
            ),
        )

        assertEquals("AAA.BBB.CCC", EcardApi.login(http, "8207260917", "pw"))
        assertEquals(
            "第二次登录不能失败 —— 这就是线上报的 bug",
            "DDD.EEE.FFF",
            EcardApi.login(http, "8207260917", "pw"),
        )
    }

    // ------------------------------------------------------------ 异常路径

    @Test
    fun `既没有表单也没有 token：报页面结构变了，并带上最终地址便于排查`() {
        val http = FakeHttp(
            mutableListOf(
                HttpResponse(200, "https://ca.csu.edu.cn/authserver/login", "<html>something else</html>", emptyList()),
            ),
        )

        val error = assertThrows(AuthException::class.java) { EcardApi.login(http, "u", "p") }
        assertTrue("应提示找不到表单", error.message!!.contains("pwdFromId"))
        assertTrue("应带上最终地址", error.message!!.contains("authserver/login"))
    }

    @Test
    fun `跳到了 ecard 却没 token：提示登录态异常而不是页面结构变了`() {
        val http = FakeHttp(
            mutableListOf(
                HttpResponse(200, "https://ecard.csu.edu.cn/plat-pc/", "<html>ecard</html>", emptyList()),
            ),
        )

        val error = assertThrows(AuthException::class.java) { EcardApi.login(http, "u", "p") }
        assertTrue(
            "应当是登录态异常，实际: ${error.message}",
            error.message!!.contains("登录态异常"),
        )
    }

    @Test
    fun `登录页打不开时给出 HTTP 状态码`() {
        val http = FakeHttp(
            mutableListOf(HttpResponse(503, "https://ca.csu.edu.cn/authserver/login", "", emptyList())),
        )
        val error = assertThrows(AuthException::class.java) { EcardApi.login(http, "u", "p") }
        assertTrue(error.message!!.contains("503"))
    }

    // ------------------------------------------------------------ 失败判断不能被子串骗到

    @Test
    fun `密码错误时不能因为 service 参数里含 ecard 域名就误判为登录成功`() {
        // 真实的失败响应：仍停在 CAS 域，但 URL 的 service 参数把 ecard 域名编码了进去
        val failedUrl = "https://ca.csu.edu.cn/authserver/login" +
            "?service=https%3A%2F%2Fecard.csu.edu.cn%2Fberserker-auth%2Fcas%2Flogin%2Fwisedu"
        val body = "<div><span id=\"showErrorTip\"><span>认证失败</span></span></div>"

        val http = FakeHttp(
            mutableListOf(
                HttpResponse(200, "https://ca.csu.edu.cn/authserver/login?service=x", loginPageHtml(), emptyList()),
                HttpResponse(401, failedUrl, body, emptyList()),
            ),
        )

        val error = assertThrows(AuthException::class.java) {
            EcardApi.login(http, "8207260917", "wrong-password")
        }
        assertTrue(
            "应当报「登录失败：认证失败」，实际: ${error.message}",
            error.message!!.contains("认证失败"),
        )
        assertFalse(
            "绝不能出现「登录成功」这种把用户带偏的措辞: ${error.message}",
            error.message!!.contains("登录成功"),
        )
    }

    @Test
    fun `会话失效且页面异常时，也不能因子串而误报为登录态异常`() {
        val casUrl = "https://ca.csu.edu.cn/authserver/login" +
            "?service=https%3A%2F%2Fecard.csu.edu.cn%2Fx"
        val http = FakeHttp(
            mutableListOf(HttpResponse(200, casUrl, "<html>no form here</html>", emptyList())),
        )
        val error = assertThrows(AuthException::class.java) { EcardApi.login(http, "u", "p") }
        assertTrue(
            "应当报找不到表单，实际: ${error.message}",
            error.message!!.contains("pwdFromId"),
        )
    }

    // ------------------------------------------------------------ 学校风控

    @Test
    fun `账号被风控冻结时，既要保留学校原文也要补上怎么办`() {
        // 这是真实遇到的原文（测试期间登录太频繁被冻结）
        val body = "<div><span id=\"showErrorTip\"><span>" +
            "该账号已被冻结，预计解冻时间：2026-09-28 15:44:30，冻结原因：账号频繁访问；" +
            "可通过账号解禁功能自助解冻。</span></span></div>"
        val http = FakeHttp(
            mutableListOf(
                HttpResponse(200, "https://ca.csu.edu.cn/authserver/login?service=x", loginPageHtml(), emptyList()),
                HttpResponse(200, "https://ca.csu.edu.cn/authserver/login", body, emptyList()),
            ),
        )

        val error = assertThrows(AuthException::class.java) { EcardApi.login(http, "u", "p") }
        val message = error.message!!
        assertTrue("必须保留学校的解冻时间，否则用户不知道该等多久：$message", message.contains("15:44:30"))
        assertTrue("必须告诉用户去哪儿处理：$message", message.contains("账号解禁"))
        assertTrue("应当说明这是风控而不是密码错：$message", message.contains("频繁"))
    }

    @Test
    fun `普通错误不加多余解释`() {
        val message = EcardApi.describeLoginFailure("您提供的用户名或者密码有误", 200)
        assertTrue(message.contains("您提供的用户名或者密码有误"))
        assertFalse("不该给密码错误加风控说明", message.contains("风控"))
    }

    @Test
    fun `没有提示时给出通用文案并带状态码`() {
        val message = EcardApi.describeLoginFailure(null, 503)
        assertTrue(message.contains("503"))
        assertTrue(message.contains("账号或密码"))
    }
}
