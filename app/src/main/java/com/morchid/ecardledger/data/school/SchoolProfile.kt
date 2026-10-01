package com.morchid.ecardledger.data.school

/**
 * 一所学校的校园卡接入配置。
 *
 * 为什么要抽这一层：校园卡系统的差异基本集中在「地址 + 路径 + 表单/字段名」。
 * 把差异收敛成一个数据类之后：
 *  - 同一厂商（新中新 一卡通 + 金智 authserver 统一身份认证）的学校，
 *    通常**只需要新增一条配置**，代码不用改
 *  - 完全不同的厂商，则实现 [CampusCardProvider] 接口（新的 class），
 *    在 [SchoolRegistry] 里注册即可，上层业务流程不用动
 *
 * ⚠️ 除中南大学外，其余学校的取值为**推测性的默认值，未经验证**。
 *    接入新学校时必须用真实账号实测，并把踩到的差异记进 [notes]。
 */
/**
 * 学校的登录方式。
 *
 * 这决定了 **App 能不能替你登录** —— 不是实现细节，是能不能做的分界线：
 * 账号密码表单可以代填，微信授权必须本人在微信里点，第三方 App 拿不到凭据。
 */
enum class LoginKind {
    /** 账号密码表单（金智 authserver 那种），App 可以代填 */
    CAS_FORM,

    /** 微信公众号网页授权，App **无法**代劳 */
    WECHAT_OAUTH,
}

data class SchoolProfile(
    val id: String,
    val displayName: String,

    /** 登录方式。不是 [LoginKind.CAS_FORM] 的，App 目前登不进去，别让用户白填一遍 */
    val loginKind: LoginKind = LoginKind.CAS_FORM,

    // ---------------- 统一身份认证（金智 authserver，国内高校大量在用） ----------------

    /** 认证服务器根地址，例如 https://ca.csu.edu.cn */
    val casBaseUrl: String,
    /** 登录页路径，金智默认 /authserver/login */
    val casLoginPath: String = "/authserver/login",
    /**
     * 账号密码表单的 id。中南大学是 pwdFromId。
     * ⚠️ 这一项极其关键：登录页里可能有多个表单共用同一个 name，
     * 抓错表单会得到 401「认证失败」而且不计入密码错误次数，非常难查（我们踩过）。
     */
    val loginFormId: String = "pwdFromId",

    // ---------------- 校园卡站点 ----------------

    /** 校园卡站点根地址，例如 https://ecard.csu.edu.cn */
    val ecardBaseUrl: String,
    /** CAS 登录成功后回调到校园卡的服务地址（service 参数） */
    val ecardServiceUrl: String,
    /** 卡信息接口（含查询参数） */
    val cardUrl: String,
    /** 流水接口（不含查询参数） */
    val turnoverUrl: String,
    /** 流水接口的 synAccessSource 取值；有些部署不需要，置 null */
    val synAccessSource: String? = "pc",

    /**
     * 学校所在时区。服务端返回的时间字符串不带时区，必须按这个时区解析，
     * 否则设备时区一变，同步记录的时间戳就整体偏移（详见 [SchoolTime]）。
     */
    val timeZoneId: String = "Asia/Shanghai",

    /** 适配这所学校时踩过的坑、待确认项，写给后来者 */
    val notes: String = "",
) {
    val casLoginUrl: String
        get() = casBaseUrl + casLoginPath + "?service=" +
            java.net.URLEncoder.encode(ecardServiceUrl, "UTF-8")
}

/**
 * 学校注册表。
 *
 * 目前只有中南大学是**实测通过**的；其余条目要么是用户自定义（未验证），
 * 要么等有了真实账号再补。
 */
object SchoolRegistry {

    /** 中南大学（新中新一卡通 + 金智 CAS）—— 已实测：登录、卡信息、流水、字段含义 */
    val CSU = SchoolProfile(
        id = "csu",
        displayName = "中南大学",
        casBaseUrl = "https://ca.csu.edu.cn",
        casLoginPath = "/authserver/login",
        loginFormId = "pwdFromId",
        ecardBaseUrl = "https://ecard.csu.edu.cn",
        ecardServiceUrl = "https://ecard.csu.edu.cn/berserker-auth/cas/login/wisedu" +
            "?targetUrl=https://ecard.csu.edu.cn/plat-pc/?name=loginTransit",
        cardUrl = "https://ecard.csu.edu.cn/berserker-app/ykt/tsm/queryCard" +
            "?scene=recharge&synAccessSource=pc",
        turnoverUrl = "https://ecard.csu.edu.cn/berserker-search/search/personal/turnover",
        synAccessSource = "pc",
        notes = "实测：tranamt/cardBalance 单位是分；typeId=1 消费(支出)、typeId=2 充值(收入)；" +
            "cllt 必须取 pwdFromId 表单里的 userNameLogin；POST 必须带 service 参数。",
    )

    val all: List<SchoolProfile> = listOf(CSU)

    /** 接受 null 是为了让「meta 里还没存学校」这种正常情况不必到处写 ?. 或 orEmpty() */
    fun byId(id: String?): SchoolProfile? = all.firstOrNull { it.id == id }

    /** 默认学校 */
    val default: SchoolProfile get() = CSU

    /**
     * 用户自己填的学校配置（未验证）。用于「接入其他学校」时先跑一遍看能不能通，
     * 通了再考虑固化进 [all]。
     */
    fun custom(
        id: String,
        displayName: String,
        casBaseUrl: String,
        ecardBaseUrl: String,
        ecardServiceUrl: String,
        cardUrl: String,
        turnoverUrl: String,
        loginFormId: String = "pwdFromId",
        casLoginPath: String = "/authserver/login",
        synAccessSource: String? = "pc",
        timeZoneId: String = "Asia/Shanghai",
        notes: String = "用户自定义，未验证",
    ) = SchoolProfile(
        id = id,
        displayName = displayName,
        casBaseUrl = casBaseUrl,
        casLoginPath = casLoginPath,
        loginFormId = loginFormId,
        ecardBaseUrl = ecardBaseUrl,
        ecardServiceUrl = ecardServiceUrl,
        cardUrl = cardUrl,
        turnoverUrl = turnoverUrl,
        synAccessSource = synAccessSource,
        timeZoneId = timeZoneId,
        notes = notes,
    )

    /**
     * 接入一所新学校时要填写/确认的清单。
     * 直接显示在 App 的「接入其他学校」帮助页里，用户照着抓包填。
     */
    val ADAPTATION_CHECKLIST: List<String> = listOf(
        "1. 统一身份认证登录页地址（一般是 https://ca.学校域名/authserver/login）",
        "2. 登录页里账号密码表单的 id（中南大学是 pwdFromId，必须先确认，抓错表单会得到误导性的「认证失败」）",
        "3. 校园卡站点地址，以及 CAS 登录成功后的回调 service 地址",
        "4. 卡信息接口的完整地址（用于取余额）",
        "5. 流水接口地址，以及日期参数格式（中南大学用 YYYY-MM-DD）",
        "6. 流水响应里各字段的含义：金额单位、收支方向字段、时间字段、商户字段、唯一流水号",
        "7. 是否要求额外的 synAccessSource 之类的参数（缺了会 405）",
    )
}
