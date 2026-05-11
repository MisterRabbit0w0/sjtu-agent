package edu.sjtu.agent.auth

import android.annotation.SuppressLint
import android.os.Bundle
import android.text.InputType
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import edu.sjtu.agent.data.local.EncryptedCredentialStore
import edu.sjtu.agent.data.AgentResult
import edu.sjtu.agent.network.AihaokeClient
import edu.sjtu.agent.network.CampusSearchClient
import edu.sjtu.agent.network.CanvasClient
import edu.sjtu.agent.network.IcourseClient
import edu.sjtu.agent.network.JwxtClient
import edu.sjtu.agent.network.PhycaiClient
import edu.sjtu.agent.network.defaultHttpClient
import edu.sjtu.agent.network.parseCookieHeader
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

class WebAuthActivity : ComponentActivity() {
    private lateinit var store: EncryptedCredentialStore
    private lateinit var scope: String
    private lateinit var domains: Array<String>
    private lateinit var status: TextView
    private lateinit var webView: WebView
    private var canvasTokenInput: EditText? = null
    private val http by lazy { defaultHttpClient() }
    private val canvasClient by lazy { CanvasClient(http) }
    private val aihaokeClient by lazy { AihaokeClient(http) }
    private val jwxtClient by lazy { JwxtClient(http) }
    private val phycaiClient by lazy { PhycaiClient(http) }
    private val icourseClient by lazy { IcourseClient(http) }
    private val searchClient by lazy { CampusSearchClient(http) }
    private var canvasTokenMode: Boolean = false
    private var canvasBaseUrl: String = ""
    private var jumpedToCanvasSettings: Boolean = false
    private var jumpedToAihaokeCourse: Boolean = false
    private var clickedIcourseLogin: Boolean = false
    private var clickedDywebLogin: Boolean = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = EncryptedCredentialStore(this)
        scope = intent.getStringExtra(EXTRA_SCOPE).orEmpty()
        val startUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        val successHint = intent.getStringExtra(EXTRA_SUCCESS_HINT).orEmpty()
        canvasTokenMode = intent.getBooleanExtra(EXTRA_CANVAS_TOKEN_MODE, false)
        canvasBaseUrl = intent.getStringExtra(EXTRA_CANVAS_BASE_URL).orEmpty().trimEnd('/')
        domains = intent.getStringArrayExtra(EXTRA_DOMAINS) ?: emptyArray()

