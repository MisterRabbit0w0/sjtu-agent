package edu.sjtu.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.sjtu.agent.data.AgentResult
import edu.sjtu.agent.data.AppSettings
import edu.sjtu.agent.data.CampusSearchItem
import edu.sjtu.agent.data.ChatMessage
import edu.sjtu.agent.data.ChatRole
import edu.sjtu.agent.data.DeadlineItem
import edu.sjtu.agent.data.GradeItem
import edu.sjtu.agent.data.LabBooking
import edu.sjtu.agent.data.LlmSettings
import edu.sjtu.agent.data.Reminder
import edu.sjtu.agent.data.ScheduleCourse
import edu.sjtu.agent.data.SetupStatus
import edu.sjtu.agent.llm.providerPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentUiState(
    val settings: AppSettings = AppSettings(),
    val status: SetupStatus = SetupStatus(false, false, false, false, false, false, false, false),
    val deadlines: List<DeadlineItem> = emptyList(),
    val schedule: List<ScheduleCourse> = emptyList(),
    val grades: List<GradeItem> = emptyList(),
    val lab: LabBooking? = null,
    val reminders: List<Reminder> = emptyList(),
    val searchResults: List<CampusSearchItem> = emptyList(),
    val chatMessages: List<ChatMessage> = listOf(
        ChatMessage(ChatRole.Assistant, "你好，我是 SJTU Agent。可以问我 DDL、课表、成绩、校园通知，也可以让我帮你加提醒。"),
    ),
    val loading: Boolean = false,
    val notice: String = "",
)

