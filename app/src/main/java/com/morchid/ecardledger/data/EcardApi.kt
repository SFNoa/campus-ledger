package com.morchid.ecardledger.data

import com.morchid.ecardledger.data.school.SchoolProfile
import com.morchid.ecardledger.data.school.SchoolRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class AuthException(message: String) : Exception(message)

data class CardInfo(
    val cardName: String,
    val balanceCents: Long,
    val sno: String,
    val ownerName: String,
    val frozen: Boolean,
    val lost: Boolean,
)

data class TurnoverRecord(
    /** 服务端唯一流水号，用于去重 */
    val orderId: String,
    /** 本地时间文本，如 2026-09-28 12:02:13 */
    val timeText: String,
    /** 金额，单位：分（tranamt 永远是正数，方向看 isIncome） */
    val amountCents: Long,
    val isIncome: Boolean,
    /** 消费 / 充值 … */
    val kind: String,
    val merchant: String,
    val payName: String,
    /** 交易后余额，单位：分 */
    val balanceCents: Long,
    val toAccount: String,
    val raw: String,
)

data class TurnoverPage(val total: Int, val records: List<TurnoverRecord>)

/**
 * 中南大学校园卡接口。
 *
 * 全部结论都是在真机上跑通验证过的（见 tools/ecard-v2.mjs + tools/analyze 输出）：
 *  - CAS 登录走 id=pwdFromId 表单，cllt 必须是 userNameLogin
 *    （踩过的坑：用正则抓文档里第一个 name=cllt 会拿到 fido 表单的 fidoLogin，导致 401 认证失败）
 *  - POST 的 URL 必须带上 service 参数，否则服务端 500
 *  - 登录成功后从最后一跳 URL 的 synjones-auth 参数取 JWT，之后用 bearer 方式带在 header 上
 *  - 流水接口是 /berserker-search/search/personal/turnover，日期格式 YYYY-MM-DD
 *  - tranamt/cardBalance 单位是分；typeId=1 消费(支出)、typeId=2 充值(收入)
 */
object EcardApi {

    // 所有地址与路径都来自 SchoolProfile（见 data/school/SchoolProfile.kt）。
    // 换一所同样用「新中新一卡通 + 金智统一身份认证」的学校，只需要换一份配置。

    internal fun loginUrl(profile: SchoolProfile): String = profile.casLoginUrl

    // ------------------------------------------------------------ 表单解析

    // 以下三项设为 internal 是为了让单元测试直接验证表单解析——
    // 这里正是出过致命 bug 的地方（抓到 fidoLogin 导致 401），必须有回归测试。

    internal data class Field(
        val name: String,
        val id: String,
        val type: String,
        val value: String,
        val disabled: Boolean,
        val checked: Boolean,
    )

    internal data class Form(val action: String, val fields: List<Field>)