        CookieManager.getInstance().setAcceptCookie(true)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        status = TextView(this).apply {
            text = if (canvasTokenMode) "登录 Canvas 后生成 Access Token，再点「保存 Token」" else "登录完成后点「保存会话」"
            setPadding(28, 20, 28, 12)
        }
        if (canvasTokenMode) {
            canvasTokenInput = EditText(this).apply {
                hint = "可粘贴 Canvas Token"
                minLines = 1
                maxLines = 3
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                setPadding(28, 8, 28, 8)
            }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 0, 20, 12)
        }
        val save = Button(this).apply {
            text = if (canvasTokenMode) "保存 Token" else "保存会话"
            setOnClickListener {
                when {
                    canvasTokenMode -> saveCanvasTokenAndFinish()
                    scope == "aihaoke" -> saveAihaokeSessionAndFinish()
                    scope == "jwxt" -> saveJwxtSessionAndFinish()
                    scope == "phycai" -> savePhycaiSessionAndFinish()
                    scope == "icourse" -> saveIcourseSessionAndFinish()
                    scope == "shuiyuan" -> saveShuiyuanSessionAndFinish()
                    scope == "dyweb" -> saveDywebSessionAndFinish()
                    else -> saveCookiesAndFinish()
                }
            }
        }
        val autoCreateButton = Button(this).apply {
            text = "自动生成"
            setOnClickListener { autoCreateCanvasToken() }
        }
        val settingsButton = Button(this).apply {
            text = "设置页"
            setOnClickListener { openCanvasSettings() }
        }
        val close = Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        }
        actions.addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (canvasTokenMode) {
            actions.addView(autoCreateButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(settingsButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        actions.addView(close, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (canvasTokenMode) {
                        handleCanvasPageFinished(url)
                    } else {
                        handlePlatformPageFinished(url, successHint)
                    }
                }
            }
        }

        root.addView(status)
        canvasTokenInput?.let {
            root.addView(it, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
        }
        root.addView(actions)
        root.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f,
        ))
        setContentView(root)
        if (startUrl.isNotBlank()) webView.loadUrl(startUrl)
    }

    private fun saveCookiesAndFinish() {
        lifecycleScope.launch {
            val cookies = collectCookiesForDomains(domains.toList())
            store.putCookies(scope, cookies)
            if (scope == "dyweb") {
                cookies["sjtu_token"]?.let { store.putSecret("dyweb_token", it) }
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun saveAihaokeSessionAndFinish() {
        status.text = "正在从 AI 好课会话识别 haoke-token"
        webView.evaluateJavascript(AihaokeTokenScrapeScript) { raw ->
            val scriptCookies = parseScriptCookieMap(raw)
            lifecycleScope.launch {
                val cookies = (collectAihaokeCookies() + scriptCookies)
                    .mapValues { (key, value) ->
                        if (key == "haoke-token") value.cleanBearerToken() else value.trim()
                    }
                    .filterValues { it.isNotBlank() }
                val token = cookies["haoke-token"].orEmpty().cleanBearerToken()
                if (token.isBlank()) {
                    status.text = "未获取到 AI 好课 haoke-token，请确认已通过统一身份认证进入学生课程页后再保存"
                    return@launch
                }
                val normalizedCookies = cookies + ("haoke-token" to token)
                when (val validation = aihaokeClient.validateToken(token, normalizedCookies)) {
                    is AgentResult.Error -> {
                        status.text = validation.message
                        return@launch
                    }
                    is AgentResult.Ok -> Unit
                }
                store.putCookies("aihaoke", normalizedCookies)
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun saveJwxtSessionAndFinish() {
        lifecycleScope.launch {
            status.text = "正在校验教务系统 session"
            val cookies = collectJwxtCookies()
            if (cookies.isEmpty()) {
                status.text = "未获取到教务系统 Cookie，请确认已进入 i.sjtu.edu.cn 后再保存"
                return@launch
            }
            when (val validation = jwxtClient.validateCookies(cookies)) {
                is AgentResult.Error -> {
                    status.text = validation.message
                    return@launch
                }
                is AgentResult.Ok -> Unit
            }
            saveJaccountCookiesIfPresent()
            store.putCookies("jwxt", cookies)
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun savePhycaiSessionAndFinish() {
        lifecycleScope.launch {
            status.text = "正在校验物理实验 session"
            val cookies = collectPhycaiCookies()
            if (cookies.isEmpty()) {
                status.text = "未获取到物理实验 Cookie，请确认已通过 jAccount 进入实验页面"
                return@launch
            }
            when (val validation = phycaiClient.validateCookies(cookies)) {
                is AgentResult.Error -> {
                    status.text = validation.message
                    return@launch
                }
                is AgentResult.Ok -> Unit
            }
            saveJaccountCookiesIfPresent()
            store.putCookies("phycai", cookies)
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun saveIcourseSessionAndFinish() {
        lifecycleScope.launch {
            status.text = "正在校验中国大学 MOOC session"
            val cookies = collectIcourseCookies()
            when (val validation = icourseClient.validateCookies(cookies)) {
                is AgentResult.Error -> {
                    status.text = validation.message
                    return@launch
                }
                is AgentResult.Ok -> Unit
            }
            store.putCookies("icourse", cookies)
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun saveShuiyuanSessionAndFinish() {
        lifecycleScope.launch {
            status.text = "正在校验水源社区 session"
            val cookies = collectShuiyuanCookies()
            if (cookies.isEmpty()) {
                status.text = "未获取到水源社区 Cookie，请确认已通过 jAccount 进入水源"
                return@launch
            }
            when (val validation = searchClient.validateShuiyuanCookies(cookies)) {
                is AgentResult.Error -> {
                    status.text = validation.message
                    return@launch
                }
                is AgentResult.Ok -> Unit
            }
            saveJaccountCookiesIfPresent()
            store.putCookies("shuiyuan", cookies)
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun saveDywebSessionAndFinish() {
        status.text = "正在从传承·交大会话识别 sjtu_token"
        webView.evaluateJavascript(DywebTokenScrapeScript) { raw ->
            val scriptValues = parseScriptCookieMap(raw)
            lifecycleScope.launch {
                val cookies = (collectDywebCookies() + scriptValues).cleanCookieValues()
                val token = cookies["sjtu_token"].orEmpty().trim()
                if (token.isBlank()) {
                    status.text = "未获取到传承·交大 sjtu_token，请确认已点击「使用 jAccount 登录」并进入站点"
                    return@launch
                }
                when (val validation = searchClient.validateDywebToken(token)) {
                    is AgentResult.Error -> {
                        status.text = validation.message
                        return@launch
                    }
                    is AgentResult.Ok -> Unit
                }
                saveJaccountCookiesIfPresent()
                store.putCookies("dyweb", cookies + ("sjtu_token" to token))
                store.putSecret("dyweb_token", token)
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun saveCanvasTokenAndFinish() {
        val pasted = canvasTokenInput?.text?.toString()?.trim().orEmpty()
        if (pasted.isNotBlank()) {
            persistCanvasToken(pasted)
            return
        }

        status.text = "正在从当前页面识别 Token"
        webView.evaluateJavascript(CanvasTokenScrapeScript) { raw ->
            val pageText = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            val token = extractCanvasToken(pageText)
            if (token.isBlank()) {
                status.text = "未识别到 Token，请复制页面显示的 Access Token 到上方输入框"
            } else {
                persistCanvasToken(token)
            }
        }
    }

    private fun persistCanvasToken(token: String) {
        lifecycleScope.launch {
            val cleanToken = token.cleanBearerToken()
            if (cleanToken.isBlank()) {
                status.text = "Token 为空，请重新复制 Canvas Access Token"
                return@launch
            }
            val baseUrl = canvasBaseUrl.ifBlank { "https://oc.sjtu.edu.cn" }
            when (val validation = canvasClient.validateToken(baseUrl, cleanToken)) {
                is AgentResult.Error -> {
                    status.text = validation.message
                    return@launch
                }
                is AgentResult.Ok -> Unit
            }
            store.putSecret("canvas_token", cleanToken)
            if (canvasBaseUrl.isNotBlank()) {
                store.saveSettings(store.getSettings().copy(canvasBaseUrl = canvasBaseUrl))
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun autoCreateCanvasToken() {
        if (!canvasTokenMode) return
        val currentUrl = webView.url.orEmpty()
        val currentPath = runCatching { Uri.parse(currentUrl).path.orEmpty() }.getOrDefault("")
        if (currentPath != "/profile/settings") {
            status.text = "正在打开 Canvas Token 设置页"
            openCanvasSettings()
            webView.postDelayed({ autoCreateCanvasToken() }, 2500)
            return
        }

        status.text = "正在按 Windows 端流程创建 Canvas Token"
        webView.evaluateJavascript(canvasTokenCreateScript("SJTU Agent Android")) { raw ->
            val result = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
            if (result == "NO_CREATE_BUTTON") {
                status.text = "未找到 New Access Token，请确认已登录并在设置页下方"
                return@evaluateJavascript
            }
            status.text = "已触发生成，正在读取弹出的 Token"
            webView.postDelayed({ saveCanvasTokenAndFinish() }, 3500)
        }
    }

    private fun extractCanvasToken(pageText: String): String =
        CanvasTokenPreferredRegex.find(pageText)?.value
            ?: CanvasTokenFallbackRegex.find(pageText)?.value
            ?: ""

    private fun handleCanvasPageFinished(url: String) {
        val current = Uri.parse(url)
        val canvasHost = Uri.parse(canvasBaseUrl).host.orEmpty()
        val isCanvasHost = current.host.equals(canvasHost, ignoreCase = true)
        val path = current.path.orEmpty()

        if (isCanvasHost && path != "/profile/settings" && !path.startsWith("/login/") && !jumpedToCanvasSettings) {
            jumpedToCanvasSettings = true
            status.text = "Canvas 已登录，正在打开 Token 设置页"
            openCanvasSettings()
            return
        }

        status.text = when {
            isCanvasHost && path == "/profile/settings" ->
                "在页面底部点击 New Access Token，生成后复制 token，或直接点「保存 Token」尝试识别"
            path.startsWith("/login/") ->
                "请先用 jAccount 登录 Canvas"
            else ->
                "当前页面：$url"
        }
    }

    private fun openCanvasSettings() {
        val base = canvasBaseUrl.ifBlank { "https://oc.sjtu.edu.cn" }
        webView.loadUrl("$base/profile/settings#access_tokens")
    }

    private fun handlePlatformPageFinished(url: String, successHint: String) {
        when (scope) {
            "aihaoke" -> handleAihaokePageFinished(url)
            "jwxt" -> handleJwxtPageFinished(url)
            "phycai" -> handlePhycaiPageFinished(url)
            "icourse" -> handleIcoursePageFinished(url)
            "shuiyuan" -> handleShuiyuanPageFinished(url)
            "dyweb" -> handleDywebPageFinished(url)
            else -> {
                status.text = if (successHint.isNotBlank() && url.contains(successHint, ignoreCase = true)) {
                    "看起来已进入目标站点，可保存会话"
                } else {
                    "当前页面：$url"
                }
            }
        }
    }

    private fun handleAihaokePageFinished(url: String) {
        val current = Uri.parse(url)
        val host = current.host.orEmpty()
        val path = current.path.orEmpty()
        val isAihaokeHost = host.equals("sjtu.aihaoke.net", ignoreCase = true)
        status.text = when {
            isAihaokeHost && path == "/login" -> {
                webView.evaluateJavascript(AihaokeSsoClickScript, null)
                "正在打开 AI 好课统一身份认证入口"
            }
            host.contains("jaccount", ignoreCase = true) ->
                "请完成 jAccount 登录"
            isAihaokeHost && path.startsWith("/student") && path != "/student/course" && !jumpedToAihaokeCourse -> {
                jumpedToAihaokeCourse = true
                webView.loadUrl("https://sjtu.aihaoke.net/student/course")
                "AI 好课已登录，正在打开课程页同步 haoke-token"
            }
            isAihaokeHost && path.startsWith("/student") ->
                "AI 好课已进入学生页，可保存会话"
            else ->
                "当前页面：$url"
        }
    }

    private fun handleJwxtPageFinished(url: String) {
        val host = Uri.parse(url).host.orEmpty()
        status.text = when {
            host.contains("jaccount", ignoreCase = true) -> "请完成 jAccount 登录"
            host.equals("i.sjtu.edu.cn", ignoreCase = true) -> "教务系统已进入，可保存会话"
            else -> "当前页面：$url"
        }
    }

    private fun handlePhycaiPageFinished(url: String) {
        val current = Uri.parse(url)
        val host = current.host.orEmpty()
        val path = current.path.orEmpty()
        status.text = when {
            host.contains("jaccount", ignoreCase = true) -> "请完成 jAccount 登录"
            host.contains("phycai.sjtu.edu.cn", ignoreCase = true) && path != "/pe/student/select.aspx" -> {
                webView.loadUrl("http://www.phycai.sjtu.edu.cn/pe/student/select.aspx")
                "物理实验已登录，正在打开实验预约页"
            }
            host.contains("phycai.sjtu.edu.cn", ignoreCase = true) -> "物理实验已进入预约页，可保存会话"
            else -> "当前页面：$url"
        }
    }

    private fun handleIcoursePageFinished(url: String) {
        val host = Uri.parse(url).host.orEmpty()
        status.text = when {
            host.contains("reg.icourse163.org", ignoreCase = true) -> "请在 MOOC 登录框中完成登录"
            host.contains("icourse163.org", ignoreCase = true) && !clickedIcourseLogin -> {
                clickedIcourseLogin = true
                webView.evaluateJavascript(IcourseLoginClickScript, null)
                "正在打开中国大学 MOOC 登录入口"
            }
            host.contains("icourse163.org", ignoreCase = true) -> "中国大学 MOOC 页面已打开，登录成功后可保存会话"
            else -> "当前页面：$url"
        }
    }

    private fun handleShuiyuanPageFinished(url: String) {
        val host = Uri.parse(url).host.orEmpty()
        status.text = when {
            host.contains("jaccount", ignoreCase = true) -> "请完成 jAccount 登录"
            host.equals("shuiyuan.sjtu.edu.cn", ignoreCase = true) -> "水源社区已进入，可保存会话"
            else -> "当前页面：$url"
        }
    }

    private fun handleDywebPageFinished(url: String) {
        val host = Uri.parse(url).host.orEmpty()
        status.text = when {
            host.contains("jaccount", ignoreCase = true) -> "请完成 jAccount 登录"
            host.equals("share.dyweb.sjtu.cn", ignoreCase = true) && !clickedDywebLogin -> {
                clickedDywebLogin = true
                webView.evaluateJavascript(DywebLoginClickScript, null)
                "正在打开传承·交大 jAccount 登录入口"
            }
            host.equals("share.dyweb.sjtu.cn", ignoreCase = true) -> "传承·交大已进入，登录成功后可保存会话"
            else -> "当前页面：$url"
        }
    }

    private fun collectAihaokeCookies(): Map<String, String> =
        collectCookiesForUrls(
            listOf(
                "https://sjtu.aihaoke.net/",
                "https://sjtu.aihaoke.net/login",
                "https://sjtu.aihaoke.net/student",
                "https://sjtu.aihaoke.net/student/course",
                "https://aihaoke.net/",
            ),
        )

    private fun collectJwxtCookies(): Map<String, String> =
        collectCookiesForDomains(listOf("i.sjtu.edu.cn")).cleanCookieValues()

    private fun collectPhycaiCookies(): Map<String, String> =
        collectCookiesForDomains(listOf("www.phycai.sjtu.edu.cn", "phycai.sjtu.edu.cn")).cleanCookieValues()

    private fun collectIcourseCookies(): Map<String, String> =
        collectCookiesForUrls(
            listOf(
                "https://www.icourse163.org/",
                "https://icourse163.org/",
                "https://reg.icourse163.org/",
            ),
        ).cleanCookieValues()

    private fun collectShuiyuanCookies(): Map<String, String> =
        collectCookiesForDomains(listOf("shuiyuan.sjtu.edu.cn")).cleanCookieValues()

    private fun collectDywebCookies(): Map<String, String> =
        collectCookiesForDomains(listOf("share.dyweb.sjtu.cn")).cleanCookieValues()

    private suspend fun saveJaccountCookiesIfPresent() {
        val cookies = collectCookiesForDomains(listOf("jaccount.sjtu.edu.cn")).cleanCookieValues()
        if (cookies.isNotEmpty()) {
            store.putCookies("jaccount", cookies)
        }
    }

    private fun collectCookiesForDomains(domains: List<String>): Map<String, String> =
        collectCookiesForUrls(domains.flatMap { domain ->
            listOf("https://$domain/", "http://$domain/")
        })

    private fun collectCookiesForUrls(urls: List<String>): Map<String, String> =
        urls.flatMap { url ->
            parseCookieHeader(CookieManager.getInstance().getCookie(url)).entries
        }.associate { it.key to it.value }

    private fun parseScriptCookieMap(raw: String): Map<String, String> {
        val text = runCatching { JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
        if (text.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(text)
            obj.keys().asSequence().mapNotNull { key ->
                val value = obj.optString(key).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    private fun Map<String, String>.cleanCookieValues(): Map<String, String> =
        mapNotNull { (key, value) ->
            val cleanKey = key.trim()
            val cleanValue = value.trim()
            if (cleanKey.isBlank() || cleanValue.isBlank()) null else cleanKey to cleanValue
        }.toMap()

    companion object {
        const val EXTRA_SCOPE = "scope"
        const val EXTRA_START_URL = "start_url"
        const val EXTRA_DOMAINS = "domains"
        const val EXTRA_SUCCESS_HINT = "success_hint"
        const val EXTRA_CANVAS_TOKEN_MODE = "canvas_token_mode"
        const val EXTRA_CANVAS_BASE_URL = "canvas_base_url"

        private val CanvasTokenPreferredRegex = Regex("""\b\d+~[A-Za-z0-9_-]{20,}\b""")
        private val CanvasTokenFallbackRegex = Regex("""\b[A-Za-z0-9_-]{40,}\b""")
        private val AihaokeSsoClickScript = """
            (() => {
              const norm = (s) => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
              const tabs = Array.from(document.querySelectorAll('.login-type-tabs li'));
              if (tabs.length) {
                tabs[tabs.length - 1].click();
                return 'CLICKED_LAST_TAB';
              }
              const clickables = Array.from(document.querySelectorAll('button, a, [role="button"], li, div'));
              const target = clickables.find((el) => {
                const text = norm(el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || '');
                return text.includes('统一身份认证') || text.includes('jaccount') || text.includes('single sign-on') || text.includes('sso');
              });
              if (target) {
                target.click();
                return 'CLICKED_TEXT_MATCH';
              }
              return 'NO_SSO_ENTRY';
            })()
        """.trimIndent()
        private val AihaokeTokenScrapeScript = """
            (() => {
              const out = {};
              const readPairs = (text) => {
                (text || '').split(';').forEach((pair) => {
                  const idx = pair.indexOf('=');
                  if (idx <= 0) return;
                  const key = pair.slice(0, idx).trim();
                  const value = pair.slice(idx + 1).trim();
                  if (key && value) out[key] = value;
                });
              };
              readPairs(document.cookie || '');
              const readStorage = (storage) => {
                if (!storage) return;
                for (let i = 0; i < storage.length; i += 1) {
                  const key = storage.key(i);
                  const value = storage.getItem(key);
                  if (key && value && /haoke-token|token/i.test(key)) out[key] = value;
                }
              };
              try { readStorage(window.localStorage); } catch (_) {}
              try { readStorage(window.sessionStorage); } catch (_) {}
              return JSON.stringify(out);
            })()
        """.trimIndent()
        private val IcourseLoginClickScript = """
            (() => {
              const norm = (s) => (s || '').replace(/\s+/g, ' ').trim();
              const visible = (el) => {
                const rect = el.getBoundingClientRect();
                const style = window.getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
              };
              const candidates = Array.from(document.querySelectorAll('a, button, [role="button"], span, div'));
              const login = candidates.find((el) => visible(el) && /登录|登 录|login/i.test(norm(el.innerText || el.textContent || el.value || '')));
              if (login) {
                login.click();
                return 'CLICKED_LOGIN';
              }
              return 'NO_LOGIN_BUTTON';
            })()
        """.trimIndent()
        private val DywebLoginClickScript = """
            (() => {
              const norm = (s) => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
              const visible = (el) => {
                const rect = el.getBoundingClientRect();
                const style = window.getComputedStyle(el);
                return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
              };
              const candidates = Array.from(document.querySelectorAll('a, button, [role="button"], span, div'));
              const login = candidates.find((el) => {
                const text = norm(el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || '');
                return text.includes('使用 jaccount 登录') || text.includes('jaccount') || text.includes('登录');
              });
              if (login) {
                login.click();
                return 'CLICKED_LOGIN';
              }
              return 'NO_LOGIN_BUTTON';
            })()
        """.trimIndent()
        private val DywebTokenScrapeScript = """
            (() => {
              const out = {};
              const readPairs = (text) => {
                (text || '').split(';').forEach((pair) => {
                  const idx = pair.indexOf('=');
                  if (idx <= 0) return;
                  const key = pair.slice(0, idx).trim();
                  const value = pair.slice(idx + 1).trim();
                  if (key && value) out[key] = value;
                });
              };
              readPairs(document.cookie || '');
              const readStorage = (storage) => {
                if (!storage) return;
                for (let i = 0; i < storage.length; i += 1) {
                  const key = storage.key(i);
                  const value = storage.getItem(key);
                  if (key && value && /sjtu_token|token/i.test(key)) out[key] = value;
                }
              };
              try { readStorage(window.localStorage); } catch (_) {}
              try { readStorage(window.sessionStorage); } catch (_) {}
              return JSON.stringify(out);
            })()
        """.trimIndent()
        private val CanvasTokenScrapeScript = """
            (() => {
              const values = [];
              document.querySelectorAll('input, textarea, code, pre, samp, kbd, dialog, .ui-dialog-content, .ReactModal__Content, .modal').forEach((el) => {
                const value = el.value || el.innerText || el.textContent || '';
                if (value) values.push(value);
              });
              return values.join('\n');
            })()
        """.trimIndent()

        private fun canvasTokenCreateScript(purpose: String): String {
            val quotedPurpose = JSONObject.quote(purpose)
            return """
                (() => {
                  const norm = (s) => (s || '').replace(/\s+/g, ' ').trim().toLowerCase();
                  const visible = (el) => {
                    const rect = el.getBoundingClientRect();
                    const style = window.getComputedStyle(el);
                    return rect.width > 0 && rect.height > 0 && style.visibility !== 'hidden' && style.display !== 'none';
                  };
                  const textOf = (el) => norm(el.innerText || el.textContent || el.value || el.getAttribute('aria-label') || el.title || '');
                  const createNeedles = [
                    'new access token',
                    '+ new access token',
                    '新增访问令牌',
                    '+ 新增访问令牌',
                    '创建新访问许可证',
                    '+ 创建新访问许可证'
                  ].map(norm);
                  const clickable = Array.from(document.querySelectorAll('button, a, [role="button"], input[type="button"], input[type="submit"]'));
                  const createButton = clickable.find((el) => visible(el) && createNeedles.some((needle) => textOf(el).includes(needle)));
                  if (!createButton) return 'NO_CREATE_BUTTON';
                  createButton.click();

                  setTimeout(() => {
                    const fields = Array.from(document.querySelectorAll('input, textarea')).filter((el) => {
                      const type = norm(el.getAttribute('type') || '');
                      return visible(el) && !el.disabled && !el.readOnly && type !== 'hidden' && type !== 'submit' && type !== 'button';
                    });
                    const purposeField = fields.find((el) => {
                      const hay = norm([el.name, el.id, el.placeholder, el.getAttribute('aria-label')].join(' '));
                      return hay.includes('purpose') || hay.includes('用途') || hay.includes('访问令牌');
                    }) || fields[0];
                    if (purposeField) {
                      purposeField.focus();
                      purposeField.value = $quotedPurpose;
                      purposeField.dispatchEvent(new Event('input', { bubbles: true }));
                      purposeField.dispatchEvent(new Event('change', { bubbles: true }));
                    }

                    const generateNeedles = [
                      'generate token',
                      '生成令牌',
                      '生成',
                      'submit',
                      '确定'
                    ].map(norm);
                    const afterClickables = Array.from(document.querySelectorAll('button, a, [role="button"], input[type="button"], input[type="submit"]'));
                    const generateButton = afterClickables.find((el) => visible(el) && generateNeedles.some((needle) => textOf(el).includes(needle)));
                    if (generateButton) generateButton.click();
                  }, 900);

                  return 'STARTED';
                })()
            """.trimIndent()
        }
    }
}

private fun String.cleanBearerToken(): String =
    replace(Regex("^\\s*Bearer\\s+", RegexOption.IGNORE_CASE), "")
        .filterNot { it == '\r' || it == '\n' || it == '\t' || it == ' ' }
