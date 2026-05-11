@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package edu.sjtu.agent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Login
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.sjtu.agent.auth.AuthSessionManager
import edu.sjtu.agent.auth.LoginProfiles
import edu.sjtu.agent.data.ChatRole
import edu.sjtu.agent.data.DeadlineItem
import edu.sjtu.agent.llm.providerPreset
import edu.sjtu.agent.ui.theme.SJTUAgentTheme
import edu.sjtu.agent.util.formatCampusTime
import edu.sjtu.agent.util.relativeFromNow

private enum class Tab(val label: String) {
    Today("今日"),
    Deadlines("DDL"),
    Chat("聊天"),
    Schedule("课表"),
    Profile("我的"),
}

@Composable
fun AgentApp(container: AppContainer) {
    val vm: AgentViewModel = viewModel(factory = AgentViewModelFactory(container))
    val state by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }

    SJTUAgentTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.Today,
                        onClick = { tab = Tab.Today },
                        icon = { Icon(Icons.Outlined.Home, null) },
                        label = { Text(Tab.Today.label) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Deadlines,
                        onClick = { tab = Tab.Deadlines },
                        icon = { Icon(Icons.Outlined.Assignment, null) },
                        label = { Text(Tab.Deadlines.label) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Chat,
                        onClick = { tab = Tab.Chat },
                        icon = { Icon(Icons.Outlined.Chat, null) },
                        label = { Text(Tab.Chat.label) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Schedule,
                        onClick = { tab = Tab.Schedule },
                        icon = { Icon(Icons.Outlined.DateRange, null) },
                        label = { Text(Tab.Schedule.label) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.Profile,
                        onClick = { tab = Tab.Profile },
                        icon = { Icon(Icons.Outlined.Person, null) },
                        label = { Text(Tab.Profile.label) },
                    )
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (state.notice.isNotBlank()) {
                    Text(
                        text = state.notice,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                when (tab) {
                    Tab.Today -> TodayScreen(state, vm)
                    Tab.Deadlines -> DeadlinesScreen(state, vm)
                    Tab.Chat -> ChatScreen(state, vm)
                    Tab.Schedule -> ScheduleScreen(state, vm)
                    Tab.Profile -> ProfileScreen(state, vm)
                }
            }
        }
    }
}

@Composable
private fun Page(title: String, action: @Composable (() -> Unit)? = null, content: @Composable () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                action?.invoke()
            }
        }
        item { content() }
    }
}