class AgentViewModel(private val container: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(AgentUiState())
    val ui: StateFlow<AgentUiState> = _ui

    init {
        viewModelScope.launch {
            loadSettingsAndStatus()
            listReminders()
        }
    }

    fun refreshAll() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, notice = "正在刷新校园数据") }
            refreshDeadlinesInternal()
            loadScheduleInternal()
            loadLabInternal()
            listRemindersInternal()
            _ui.update { it.copy(loading = false, notice = "刷新完成") }
        }
    }

    fun refreshDeadlines() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, notice = "正在刷新 DDL") }
            refreshDeadlinesInternal()
            _ui.update { it.copy(loading = false) }
        }
    }

    fun loadSchedule() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, notice = "正在加载课表") }
            loadScheduleInternal()
            _ui.update { it.copy(loading = false) }
        }
    }

    fun loadGrades() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, notice = "正在查询成绩") }
            when (val result = container.repository.queryGrades()) {
                is AgentResult.Error -> _ui.update { it.copy(notice = result.message) }
                is AgentResult.Ok -> _ui.update { it.copy(grades = result.value, notice = "成绩已更新") }
            }
            _ui.update { it.copy(loading = false) }
        }
    }

    fun loadLab() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, notice = "正在查询物理实验") }
            loadLabInternal()
            _ui.update { it.copy(loading = false) }
        }
    }

    fun searchCampus(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, notice = "正在搜索校园内容") }
            when (val result = container.repository.searchCampus(query)) {
                is AgentResult.Error -> _ui.update { it.copy(notice = result.message) }
                is AgentResult.Ok -> _ui.update { it.copy(searchResults = result.value, notice = "搜索完成") }
            }
            _ui.update { it.copy(loading = false) }
        }
    }

    fun sendChat(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            val userMessage = ChatMessage(ChatRole.User, content)
            _ui.update { it.copy(chatMessages = it.chatMessages + userMessage, loading = true, notice = "Agent 正在思考") }
            try {
                val settings = container.store.getSettings()
                val apiKey = container.store.getSecret("llm_api_key")
                when (val result = container.llm.chat(settings.llm.copy(apiKeySet = apiKey.isNotBlank()), apiKey, _ui.value.chatMessages)) {
                    is AgentResult.Error -> _ui.update {
                        it.copy(
                            chatMessages = it.chatMessages + ChatMessage(ChatRole.Assistant, "请求失败：${result.message}"),
                            notice = result.message,
                        )
                    }
                    is AgentResult.Ok -> _ui.update {
                        it.copy(
                            chatMessages = it.chatMessages + result.value.second + ChatMessage(ChatRole.Assistant, result.value.first),
                            notice = "回复完成",
                        )
                    }
                }
            } catch (t: Throwable) {
                val message = t.message ?: t::class.java.simpleName
                _ui.update {
                    it.copy(
                        chatMessages = it.chatMessages + ChatMessage(ChatRole.Assistant, "请求失败：$message"),
                        notice = message,
                    )
                }
            } finally {
                _ui.update { it.copy(loading = false) }
            }
        }
    }

    fun saveCanvasToken(baseUrl: String, token: String) {
        viewModelScope.launch {
            val canvasBase = baseUrl.ifBlank { "https://oc.sjtu.edu.cn" }
            val settings = container.store.getSettings().copy(canvasBaseUrl = canvasBase)
            container.store.saveSettings(settings)
            val cleanToken = token.cleanBearerToken()
            if (cleanToken.isNotBlank()) {
                when (val validation = container.canvas.validateToken(canvasBase, cleanToken)) {
                    is AgentResult.Error -> {
                        _ui.update { it.copy(notice = validation.message) }
                        return@launch
                    }
                    is AgentResult.Ok -> container.store.putSecret("canvas_token", cleanToken)
                }
            }
            loadSettingsAndStatus("Canvas 配置已保存")
        }
    }

    fun saveLlmSettings(provider: String, baseUrl: String, model: String, apiKey: String) {
        viewModelScope.launch {
            val preset = providerPreset(provider)
            val old = container.store.getSettings()
            val cleanApiKey = apiKey.cleanSecret()
            val llm = LlmSettings(
                provider = provider,
                baseUrl = baseUrl.ifBlank { preset.baseUrl },
                model = model.ifBlank { preset.model },
                apiKeySet = cleanApiKey.isNotBlank() || old.llm.apiKeySet,
            )
            container.store.saveSettings(old.copy(llm = llm))
            if (cleanApiKey.isNotBlank()) container.store.putSecret("llm_api_key", cleanApiKey)
            loadSettingsAndStatus("模型配置已保存")
        }
    }

    fun saveShuiyuan(apiKey: String, clientId: String) {
        viewModelScope.launch {
            if (apiKey.isNotBlank()) container.store.putSecret("shuiyuan_api_key", apiKey)
            if (clientId.isNotBlank()) container.store.putSecret("shuiyuan_client_id", clientId)
            loadSettingsAndStatus("水源配置已保存")
        }
    }

    fun clearAllSecrets() {
        viewModelScope.launch {
            container.store.clearAll()
            _ui.value = AgentUiState(notice = "已清除本地配置")
            loadSettingsAndStatus()
        }
    }

    fun addReminder(title: String, start: String) {
        if (title.isBlank() || start.isBlank()) return
        viewModelScope.launch {
            when (val result = container.repository.addReminder(title, start)) {
                is AgentResult.Error -> _ui.update { it.copy(notice = result.message) }
                is AgentResult.Ok -> {
                    listRemindersInternal()
                    _ui.update { it.copy(notice = "提醒已添加 #${result.value.id}") }
                }
            }
        }
    }

    fun listReminders() {
        viewModelScope.launch { listRemindersInternal() }
    }

    fun removeReminder(id: Long) {
        viewModelScope.launch {
            when (val result = container.repository.removeReminder(id)) {
                is AgentResult.Error -> _ui.update { it.copy(notice = result.message) }
                is AgentResult.Ok -> {
                    listRemindersInternal()
                    _ui.update { it.copy(notice = "提醒已删除") }
                }
            }
        }
    }

    private suspend fun loadSettingsAndStatus(notice: String = "") {
        val settings = container.store.getSettings()
        val status = container.repository.status()
        _ui.update { it.copy(settings = settings, status = status, notice = notice.ifBlank { it.notice }) }
    }

    private suspend fun refreshDeadlinesInternal() {
        when (val result = container.repository.refreshDeadlines()) {
            is AgentResult.Error -> _ui.update { it.copy(notice = result.message) }
            is AgentResult.Ok -> _ui.update { it.copy(deadlines = result.value, notice = "DDL 已更新，共 ${result.value.size} 项") }
        }
        loadSettingsAndStatus()
    }

    private suspend fun loadScheduleInternal() {
        when (val result = container.repository.fetchSchedule()) {
            is AgentResult.Error -> _ui.update { it.copy(notice = result.message) }
            is AgentResult.Ok -> _ui.update { it.copy(schedule = result.value, notice = "课表已更新") }
        }
    }

    private suspend fun loadLabInternal() {
        when (val result = container.repository.fetchNextLab()) {
            is AgentResult.Error -> _ui.update { it.copy(notice = result.message) }
            is AgentResult.Ok -> _ui.update { it.copy(lab = result.value, notice = result.value?.let { "实验安排已更新" } ?: "暂无未来实验") }
        }
    }

    private suspend fun listRemindersInternal() {
        when (val result = container.repository.listReminders()) {
            is AgentResult.Error -> _ui.update { it.copy(notice = result.message) }
            is AgentResult.Ok -> _ui.update { it.copy(reminders = result.value) }
        }
    }
}

class AgentViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AgentViewModel(container) as T
    }
}

private fun String.cleanSecret(): String =
    trim().filterNot { it == '\r' || it == '\n' || it == '\t' || it == ' ' }

private fun String.cleanBearerToken(): String =
    replace(Regex("^\\s*Bearer\\s+", RegexOption.IGNORE_CASE), "")
        .filterNot { it == '\r' || it == '\n' || it == '\t' || it == ' ' }