    internal fun parseForm(html: String, formId: String): Form? {
        val formTag = Regex(
            "<form[^>]*id=[\"']" + Regex.escape(formId) + "[\"'][^>]*>",
            RegexOption.IGNORE_CASE,
        ).find(html) ?: return null

        val start = formTag.range.last + 1
        val closeIdx = html.indexOf("</form>", start)
        val inner = html.substring(start, if (closeIdx < 0) html.length else closeIdx)

        fun attr(tag: String, name: String): String =
            Regex("$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                .find(tag)?.groupValues?.get(1) ?: ""

        val fields = Regex("<input[^>]*>", RegexOption.IGNORE_CASE).findAll(inner).map { m ->
            val tag = m.value
            Field(
                name = attr(tag, "name"),
                id = attr(tag, "id"),
                type = attr(tag, "type").ifEmpty { "text" }.lowercase(),
                value = attr(tag, "value"),
                disabled = Regex("\\bdisabled\\b", RegexOption.IGNORE_CASE).containsMatchIn(tag),
                checked = Regex("\\bchecked\\b", RegexOption.IGNORE_CASE).containsMatchIn(tag),
            )
        }.toList()

        val action = attr(formTag.value, "action").ifEmpty { "/authserver/login" }
        return Form(action, fields)
    }

    private fun extractToken(url: String): String? {
        val query = runCatching { URL(url).query }.getOrNull() ?: return null
        val raw = query.split("&").firstOrNull { it.startsWith("synjones-auth=") }
            ?.substringAfter("=") ?: return null
        return runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
    }

    // ------------------------------------------------------------ 登录

    /** @return berserker token（JWT） */
    fun login(
        http: HttpClient,
        username: String,
        password: String,
        profile: SchoolProfile = SchoolRegistry.default,
    ): String {
        val page = http.request(profile.casLoginUrl)
        if (!page.ok) throw AuthException("打不开统一身份认证登录页（HTTP ${page.status}）")

        val form = parseForm(page.body, profile.loginFormId)
            ?: return reuseSessionOrFail(page, profile)
        val salt = form.fields.firstOrNull { it.id == "pwdEncryptSalt" }?.value?.trim().orEmpty()
        if (salt.isEmpty()) throw AuthException("登录页结构变了：缺少 pwdEncryptSalt")

        val encrypted = AesPwd.encrypt(password, salt)
        val body = LinkedHashMap<String, String>()
        for (f in form.fields) {
            when {
                f.name.isEmpty() -> Unit                       // 无 name 不提交
                f.id == "saltPassword" -> body[f.name] = encrypted
                f.type == "password" -> Unit                   // 站点 JS 会 disable 掉，浏览器不提交
                f.name == "username" -> body[f.name] = username
                f.type == "checkbox" && !f.checked -> Unit      // 未勾选不提交
                f.disabled -> Unit
                else -> body[f.name] = f.value
            }
        }

        // 站点的 JS 用 utils.setUrlParam("pwdFromId","?service",…) 把 service 拼到 action 上；
        // 少了这个参数服务端直接 500（踩过的坑）。
        val postUrl = if (form.action.contains("service=")) {
            URL(URL(profile.casBaseUrl), form.action).toString()
        } else {
            profile.casBaseUrl + form.action +
                "?service=" + URLEncoder.encode(profile.ecardServiceUrl, "UTF-8")
        }

        val res = http.request(
            postUrl,
            method = "POST",
            form = body,
            headers = mapOf("Origin" to profile.casBaseUrl, "Referer" to page.url),
        )

        // ⚠️ 这里必须比较 **host**，不能拿整个 URL 去找校园卡域名子串：
        // 登录失败时响应仍停在 CAS 域上，而它的 service 查询参数里就编码了校园卡域名
        // （...%2F%2Fecard.xxx.edu.cn%2F...），用子串判断会把「密码错误」误判成
        // 「登录成功但没取到 token」，让人完全找不到方向。
        if (!isOnEcard(res.url, profile)) {
            val tip = Regex("id=\"showErrorTip\"[^>]*>([\\s\\S]{0,400}?)</span>")
                .find(res.body)?.groupValues?.get(1)
                ?.replace(Regex("<[^>]+>"), "")?.trim()
            throw AuthException(describeLoginFailure(tip, res.status))
        }
        return extractToken(res.url)
            ?: throw AuthException("已跳到校园卡站点但没取到 token，建议退出后重新登录")
    }

    /**
     * 把学校的失败提示翻译成用户能照着做的说明。
     *
     * 学校的风控会临时冻结账号（实测原文：
     * 「该账号已被冻结，预计解冻时间：…，冻结原因：账号频繁访问，可通过账号解禁功能自助解冻」）。
     * 这种情况下照抄原文最有用，但必须补一句「怎么办」——
     * 否则用户只看到一串系统文案，完全不知道该等还是该去改密码。
     */
    internal fun describeLoginFailure(tip: String?, status: Int): String {
        if (tip.isNullOrBlank()) return "登录失败：账号或密码不正确（HTTP $status）"
        val actionable = when {
            tip.contains("冻结") || tip.contains("解禁") ->
                "$tip\n\n这通常是短时间内登录太频繁触发的风控。等到解冻时间后再试即可，" +
                    "也可以去统一身份认证的「账号解禁」自助处理。本 App 的自动同步默认 6 小时一次，不会频繁触发。"
            tip.contains("锁定") ->
                "$tip\n\n账号被锁定，请按学校提示处理后再试。"
            else -> tip
        }
        return "登录失败：$actionable"
    }

    /** 最终地址是否已经落在校园卡站点的 host 上（比较 host，理由见上面登录处的注释） */
    private fun isOnEcard(url: String, profile: SchoolProfile): Boolean {
        val expected = runCatching { URL(profile.ecardBaseUrl).host }.getOrNull() ?: return false
        return runCatching { URL(url).host.equals(expected, ignoreCase = true) }.getOrDefault(false)
    }

    /**
     * 登录页里**没有**账号密码表单时的处理。
     *
     * 这是线上真实报过的 bug：CookieManager 是进程级的，第一次登录成功后 CAS 会话仍然有效，
     * 于是第二次 GET 登录页会被 CAS **直接 302 送回 ecard** 并带上新的 synjones-auth，
     * 页面里根本没有表单。老代码在这里直接抛「找不到 id=pwdFromId 的表单」，
     * 结果就是同一个 App 会话里第二次同步必然失败（用户看到的现象）。
     *
     * 正确做法是复用已有会话：不必再交一次密码（少一次密码交换，对风控也更友好），
     * 同时能拿到一个全新的 token。
     */
    private fun reuseSessionOrFail(page: HttpResponse, profile: SchoolProfile): String {
        extractToken(page.url)?.let { return it }
        throw AuthException(
            if (isOnEcard(page.url, profile)) {
                "登录态异常：已跳到校园卡站点但没取到 token，建议退出后重新登录"
            } else {
                "登录页结构变了：找不到 id=${profile.loginFormId} 的表单（最终地址 ${page.url}）"
            },
        )
    }

    // ------------------------------------------------------------ 卡信息

    fun queryCard(
        http: HttpClient,
        token: String,
        profile: SchoolProfile = SchoolRegistry.default,
    ): CardInfo {
        val res = http.getJson(profile.cardUrl, token)
        return parseCardBody(res.body, res.status)
    }

    /** 纯解析逻辑，便于用真实响应做离线单元测试 */
    internal fun parseCardBody(body: String, status: Int): CardInfo {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw AuthException("卡信息接口返回异常（HTTP $status）")
        val cards = json.optJSONObject("data")?.optJSONArray("card")
        if (cards == null || cards.length() == 0) {
            throw AuthException("没查到校园卡，token 可能已失效，请重新登录")
        }
        val c = cards.getJSONObject(0)
        return CardInfo(
            cardName = c.optString("card_name").ifEmpty { "校园卡" },
            balanceCents = c.optLong("db_balance"),
            sno = c.optString("sno").trim(),
            ownerName = c.optString("name"),
            frozen = c.optInt("freezeflag") == 1,
            lost = c.optInt("lostflag") == 1,
        )
    }

    // ------------------------------------------------------------ 流水

    fun queryTurnover(
        http: HttpClient,
        token: String,
        from: String,
        to: String,
        page: Int = 1,
        size: Int = 100,
        profile: SchoolProfile = SchoolRegistry.default,
    ): TurnoverPage {
        val params = buildString {
            append("timeFrom=").append(from)
            append("&timeTo=").append(to)
            append("&size=").append(size)
            append("&current=").append(page)
            // 缺了这个参数服务端返回 405（中南大学实测）
            profile.synAccessSource?.let { append("&synAccessSource=").append(it) }
        }
        val res = http.getJson("${profile.turnoverUrl}?$params", token)
        return parseTurnoverBody(res.body, res.status)
    }

    /** 纯解析逻辑，便于用真实响应做离线单元测试 */
    internal fun parseTurnoverBody(body: String, status: Int): TurnoverPage {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw AuthException("流水接口返回异常（HTTP $status）")
        if (json.optInt("code", 200) == 401) throw AuthException("登录态已失效，请重新登录")

        val data = json.optJSONObject("data") ?: JSONObject()
        val arr: JSONArray = data.optJSONArray("records") ?: JSONArray()
        val list = ArrayList<TurnoverRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kind = o.optString("turnoverType")
            val typeId = o.optString("typeId")
            // 实测：消费 → typeId=1 / icon=consume；充值 → typeId=2 / icon=recharge
            val isIncome = typeId == "2" ||
                kind.contains("充值") || kind.contains("转入") ||
                kind.contains("退款") || kind.contains("补助") ||
                o.optBoolean("isRefund", false)

            list += TurnoverRecord(
                orderId = o.optString("orderId"),
                timeText = o.optString("effectdateStr")
                    .ifEmpty { o.optString("jndatetimeStr") },
                amountCents = o.optLong("tranamt"),
                isIncome = isIncome,
                kind = kind.ifEmpty { if (isIncome) "收入" else "支出" },
                merchant = cleanMerchant(o.optString("resume"), o.optString("payName")),
                payName = o.optString("payName"),
                balanceCents = o.optLong("cardBalance"),
                toAccount = o.optString("toAccount"),
                raw = o.toString(),
            )
        }
        return TurnoverPage(data.optInt("total", list.size), list)
    }

    /**
     * resume 形如「南校区2食堂2楼白案组-持卡人消费」，去掉后缀才是商户名。
     *
     * 实测有一批记录（宿舍水控等小额消费）的 resume 恰好就是「持卡人消费」，
     * 没有商户前缀。这时不能把「持卡人消费」当成商户名显示给用户，
     * 要回落到更通用的文案。
     */
    internal fun cleanMerchant(resume: String, payName: String): String {
        val trimmed = resume.substringBefore("-持卡人消费").trim()
        if (trimmed.isNotEmpty() && trimmed != "持卡人消费") return trimmed
        if (payName.isNotEmpty() && payName != "持卡人消费") return payName
        return "校园卡消费"
    }
}
