package com.morchid.ecardledger.data.school

import com.morchid.ecardledger.data.AuthException
import com.morchid.ecardledger.data.CardInfo
import com.morchid.ecardledger.data.EcardApi
import com.morchid.ecardledger.data.HttpClient
import com.morchid.ecardledger.data.TurnoverPage

/**
 * 校园卡数据源接口。
 *
 * 上层（同步流程）只依赖这个接口，不关心是哪家厂商、哪个学校。
 *
 * 扩展方式：
 *  - **同厂商新学校** → 不改代码，只在 [SchoolRegistry] 里加一条 [SchoolProfile]
 *  - **不同厂商** → 新写一个实现这个接口的 class，在 [CampusCardProviders.of] 里分支即可
 *
 * 目前唯一已实测的实现是 [SynjonesCampusCardProvider]（新中新一卡通 + 金智统一身份认证）。
 */
interface CampusCardProvider {

    val profile: SchoolProfile

    /** 登录并返回业务 token */
    fun login(http: HttpClient, username: String, password: String): String

    /** 卡信息（余额等） */
    fun queryCard(http: HttpClient, token: String): CardInfo

    /** 流水 */
    fun queryTurnover(
        http: HttpClient,
        token: String,
        from: String,
        to: String,
        page: Int = 1,
        size: Int = 100,
    ): TurnoverPage
}

/**
 * 新中新一卡通 + 金智统一身份认证的通用实现。
 * 中南大学用的就是这一套（已实测）；同样用这套的学校换一份 [SchoolProfile] 即可。
 */
class SynjonesCampusCardProvider(override val profile: SchoolProfile) : CampusCardProvider {

    override fun login(http: HttpClient, username: String, password: String): String =
        EcardApi.login(http, username, password, profile)

    override fun queryCard(http: HttpClient, token: String): CardInfo =
        EcardApi.queryCard(http, token, profile)

    override fun queryTurnover(
        http: HttpClient,
        token: String,
        from: String,
        to: String,
        page: Int,
        size: Int,
    ): TurnoverPage = EcardApi.queryTurnover(http, token, from, to, page, size, profile)
}

object CampusCardProviders {

    /** 未知学校时回落到默认学校（中南大学），避免直接崩 */
    fun of(schoolId: String?): CampusCardProvider {
        val profile = SchoolRegistry.byId(schoolId ?: SchoolRegistry.default.id)
            ?: SchoolRegistry.default
        return when (profile.id) {
            // 将来接入别的厂商时在这里加分支，例如：
            // "some-other-vendor" -> OtherVendorProvider(profile)
            else -> SynjonesCampusCardProvider(profile)
        }
    }

    fun byProfile(profile: SchoolProfile): CampusCardProvider =
        SynjonesCampusCardProvider(profile)
}

/** 让调用方拿到更明确的错误信息，而不是空指针 */
internal fun SchoolProfile.requireValid(): SchoolProfile {
    if (casBaseUrl.isBlank() || ecardBaseUrl.isBlank() || turnoverUrl.isBlank()) {
        throw AuthException("学校配置不完整：${displayName}（$id）")
    }
    return this
}
