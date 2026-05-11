package edu.sjtu.agent.data

import kotlinx.serialization.Serializable
import java.time.Instant

data class DeadlineItem(
    val platform: String,
    val course: String,
    val title: String,
    val dueAt: Instant,
    val submitted: Boolean,
    val url: String = "",
    val metadata: Map<String, String> = emptyMap(),
)

data class ScheduleCourse(
    val name: String,
    val teacher: String,
    val location: String,
    val campus: String,
    val day: Int,
    val slotStart: Int,
    val slotEnd: Int,
    val timeStart: String,
    val timeEnd: String,
    val weeks: List<Int>,
    val weekText: String = "",
)

data class GradeItem(
    val year: String,
    val semester: String,
    val courseId: String,
    val courseName: String,
    val score: String,
    val gpa: String,
    val credits: String,
    val type: String,
)

data class LabBooking(
    val name: String,
    val dateTime: Instant,
    val room: String,
    val timeText: String,
)

@Serializable
data class Reminder(
    val id: Long,
    val title: String,
    val startAt: String,
    val endAt: String = "",
    val note: String = "",
    val expired: Boolean = false,
)

data class CampusSearchItem(
    val source: String,
    val title: String,
    val summary: String = "",
    val url: String = "",
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class LlmSettings(
    val provider: String = "zhiyuan",
    val baseUrl: String = "https://models.sjtu.edu.cn/api/v1",
    val model: String = "deepseek-chat",
    val apiKeySet: Boolean = false,
)

@Serializable
data class IcourseCourse(
    val name: String,
    val learnUrl: String,
    val termId: Long,
    val courseId: String,
)

@Serializable
data class AppSettings(
    val canvasBaseUrl: String = "https://oc.sjtu.edu.cn",
    val llm: LlmSettings = LlmSettings(),
    val semesterStart: String = "",
    val icourseCourses: List<IcourseCourse> = listOf(
        IcourseCourse(
            name = "大学物理",
            learnUrl = "https://www.icourse163.org/learn/SJTU-1449794172?tid=1476751568",
            termId = 1476751568,
            courseId = "SJTU-1449794172",
        ),
    ),
)

data class SetupStatus(
    val hasCanvasToken: Boolean,
    val hasAihaokeCookie: Boolean,
    val hasJwxtCookie: Boolean,
    val hasPhycaiCookie: Boolean,
    val hasIcourseCookie: Boolean,
    val hasLlmKey: Boolean,
    val hasShuiyuanCredential: Boolean,
    val hasDywebToken: Boolean,
)

data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val toolName: String? = null,
    val pending: Boolean = false,
)

enum class ChatRole {
    User,
    Assistant,
    Tool,
}

sealed class AgentResult<out T> {
    data class Ok<T>(val value: T) : AgentResult<T>()
    data class Error(val message: String, val cause: Throwable? = null) : AgentResult<Nothing>()
}

inline fun <T> AgentResult<T>.getOrElse(default: () -> T): T = when (this) {
    is AgentResult.Ok -> value
    is AgentResult.Error -> default()
}
