package edu.sjtu.agent.auth

import android.content.Context
import android.content.Intent
import android.net.Uri

object AuthSessionManager {
    fun intentFor(context: Context, profile: LoginProfile): Intent =
        Intent(context, WebAuthActivity::class.java).apply {
            putExtra(WebAuthActivity.EXTRA_SCOPE, profile.scope)
            putExtra(WebAuthActivity.EXTRA_START_URL, profile.startUrl)
            putExtra(WebAuthActivity.EXTRA_DOMAINS, profile.allowedDomains.toTypedArray())
            putExtra(WebAuthActivity.EXTRA_SUCCESS_HINT, profile.successHint)
        }

    fun canvasTokenIntent(context: Context, baseUrl: String): Intent {
        val normalized = baseUrl.trim().ifBlank { "https://oc.sjtu.edu.cn" }.trimEnd('/')
        val host = Uri.parse(normalized).host.orEmpty()
        return Intent(context, WebAuthActivity::class.java).apply {
            putExtra(WebAuthActivity.EXTRA_SCOPE, "canvas")
            putExtra(WebAuthActivity.EXTRA_START_URL, "$normalized/login/openid_connect")
            putExtra(WebAuthActivity.EXTRA_DOMAINS, arrayOf(host, "jaccount.sjtu.edu.cn"))
            putExtra(WebAuthActivity.EXTRA_SUCCESS_HINT, host)
            putExtra(WebAuthActivity.EXTRA_CANVAS_TOKEN_MODE, true)
            putExtra(WebAuthActivity.EXTRA_CANVAS_BASE_URL, normalized)
        }
    }
}

data class LoginProfile(
    val scope: String,
    val title: String,
    val startUrl: String,
    val allowedDomains: List<String>,
    val successHint: String,
)

val LoginProfiles = listOf(
    LoginProfile(
        scope = "aihaoke",
        title = "AI 好课",
        startUrl = "https://sjtu.aihaoke.net/login",
        allowedDomains = listOf("sjtu.aihaoke.net", "jaccount.sjtu.edu.cn"),
        successHint = "student",
    ),
    LoginProfile(
        scope = "jwxt",
        title = "教务系统",
        startUrl = "https://i.sjtu.edu.cn/jaccountlogin",
        allowedDomains = listOf("i.sjtu.edu.cn", "jaccount.sjtu.edu.cn"),
        successHint = "i.sjtu.edu.cn",
    ),
    LoginProfile(
        scope = "phycai",
        title = "物理实验",
        startUrl = "http://www.phycai.sjtu.edu.cn/pe/Jlogin.aspx",
        allowedDomains = listOf("www.phycai.sjtu.edu.cn", "phycai.sjtu.edu.cn", "jaccount.sjtu.edu.cn"),
        successHint = "student",
    ),
    LoginProfile(
        scope = "icourse",
        title = "中国大学 MOOC",
        startUrl = "https://www.icourse163.org/",
        allowedDomains = listOf("www.icourse163.org", "icourse163.org", "reg.icourse163.org"),
        successHint = "icourse163.org",
    ),
    LoginProfile(
        scope = "shuiyuan",
        title = "水源社区",
        startUrl = "https://shuiyuan.sjtu.edu.cn/",
        allowedDomains = listOf("shuiyuan.sjtu.edu.cn"),
        successHint = "shuiyuan.sjtu.edu.cn",
    ),
    LoginProfile(
        scope = "dyweb",
        title = "传承·交大",
        startUrl = "https://share.dyweb.sjtu.cn/",
        allowedDomains = listOf("share.dyweb.sjtu.cn", "jaccount.sjtu.edu.cn"),
        successHint = "share.dyweb.sjtu.cn",
    ),
)
