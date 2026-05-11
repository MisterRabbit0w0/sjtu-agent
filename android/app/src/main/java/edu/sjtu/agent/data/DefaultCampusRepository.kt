package edu.sjtu.agent.data

import edu.sjtu.agent.data.local.CredentialStore
import edu.sjtu.agent.network.AihaokeClient
import edu.sjtu.agent.network.CampusSearchClient
import edu.sjtu.agent.network.CanvasClient
import edu.sjtu.agent.network.IcourseClient
import edu.sjtu.agent.network.JwxtClient
import edu.sjtu.agent.network.PhycaiClient
import edu.sjtu.agent.util.parseCampusInstant
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant

class DefaultCampusRepository(
    private val store: CredentialStore,
    private val canvas: CanvasClient,
    private val aihaoke: AihaokeClient,
    private val icourse: IcourseClient,
    private val jwxt: JwxtClient,
    private val phycai: PhycaiClient,
    private val search: CampusSearchClient,
) : CampusRepository {
    override suspend fun status(): SetupStatus = store.status()

    override suspend fun refreshDeadlines(): AgentResult<List<DeadlineItem>> = coroutineScope {
        val settings = store.getSettings()
        val canvasToken = store.getSecret("canvas_token")
        val canvasTask = async {
            if (canvasToken.isBlank()) AgentResult.Ok(emptyList()) else canvas.fetchDeadlines(settings.canvasBaseUrl, canvasToken)
        }
        val tasks = listOf(
            async { aihaoke.fetchDeadlines(store.getCookies("aihaoke")).emptyOnError() },
            async { icourse.fetchDeadlines(store.getCookies("icourse"), settings.icourseCourses).emptyOnError() },
        )
        val canvasItems = when (val result = canvasTask.await()) {
            is AgentResult.Ok -> result.value
            is AgentResult.Error -> {
                if (result.message.contains("Token 无效") || result.message.contains("401") || result.message.contains("Invalid access token", ignoreCase = true)) {
                    store.removeSecret("canvas_token")
                }
                return@coroutineScope AgentResult.Error("Canvas DDL 获取失败：${result.message}")
            }
        }
        AgentResult.Ok((canvasItems + tasks.awaitAll().flatten()).filter { !it.submitted }.sortedBy { it.dueAt })
    }

    override suspend fun fetchSchedule(): AgentResult<List<ScheduleCourse>> =
        jwxt.fetchSchedule(store.getCookies("jwxt"))

    override suspend fun queryGrades(year: String, semester: String): AgentResult<List<GradeItem>> =
        jwxt.queryGrades(store.getCookies("jwxt"), year, semester)

    override suspend fun fetchNextLab(): AgentResult<LabBooking?> =
        phycai.fetchNextLab(store.getCookies("phycai"))

    override suspend fun searchCampus(query: String, sites: Set<String>): AgentResult<List<CampusSearchItem>> =
        search.search(
            query = query,
            sites = sites,
            maxResults = 6,
            shuiyuanApiKey = store.getSecret("shuiyuan_api_key"),
            shuiyuanClientId = store.getSecret("shuiyuan_client_id"),
            shuiyuanCookies = store.getCookies("shuiyuan"),
            dywebToken = store.getSecret("dyweb_token").ifBlank { store.getCookies("dyweb")["sjtu_token"].orEmpty() },
        )

    override suspend fun addReminder(title: String, startAt: String, endAt: String, note: String): AgentResult<Reminder> {
        val start = parseCampusInstant(startAt) ?: return AgentResult.Error("无法解析提醒开始时间")
        val end = endAt.takeIf { it.isNotBlank() }?.let { parseCampusInstant(it) }
        val current = store.getReminders()
        val nextId = (current.maxOfOrNull { it.id } ?: 0L) + 1
        val reminder = Reminder(
            id = nextId,
            title = title.trim(),
            startAt = start.toString(),
            endAt = end?.toString().orEmpty(),
            note = note.trim(),
            expired = (end ?: start) < Instant.now(),
        )
        store.saveReminders(current + reminder)
        return AgentResult.Ok(reminder)
    }

    override suspend fun listReminders(): AgentResult<List<Reminder>> {
        val now = Instant.now()
        return AgentResult.Ok(store.getReminders().map {
            val end = it.endAt.takeIf(String::isNotBlank)?.let(::parseCampusInstant)
            val start = parseCampusInstant(it.startAt)
            it.copy(expired = (end ?: start)?.let { instant -> instant < now } ?: false)
        }.sortedBy { parseCampusInstant(it.startAt) ?: Instant.MAX })
    }

    override suspend fun removeReminder(id: Long): AgentResult<Unit> {
        val current = store.getReminders()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return AgentResult.Error("未找到提醒 #$id")
        store.saveReminders(next)
        return AgentResult.Ok(Unit)
    }

    private fun AgentResult<List<DeadlineItem>>.emptyOnError(): List<DeadlineItem> = when (this) {
        is AgentResult.Ok -> value
        is AgentResult.Error -> emptyList()
    }
}
