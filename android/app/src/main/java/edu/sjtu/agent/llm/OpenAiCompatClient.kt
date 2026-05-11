package edu.sjtu.agent.llm

import edu.sjtu.agent.data.AgentResult
import edu.sjtu.agent.data.ChatMessage
import edu.sjtu.agent.data.ChatRole
import edu.sjtu.agent.data.LlmSettings
import edu.sjtu.agent.network.JsonMediaType
import edu.sjtu.agent.network.await
import edu.sjtu.agent.util.LenientJson
import edu.sjtu.agent.util.array
import edu.sjtu.agent.util.obj
import edu.sjtu.agent.util.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class OpenAiCompatClient(
    private val http: OkHttpClient,
    private val toolRouter: ToolRouter,
) {
    suspend fun chat(
        settings: LlmSettings,
        apiKey: String,
        messages: List<ChatMessage>,
    ): AgentResult<Pair<String, List<ChatMessage>>> = withContext(Dispatchers.IO) {
        val cleanApiKey = apiKey.cleanHeaderSecret()
        if (cleanApiKey.isBlank()) return@withContext AgentResult.Error("请先在「我的」里配置模型 API Key")
        val apiMessages = mutableListOf<JsonObject>()
        apiMessages += buildJsonObject {
            put("role", "system")
            put(
                "content",
                "你是 SJTU Agent Android。回答使用中文。遇到 DDL、课表、成绩、校园搜索、提醒相关请求时优先调用工具。不要透露任何本地密钥。",
            )
        }
        messages.filter { it.role != ChatRole.Tool || it.toolName != null }.forEach { message ->
            when (message.role) {
                ChatRole.User -> apiMessages += buildJsonObject {
                    put("role", "user")
                    put("content", message.content)
                }
                ChatRole.Assistant -> apiMessages += buildJsonObject {
                    put("role", "assistant")
                    put("content", message.content)
                }
                ChatRole.Tool -> Unit
            }
        }

        val toolEvents = mutableListOf<ChatMessage>()
        var hasNativeReasoning = false
        repeat(MAX_TOOL_ROUNDS) {
            val choice = when (val response = requestChat(settings, cleanApiKey, apiMessages, includeTools = true)) {
                is AgentResult.Error -> return@withContext AgentResult.Error(response.message, response.cause)
                is AgentResult.Ok -> response.value
            }
            val message = choice.obj("message")
            val toolCalls = message.array("tool_calls")

            // 提取 reasoning_content（DeepSeek-R1 等思考模型返回）
            val reasoningContent = message["reasoning_content"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (reasoningContent.isNotBlank()) {
                hasNativeReasoning = true
            }

            if (toolCalls.isEmpty()) {
                return@withContext AgentResult.Ok(message.string("content") to toolEvents)
            }

            // 构建 assistant 消息，包含 reasoning_content（部分 endpoint 要求回传）
            apiMessages += buildJsonObject {
                put("role", "assistant")
                put("content", message.string("content"))
                put("tool_calls", toolCalls)
                if (hasNativeReasoning && reasoningContent.isNotBlank()) {
                    put("reasoning_content", reasoningContent)
                }
            }
            for (callEl in toolCalls) {
                val call = callEl as? JsonObject ?: continue
                val function = call.obj("function")
                val name = function.string("name")
                val args = runCatching {
                    LenientJson.parseToJsonElement(function.string("arguments").ifBlank { "{}" }).jsonObject
                }.getOrDefault(JsonObject(emptyMap()))
                toolEvents += ChatMessage(ChatRole.Tool, "调用工具：$name", toolName = name)
                val result = runCatching { toolRouter.runTool(name, args) }
                    .getOrElse { """{"error":"${(it.message ?: "工具调用失败").replace("\"", "\\\"")}"}""" }
                apiMessages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", call.string("id"))
                    put("content", result)
                }
                toolEvents += ChatMessage(ChatRole.Tool, result.take(600), toolName = name)
            }
        }
        AgentResult.Error("工具调用轮次过多，请缩小问题范围后重试")
    }

    private suspend fun requestChat(
        settings: LlmSettings,
        apiKey: String,
        messages: List<JsonObject>,
        includeTools: Boolean,
    ): AgentResult<JsonObject> {
        val baseUrl = settings.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return AgentResult.Error("请先配置模型 Base URL")
        if (settings.model.isBlank()) return AgentResult.Error("请先配置模型名称")
        val endpoint = "$baseUrl/chat/completions".toHttpUrlOrNull()
            ?: return AgentResult.Error("模型 Base URL 不合法：$baseUrl")
        val payload = buildJsonObject {
            put("model", settings.model)
            put("messages", JsonArray(messages))
            put("temperature", 0.3)
            if (includeTools) {
                val tools = runCatching { LenientJson.parseToJsonElement(toolRouter.toolsJson) }
                    .getOrElse { return AgentResult.Error("本地工具定义不是合法 JSON") }
                put("tools", tools)
                put("tool_choice", "auto")
            }
        }.toString()
        val request = runCatching {
            Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(payload.toRequestBody(JsonMediaType))
                .build()
        }.getOrElse {
            return AgentResult.Error(it.message ?: "LLM 请求构造失败", it)
        }

        // 重试机制：最多重试3次，处理超时和网络错误
        var lastError: Throwable? = null
        repeat(MAX_RETRIES) { attempt ->
            val response = try {
                http.newCall(request).await()
            } catch (e: Exception) {
                lastError = e
                val isTimeout = e.message?.contains("timeout", ignoreCase = true) == true ||
                    e.message?.contains("timed out", ignoreCase = true) == true
                if (isTimeout && attempt < MAX_RETRIES - 1) {
                    android.util.Log.w("SJTUAgent.LLM", "Request timeout, retrying ${attempt + 1}/$MAX_RETRIES")
                    delay(RETRY_DELAY_MS)
                    return@repeat
                }
                return AgentResult.Error(e.message ?: "LLM 请求失败", e)
            }
            val body = runCatching { response.body?.string().orEmpty() }
                .getOrElse { return AgentResult.Error(it.message ?: "LLM 响应读取失败", it) }
            if (!response.isSuccessful) {
                // 对于服务器过载错误，进行重试
                if (response.code == 429 || response.code >= 500) {
                    lastError = RuntimeException("HTTP ${response.code}")
                    if (attempt < MAX_RETRIES - 1) {
                        android.util.Log.w("SJTUAgent.LLM", "Server error ${response.code}, retrying ${attempt + 1}/$MAX_RETRIES")
                        delay(RETRY_DELAY_MS)
                        return@repeat
                    }
                }
                return AgentResult.Error("LLM HTTP ${response.code}: ${body.take(300)}")
            }
            val root = runCatching { LenientJson.parseToJsonElement(body).jsonObject }
                .getOrElse { return AgentResult.Error("LLM 响应不是 JSON") }
            val choice = root.array("choices").firstOrNull() as? JsonObject
                ?: return AgentResult.Error("LLM 响应缺少 choices")
            return AgentResult.Ok(choice)
        }
        return AgentResult.Error(lastError?.message ?: "LLM 请求失败", lastError)
    }

    companion object {
        private const val MAX_TOOL_ROUNDS = 6
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 5000L
    }
}

fun providerPreset(provider: String): LlmSettings = when (provider) {
    "zhiyuan" -> LlmSettings(
        provider = "zhiyuan",
        baseUrl = "https://models.sjtu.edu.cn/api/v1",
        model = "deepseek-chat",
    )
    "openai" -> LlmSettings(
        provider = "openai",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini",
    )
    "deepseek" -> LlmSettings(
        provider = "deepseek",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-chat",
    )
    else -> LlmSettings(provider = "custom", baseUrl = "", model = "")
}

private fun String.cleanHeaderSecret(): String =
    trim().filterNot { it == '\r' || it == '\n' || it == '\t' || it == ' ' }
