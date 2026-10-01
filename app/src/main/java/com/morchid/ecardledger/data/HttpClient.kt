package com.morchid.ecardledger.data

import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class HttpResponse(
    val status: Int,
    val url: String,
    val body: String,
    val trace: List<String>,
) {
    val ok: Boolean get() = status in 200..299
}

/**
 * 极简 HTTP 客户端，只依赖 JDK 自带的 HttpURLConnection，不引入 OkHttp。
 *
 * 两个要点：
 *  1. 必须手动跟随重定向 —— 登录成功后要从最后一跳的 URL 里取出 synjones-auth token，
 *     自动跟随会丢掉中间的 Location。
 *  2. cookie 交给 java.net.CookieManager 维护，CAS(ca.csu.edu.cn) 与 ecard 两个域各自独立。
 *     注意它是**进程级**的 —— 这正是「第一次同步成功、第二次报找不到表单」那个 bug 的根源。
 *
 * 声明为 open 是为了让单元测试能用假实现替换掉真实网络
 * （见 CasSessionReuseTest：直接构造「CAS 会话仍在」的响应来复现该 bug）。
 */
open class HttpClient {

    init {
        if (CookieHandler.getDefault() == null) {
            CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        }
    }

    /**
     * 丢掉所有会话 cookie。
     * 退出登录时必须调用：否则再次「登录」会复用旧会话、根本不校验密码，输错密码也能进去。
     */
    fun clearCookies() {
        (CookieHandler.getDefault() as? CookieManager)?.cookieStore?.removeAll()
    }

    open fun request(
        url: String,
        method: String = "GET",
        form: Map<String, String>? = null,
        headers: Map<String, String> = emptyMap(),
    ): HttpResponse {
        var current = url
        var httpMethod = method
        var body: ByteArray? = form?.let { encodeForm(it).toByteArray(Charsets.UTF_8) }
        val trace = ArrayList<String>()

        repeat(MAX_REDIRECTS) {
            val parsed = URL(current)
            val conn = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = httpMethod
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 25_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                headers.forEach { (k, v) -> setRequestProperty(k, v) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
            }
            try {
                body?.let { bytes -> conn.outputStream.use { it.write(bytes) } }
                val status = conn.responseCode
                val location = conn.getHeaderField("Location")
                trace += "$status $httpMethod ${parsed.host}${parsed.path}"

                if (status in 300..399 && !location.isNullOrEmpty()) {
                    current = URL(parsed, location).toString()
                    httpMethod = "GET"   // 3xx 之后一律转 GET
                    body = null
                } else {
                    val stream = if (status >= 400) conn.errorStream else conn.inputStream
                    val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    return HttpResponse(status, current, text, trace)
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IllegalStateException("重定向次数过多：$url")
    }

    /** 调 ecard 的 berserker 业务接口 */
    fun getJson(url: String, token: String? = null): HttpResponse = request(
        url,
        headers = buildMap {
            put("Accept", "application/json, text/plain, */*")
            put("Referer", "https://ecard.csu.edu.cn/plat-pc/")
            if (!token.isNullOrEmpty()) {
                put("synjones-auth", "bearer $token")
                put("authorization", "bearer $token")
            }
        },
    )

    private fun encodeForm(form: Map<String, String>): String =
        form.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }

    companion object {
        private const val MAX_REDIRECTS = 15
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