@Composable
private fun TodayScreen(state: AgentUiState, vm: AgentViewModel) {
    Page(
        title = "今日",
        action = {
            IconButton(onClick = vm::refreshAll) { Icon(Icons.Outlined.Refresh, "刷新") }
        },
    ) {
        StatusPanel(state)
        Spacer(Modifier.height(12.dp))
        Text("临近事项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        val upcoming = state.deadlines.take(5)
        if (upcoming.isEmpty()) {
            EmptyText("还没有 DDL 数据，点右上角刷新。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                upcoming.forEach { DeadlineCard(it) }
            }
        }
        Spacer(Modifier.height(12.dp))
        state.lab?.let { lab ->
            InfoCard("下一次物理实验", "${lab.name}\n${lab.dateTime.formatCampusTime()} · ${lab.room}\n${lab.timeText}")
        }
        if (state.reminders.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("提醒", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            state.reminders.take(4).forEach {
                InfoCard("#${it.id} ${it.title}", it.startAt)
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DeadlinesScreen(state: AgentUiState, vm: AgentViewModel) {
    var platform by rememberSaveable { mutableStateOf("全部") }
    val platforms = listOf("全部") + state.deadlines.map { it.platform }.distinct().sorted()
    val filtered = if (platform == "全部") state.deadlines else state.deadlines.filter { it.platform == platform }
    Page(
        title = "DDL",
        action = {
            IconButton(onClick = vm::refreshDeadlines) { Icon(Icons.Outlined.Refresh, "刷新 DDL") }
        },
    ) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            platforms.forEach {
                FilterChip(
                    selected = platform == it,
                    onClick = { platform = it },
                    label = { Text(it) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            EmptyText("没有可显示的 DDL。")
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                filtered.forEach { DeadlineCard(it) }
            }
        }
    }
}

@Composable
private fun ChatScreen(state: AgentUiState, vm: AgentViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Agent 聊天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.chatMessages) { message ->
                val tone = when (message.role) {
                    ChatRole.User -> MaterialTheme.colorScheme.primaryContainer
                    ChatRole.Assistant -> MaterialTheme.colorScheme.surface
                    ChatRole.Tool -> MaterialTheme.colorScheme.tertiaryContainer
                }
                Card(colors = CardDefaults.cardColors(containerColor = tone)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            text = when (message.role) {
                                ChatRole.User -> "你"
                                ChatRole.Assistant -> "SJTU Agent"
                                ChatRole.Tool -> message.toolName ?: "工具"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(message.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 4,
                placeholder = { Text("问 DDL、课表、成绩或校园事项") },
            )
            Button(
                onClick = {
                    vm.sendChat(input)
                    input = ""
                },
                enabled = input.isNotBlank() && !state.loading,
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
private fun ScheduleScreen(state: AgentUiState, vm: AgentViewModel) {
    var search by rememberSaveable { mutableStateOf("") }
    Page(title = "课表与校园") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::loadSchedule) { Text("课表") }
            Button(onClick = vm::loadGrades) { Text("成绩") }
            Button(onClick = vm::loadLab) { Text("物理实验") }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("搜索校园内容") },
            trailingIcon = {
                IconButton(onClick = { vm.searchCampus(search) }) {
                    Icon(Icons.Outlined.Search, "搜索")
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        if (state.schedule.isNotEmpty()) {
            Text("课表", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val coursesByDay = state.schedule
                .sortedWith(compareBy({ it.day }, { it.slotStart }, { it.name }))
                .groupBy { it.day }
            (1..7).forEach { day ->
                val courses = coursesByDay[day].orEmpty()
                if (courses.isNotEmpty()) {
                    Text(weekdayLabel(day), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    courses.forEach {
                        val weekText = it.weekText.ifBlank { it.weeks.joinToString("、") { week -> "$week" } }
                        InfoCard(
                            "${it.timeStart}-${it.timeEnd}",
                            listOf(
                                it.name,
                                listOf(it.location, it.teacher).filter(String::isNotBlank).joinToString(" · "),
                                weekText.takeIf { text -> text.isNotBlank() }?.let { text -> "周次：$text" }.orEmpty(),
                            ).filter(String::isNotBlank).joinToString("\n"),
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
        if (state.grades.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("成绩", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            state.grades.take(20).forEach {
                InfoCard(it.courseName, "${it.score} · 绩点 ${it.gpa} · ${it.credits} 学分")
                Spacer(Modifier.height(8.dp))
            }
        }
        state.lab?.let {
            Spacer(Modifier.height(8.dp))
            InfoCard("下一次物理实验", "${it.name}\n${it.dateTime.formatCampusTime()} · ${it.room}")
        }
        if (state.searchResults.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("搜索结果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            state.searchResults.forEach {
                InfoCard("[${it.source}] ${it.title}", listOf(it.summary, it.url).filter(String::isNotBlank).joinToString("\n"))
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ProfileScreen(state: AgentUiState, vm: AgentViewModel) {
    val context = LocalContext.current
    var provider by rememberSaveable(state.settings.llm.provider) { mutableStateOf(state.settings.llm.provider) }
    var baseUrl by rememberSaveable(state.settings.llm.baseUrl) { mutableStateOf(state.settings.llm.baseUrl) }
    var model by rememberSaveable(state.settings.llm.model) { mutableStateOf(state.settings.llm.model) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var canvasBase by rememberSaveable(state.settings.canvasBaseUrl) { mutableStateOf(state.settings.canvasBaseUrl) }
    var canvasToken by rememberSaveable { mutableStateOf("") }
    var shuiyuanKey by rememberSaveable { mutableStateOf("") }
    var shuiyuanClientId by rememberSaveable { mutableStateOf("") }
    var reminderTitle by rememberSaveable { mutableStateOf("") }
    var reminderStart by rememberSaveable { mutableStateOf("") }

    Page(title = "我的") {
        StatusPanel(state)
        Spacer(Modifier.height(12.dp))
        SectionTitle("模型")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("zhiyuan", "openai", "deepseek", "custom").forEach { p ->
                FilterChip(
                    selected = provider == p,
                    onClick = {
                        provider = p
                        val preset = providerPreset(p)
                        if (p != "custom") {
                            baseUrl = preset.baseUrl
                            model = preset.model
                        }
                    },
                    label = { Text(p) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(baseUrl, { baseUrl = it }, Modifier.fillMaxWidth(), label = { Text("Base URL") }, singleLine = true)
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型") }, singleLine = true)
        OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API Key（留空则不覆盖）") }, singleLine = true)
        Button(onClick = { vm.saveLlmSettings(provider, baseUrl, model, apiKey); apiKey = "" }) {
            Icon(Icons.Outlined.Save, null)
            Spacer(Modifier.width(8.dp))
            Text("保存模型")
        }
        Divider(Modifier.padding(vertical = 10.dp))
        SectionTitle("Canvas")
        OutlinedTextField(canvasBase, { canvasBase = it }, Modifier.fillMaxWidth(), label = { Text("Canvas Base URL") }, singleLine = true)
        OutlinedTextField(canvasToken, { canvasToken = it }, Modifier.fillMaxWidth(), label = { Text("Canvas Token（留空则不覆盖）") }, singleLine = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { context.startActivity(AuthSessionManager.canvasTokenIntent(context, canvasBase)) }) {
                Icon(Icons.Outlined.Login, null)
                Spacer(Modifier.width(8.dp))
                Text("登录 Canvas")
            }
            Button(onClick = { vm.saveCanvasToken(canvasBase, canvasToken); canvasToken = "" }) {
                Icon(Icons.Outlined.Save, null)
                Spacer(Modifier.width(8.dp))
                Text("保存 Canvas")
            }
        }
        Divider(Modifier.padding(vertical = 10.dp))
        SectionTitle("平台登录")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LoginProfiles.forEach { profile ->
                AssistChip(
                    onClick = { context.startActivity(AuthSessionManager.intentFor(context, profile)) },
                    label = { Text(profile.title) },
                    leadingIcon = { Icon(Icons.Outlined.Login, null) },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SectionTitle("水源 API Key")
        OutlinedTextField(shuiyuanKey, { shuiyuanKey = it }, Modifier.fillMaxWidth(), label = { Text("User API Key") }, singleLine = true)
        OutlinedTextField(shuiyuanClientId, { shuiyuanClientId = it }, Modifier.fillMaxWidth(), label = { Text("Client ID") }, singleLine = true)
        Button(onClick = { vm.saveShuiyuan(shuiyuanKey, shuiyuanClientId); shuiyuanKey = ""; shuiyuanClientId = "" }) {
            Text("保存水源")
        }
        Divider(Modifier.padding(vertical = 10.dp))
        SectionTitle("提醒")
        OutlinedTextField(reminderTitle, { reminderTitle = it }, Modifier.fillMaxWidth(), label = { Text("标题") })
        OutlinedTextField(reminderStart, { reminderStart = it }, Modifier.fillMaxWidth(), label = { Text("开始时间，如 2026-05-12 18:00") })
        Button(onClick = { vm.addReminder(reminderTitle, reminderStart); reminderTitle = ""; reminderStart = "" }) {
            Text("添加提醒")
        }
        state.reminders.forEach {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("#${it.id} ${it.title}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { vm.removeReminder(it.id) }) { Icon(Icons.Outlined.Delete, "删除") }
            }
        }
        TextButton(onClick = vm::clearAllSecrets) {
            Icon(Icons.Outlined.Delete, null)
            Spacer(Modifier.width(8.dp))
            Text("清除本地全部配置")
        }
    }
}

@Composable
private fun StatusPanel(state: AgentUiState) {
    val chips = listOf(
        "Canvas" to state.status.hasCanvasToken,
        "AI 好课" to state.status.hasAihaokeCookie,
        "教务" to state.status.hasJwxtCookie,
        "物理实验" to state.status.hasPhycaiCookie,
        "MOOC" to state.status.hasIcourseCookie,
        "模型" to state.status.hasLlmKey,
        "水源" to state.status.hasShuiyuanCredential,
        "传承" to state.status.hasDywebToken,
    )
    Card {
        FlowRow(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { (label, ok) ->
                AssistChip(
                    onClick = {},
                    label = { Text(if (ok) "$label 已配置" else "$label 未配置") },
                )
            }
        }
    }
}

@Composable
private fun DeadlineCard(item: DeadlineItem) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(item.platform, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                Text(item.dueAt.relativeFromNow(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(item.course, style = MaterialTheme.typography.bodyMedium)
            Text(item.dueAt.formatCampusTime(), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

private fun weekdayLabel(day: Int): String = when (day) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "未分配"
}

@Composable
private fun EmptyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
}
