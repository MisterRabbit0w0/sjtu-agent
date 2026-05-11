package edu.sjtu.agent.network

import edu.sjtu.agent.data.AgentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val JsonMediaType = "application/json; charset=utf-8".toMediaType()

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(180, TimeUnit.SECONDS)  // LLM 请求需要更长超时，与 Python 端对齐
    .writeTimeout(60, TimeUnit.SECONDS)
    .followRedirects(true)
    .build()

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response)
        }
    })
}

suspend fun OkHttpClient.getString(
    url: String,
    headers: Map<String, String> = emptyMap(),
    cookies: Map<String, String> = emptyMap(),
): AgentResult<Pair<String, Response>> = withContext(Dispatchers.IO) {
    runCatching {
        val req = Request.Builder().url(url).headers(headers, cookies).get().build()
        newCall(req).await().let { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) AgentResult.Error("HTTP ${response.code}: ${body.take(200)}")
            else AgentResult.Ok(body to response)
        }
    }.getOrElse { AgentResult.Error(it.message ?: "network error", it) }
}

suspend fun OkHttpClient.postJson(
    url: String,
    json: String,
    headers: Map<String, String> = emptyMap(),
    cookies: Map<String, String> = emptyMap(),
): AgentResult<Pair<String, Response>> = withContext(Dispatchers.IO) {
    runCatching {
        val req = Request.Builder()
            .url(url)
            .headers(headers, cookies)
            .post(json.toRequestBody(JsonMediaType))
            .build()
        newCall(req).await().let { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) AgentResult.Error("HTTP ${response.code}: ${body.take(200)}")
            else AgentResult.Ok(body to response)
        }
    }.getOrElse { AgentResult.Error(it.message ?: "network error", it) }
}

suspend fun OkHttpClient.postForm(
    url: String,
    form: Map<String, String>,
    headers: Map<String, String> = emptyMap(),
    cookies: Map<String, String> = emptyMap(),
): AgentResult<Pair<String, Response>> = withContext(Dispatchers.IO) {
    runCatching {
        val body = FormBody.Builder().apply {
            form.forEach { (key, value) -> add(key, value) }
        }.build()
        val req = Request.Builder()
            .url(url)
            .headers(headers, cookies)
            .post(body)
            .build()
        newCall(req).await().let { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) AgentResult.Error("HTTP ${response.code}: ${text.take(200)}")
            else AgentResult.Ok(text to response)
        }
    }.getOrElse { AgentResult.Error(it.message ?: "network error", it) }
}

private fun Request.Builder.headers(headers: Map<String, String>, cookies: Map<String, String>): Request.Builder {
    header("User-Agent", "Mozilla/5.0 (Linux; Android 14) SJTUAgent/0.1")
    headers.forEach { (key, value) -> header(key, value) }
    if (cookies.isNotEmpty()) {
        header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
    }
    return this
}

fun buildUrl(base: String, path: String, params: Map<String, String> = emptyMap()): String {
    val builder = base.trimEnd('/').plus(path).toHttpUrl().newBuilder()
    params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
    return builder.build().toString()
}

fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

fun parseCookieHeader(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return raw.split(";").mapNotNull { pair ->
        val key = pair.substringBefore("=", "").trim()
        val value = pair.substringAfter("=", "").trim()
        if (key.isBlank()) null else key to value
    }.toMap()
}

fun List<Cookie>.toSimpleMap(): Map<String, String> = associate { it.name to it.value }
