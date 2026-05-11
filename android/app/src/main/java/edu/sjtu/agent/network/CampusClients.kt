package edu.sjtu.agent.network

import edu.sjtu.agent.data.AgentResult
import edu.sjtu.agent.data.CampusSearchItem
import edu.sjtu.agent.data.DeadlineItem
import edu.sjtu.agent.data.GradeItem
import edu.sjtu.agent.data.IcourseCourse
import edu.sjtu.agent.data.LabBooking
import edu.sjtu.agent.data.ScheduleCourse
import edu.sjtu.agent.util.LenientJson
import edu.sjtu.agent.util.ShanghaiZone
import edu.sjtu.agent.util.SlotTimes
import edu.sjtu.agent.util.array
import edu.sjtu.agent.util.boolean
import edu.sjtu.agent.util.currentAcademicYearTerm
import edu.sjtu.agent.util.int
import edu.sjtu.agent.util.long
import edu.sjtu.agent.util.obj
import edu.sjtu.agent.util.parseCampusInstant
import edu.sjtu.agent.util.parseClassSlots
import edu.sjtu.agent.util.parseWeekSet
import edu.sjtu.agent.util.string
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

class CanvasClient(private val http: OkHttpClient) {
    suspend fun validateToken(baseUrl: String, token: String): AgentResult<Unit> {
        val cleanToken = token.cleanBearerToken()
        if (cleanToken.isBlank()) return AgentResult.Error("Canvas Token 为空")
        val normalizedBase = baseUrl.trim().ifBlank { "https://oc.sjtu.edu.cn" }.trimEnd('/')
        return when (val result = http.getString(
            "$normalizedBase/api/v1/users/self/profile",
            headers = mapOf("Authorization" to "Bearer $cleanToken"),
        )) {
            is AgentResult.Error -> AgentResult.Error(canvasTokenError(result.message), result.cause)
            is AgentResult.Ok -> AgentResult.Ok(Unit)
        }
    }

    suspend fun fetchDeadlines(baseUrl: String, token: String): AgentResult<List<DeadlineItem>> {
        val cleanToken = token.cleanBearerToken()
        if (cleanToken.isBlank()) return AgentResult.Error("Canvas Token 未配置")
        val headers = mapOf("Authorization" to "Bearer $cleanToken")
        val courses = mutableListOf<JsonObject>()
        val normalizedBase = baseUrl.trim().ifBlank { "https://oc.sjtu.edu.cn" }.trimEnd('/')
        var nextUrl: String? = buildUrl(normalizedBase, "/api/v1/courses", mapOf("enrollment_state" to "active", "per_page" to "100"))
        Log.i("SJTUAgent.Canvas", "fetch courses from $normalizedBase, tokenLength=${cleanToken.length}")
        while (nextUrl != null) {
            when (val result = http.getString(nextUrl, headers = headers)) {
                is AgentResult.Error -> {
                    Log.w("SJTUAgent.Canvas", "fetch courses failed: ${result.message}")
                    return AgentResult.Error(canvasTokenError(result.message), result.cause)
                }
                is AgentResult.Ok -> {
                    courses += LenientJson.parseToJsonElement(result.value.first).jsonArray.mapNotNull { it as? JsonObject }
                    nextUrl = result.value.second.nextLink()
                }
            }
        }
        Log.i("SJTUAgent.Canvas", "active courses=${courses.size}")

        val todayStart = LocalDate.now(ShanghaiZone).atStartOfDay(ShanghaiZone).toInstant()
        val items = coroutineScope {
            courses.map { course ->
                async {
                    fetchCourseAssignments(normalizedBase, headers, course, todayStart)
                }
            }.awaitAll().flatten()
        }
        return AgentResult.Ok(items.sortedBy { it.dueAt })
    }

    private suspend fun fetchCourseAssignments(
        baseUrl: String,
        headers: Map<String, String>,
        course: JsonObject,
        todayStart: Instant,
    ): List<DeadlineItem> {
        val courseId = course.long("id")
        if (courseId == 0L) return emptyList()
        val courseName = course.string("name", "课程$courseId")
        val pending = linkedMapOf<Long, Pair<String, Instant>>()
        // 使用完整 assignments 分页 + 本地按 due_at 过滤，与 Python 端对齐
        // Canvas bucket=upcoming 只返回平台认定的近期项目，会漏掉更远期但已有 due_at 的作业
        var url: String? = buildUrl(baseUrl, "/api/v1/courses/$courseId/assignments", mapOf("per_page" to "100", "order_by" to "due_at"))
        while (url != null) {
            val result = http.getString(url, headers = headers)
            if (result !is AgentResult.Ok) {
                Log.w("SJTUAgent.Canvas", "fetch assignments failed course=$courseId: ${(result as AgentResult.Error).message}")
                break
            }
            LenientJson.parseToJsonElement(result.value.first).jsonArray.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                if (obj.array("submission_types").isEmpty()) return@forEach
                val due = parseCampusInstant(obj.string("due_at")) ?: return@forEach
                if (due < todayStart) return@forEach
                pending[obj.long("id")] = obj.string("name", "未知作业") to due
            }
            url = result.value.second.nextLink()
        }
        if (pending.isEmpty()) return emptyList()

        val subUrl = "$baseUrl/api/v1/courses/$courseId/students/submissions".toHttpUrl().newBuilder()
            .addQueryParameter("student_ids[]", "self")
            .addQueryParameter("per_page", "100")
            .apply { pending.keys.forEach { addQueryParameter("assignment_ids[]", it.toString()) } }
            .build()
            .toString()
        val submitted = mutableSetOf<Long>()
        val submissions = http.getString(subUrl, headers = headers)
        if (submissions is AgentResult.Ok) {
            LenientJson.parseToJsonElement(submissions.value.first).jsonArray.forEach { element ->
                val sub = element as? JsonObject ?: return@forEach
                if (sub.string("workflow_state") in setOf("submitted", "graded") && sub.string("submitted_at").isNotBlank()) {
                    submitted.add(sub.long("assignment_id"))
                }
            }
        } else if (submissions is AgentResult.Error) {
            Log.w("SJTUAgent.Canvas", "fetch submissions failed course=$courseId: ${submissions.message}")
        }

        return pending.map { (assignmentId, pair) ->
            DeadlineItem(
                platform = "Canvas",
                course = courseName,
                title = pair.first,
                dueAt = pair.second,
                submitted = assignmentId in submitted,
                url = "$baseUrl/courses/$courseId/assignments/$assignmentId",
                metadata = mapOf("course_id" to courseId.toString(), "assignment_id" to assignmentId.toString()),
            )
        }
    }

    private fun okhttp3.Response.nextLink(): String? {
        val link = header("Link").orEmpty()
        return link.split(",").firstOrNull { it.contains("rel=\"next\"") }
            ?.substringAfter("<")
            ?.substringBefore(">")
    }
}

private fun String.cleanBearerToken(): String =
    replace(Regex("^\\s*Bearer\\s+", RegexOption.IGNORE_CASE), "")
        .filterNot { it == '\r' || it == '\n' || it == '\t' || it == ' ' }

private fun canvasTokenError(message: String): String =
    if (message.contains("401") || message.contains("Invalid access token", ignoreCase = true)) {
        "Canvas Token 无效，请在「我的」重新登录 Canvas 并生成 Access Token"
    } else {
        message
    }

class AihaokeClient(private val http: OkHttpClient) {
    suspend fun validateToken(token: String, cookies: Map<String, String> = emptyMap()): AgentResult<Unit> {
        val cleanToken = token.cleanBearerToken()
        if (cleanToken.isBlank()) return AgentResult.Error("AI 好课 haoke-token 未配置，请先登录")
        val text = when (val result = http.postJson(
            "https://sjtu.aihaoke.net/api/teach/instance/listMyClass",
            """{"requestId":"${UUID.randomUUID()}"}""",
            headers = aihaokeHeaders(cleanToken),
            cookies = cookies.filterAihaokeCookies(),
        )) {
            is AgentResult.Error -> return AgentResult.Error("AI 好课 token 校验失败：${result.message}", result.cause)
            is AgentResult.Ok -> result.value.first
        }
        val root = runCatching { LenientJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return AgentResult.Error("AI 好课 token 校验响应不是 JSON")
        if (root.int("code") == 401) return AgentResult.Error("AI 好课 haoke-token 已失效，请重新登录 AI 好课")
        val code = root["code"]?.jsonPrimitive?.contentOrNull
        if (code != null && code !in setOf("0", "200")) {
            return AgentResult.Error("AI 好课 token 校验返回异常：${text.take(200)}")
        }
        return if (aihaokeRows(root) != null) {
            AgentResult.Ok(Unit)
        } else {
            AgentResult.Error("AI 好课 token 校验失败：listMyClass 未返回课程列表字段")
        }
    }

    suspend fun fetchDeadlines(cookies: Map<String, String>): AgentResult<List<DeadlineItem>> {
        val token = cookies["haoke-token"].orEmpty().cleanBearerToken()
        if (token.isBlank()) return AgentResult.Error("AI 好课 haoke-token 未配置，请先登录")
        val headers = aihaokeHeaders(token)
        val courses = when (val result = fetchEnrolledCourses(token, cookies)) {
            is AgentResult.Error -> return result
            is AgentResult.Ok -> result.value
        }

        val items = coroutineScope {
            courses.map { course ->
                async { fetchCourseTasks(course, headers, cookies) }
            }.awaitAll().flatten()
        }
        return AgentResult.Ok(items.sortedBy { it.dueAt })
    }

    private suspend fun fetchEnrolledCourses(
        token: String,
        cookies: Map<String, String>,
    ): AgentResult<List<AihaokeCourse>> {
        val headers = aihaokeHeaders(token, referer = "https://sjtu.aihaoke.net/student/course")
        val requestBodies = listOf(
            """{"requestId":"${UUID.randomUUID()}"}""",
            """{"instanceName":"","classId":0,"requestId":"${UUID.randomUUID()}"}""",
        )

        var lastError: AgentResult.Error? = null
        for (body in requestBodies) {
            val text = when (val result = http.postJson(
                "https://sjtu.aihaoke.net/api/teach/instance/listMyClass",
                body,
                headers = headers,
                cookies = cookies.filterAihaokeCookies(),
            )) {
                is AgentResult.Error -> {
                    lastError = AgentResult.Error("AI 好课 listMyClass 请求失败：${result.message}", result.cause)
                    continue
                }
                is AgentResult.Ok -> result.value.first
            }

            val root = runCatching { LenientJson.parseToJsonElement(text).jsonObject }.getOrNull()
                ?: return AgentResult.Error("AI 好课 listMyClass 响应不是 JSON")
            if (root.int("code") == 401) {
                return AgentResult.Error("AI 好课 haoke-token 已失效，请重新登录 AI 好课")
            }
            val code = root["code"]?.jsonPrimitive?.contentOrNull
            if (code != null && code !in setOf("0", "200")) {
                lastError = AgentResult.Error("AI 好课 listMyClass 返回异常：${text.take(200)}")
                continue
            }

            val courses = parseAihaokeCourses(root)
            if (courses.isNotEmpty()) return AgentResult.Ok(courses)
            lastError = AgentResult.Error("AI 好课未识别到选修课程：${text.take(200)}")
        }

        return lastError ?: AgentResult.Error("AI 好课课程识别失败")
    }

    internal fun parseAihaokeCourses(root: JsonObject): List<AihaokeCourse> {
        val rows = aihaokeRows(root) ?: JsonArray(emptyList())

        return rows.mapNotNull { element ->
            val course = element as? JsonObject ?: return@mapNotNull null
            val classId = course.long("classId")
                .takeIf { it != 0L }
                ?: course.long("courseId").takeIf { it != 0L }
                ?: course.long("id").takeIf { it != 0L }
                ?: return@mapNotNull null
            val instanceId = course.long("instanceId")
                .takeIf { it != 0L }
                ?: course.long("id").takeIf { it != 0L }
                ?: classId
            val name = sequenceOf(
                course.string("instanceName"),
                course.string("courseName"),
                course.string("className"),
                course.string("name"),
            ).firstOrNull { it.isNotBlank() }?.trim()
                ?: "课程$classId"
            AihaokeCourse(name = name, courseId = classId, instanceId = instanceId)
        }
    }

    private suspend fun fetchCourseTasks(
        course: AihaokeCourse,
        headers: Map<String, String>,
        cookies: Map<String, String>,
    ): List<DeadlineItem> {
        val classId = course.courseId
        val courseName = course.name
        val out = mutableListOf<DeadlineItem>()
        var page = 1
        while (true) {
            val payload = buildJsonObject {
                put("classId", classId)
                put("orderType", 0)
                put("searchText", "")
                put("status", 0)
                put("taskTypes", JsonArray(emptyList()))
                put("requireFlag", 1)
                put("requestId", UUID.randomUUID().toString())
                put("page", buildJsonObject {
                    put("pageNo", page)
                    put("pageSize", 50)
                })
            }.toString()
            val result = http.postJson(
                "https://sjtu.aihaoke.net/api/learn/task/listTask",
                payload,
                headers = headers,
                cookies = cookies.filterAihaokeCookies(),
            )
            if (result !is AgentResult.Ok) break
            val root = LenientJson.parseToJsonElement(result.value.first).jsonObject
            if (root.int("code") == 401) break
            val data = root.obj("data")
            data.array("rowList").forEach { element ->
                val task = element as? JsonObject ?: return@forEach
                if (task.int("requireFlag") != 1 || task.int("myStatus") != 10) return@forEach
                val due = parseCampusInstant(task.string("endTime")) ?: return@forEach
                if (due <= Instant.now()) return@forEach
                out += DeadlineItem(
                    platform = "aihaoke",
                    course = courseName,
                    title = task.string("taskName", "未知任务").trim(),
                    dueAt = due,
                    submitted = false,
                    metadata = mapOf("class_id" to classId.toString()),
                )
            }
            if (page >= data.int("pageCount", 1)) break
            page += 1
        }
        return out
    }

    private fun aihaokeHeaders(token: String, referer: String? = null): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${token.cleanBearerToken()}")
        put("Content-Type", "application/json")
        referer?.let { put("Referer", it) }
    }

    private fun aihaokeRows(root: JsonObject): JsonArray? {
        return when (val payload = root["data"]) {
            is JsonObject -> payload.array("teachClassResponseList")
                .takeIf { it.isNotEmpty() || payload["teachClassResponseList"] is JsonArray }
                ?: payload.array("rowList").takeIf { it.isNotEmpty() || payload["rowList"] is JsonArray }
                ?: payload.array("list").takeIf { it.isNotEmpty() || payload["list"] is JsonArray }
            is JsonArray -> payload
            else -> null
        }
    }

    internal data class AihaokeCourse(
        val name: String,
        val courseId: Long,
        val instanceId: Long,
    )
}

private fun Map<String, String>.filterAihaokeCookies(): Map<String, String> =
    filterKeys { it.isNotBlank() && !it.contains("jaccount", ignoreCase = true) }

class IcourseClient(private val http: OkHttpClient) {
    suspend fun validateCookies(cookies: Map<String, String>): AgentResult<Unit> {
        if (cookies["NTESSTUDYSI"].isNullOrBlank()) {
            return AgentResult.Error("中国大学 MOOC 未获取到 NTESSTUDYSI，请确认已登录后再保存")
        }
        return when (val result = http.getString(
            "https://www.icourse163.org/",
            headers = mapOf("Referer" to "https://www.icourse163.org/"),
            cookies = cookies,
        )) {
            is AgentResult.Error -> AgentResult.Error("中国大学 MOOC 会话校验失败：${result.message}", result.cause)
            is AgentResult.Ok -> AgentResult.Ok(Unit)
        }
    }

    suspend fun fetchDeadlines(cookies: Map<String, String>, courses: List<IcourseCourse>): AgentResult<List<DeadlineItem>> {
        if (cookies.isEmpty()) return AgentResult.Error("中国大学 MOOC Cookie 未配置，请先登录")
        val all = courses.flatMap { course ->
            fetchCourse(cookies, course)
        }.sortedBy { it.dueAt }
        return AgentResult.Ok(all)
    }

    private suspend fun fetchCourse(cookies: Map<String, String>, course: IcourseCourse): List<DeadlineItem> {
        val form = mapOf("csrfKey" to cookies["NTESSTUDYSI"].orEmpty(), "termId" to course.termId.toString())
        val rpcUrl = "https://www.icourse163.org/web/j/courseBean.getLastLearnedMocTermDto.rpc"
        val headers = mapOf("Referer" to "https://www.icourse163.org/")

        // 第一次尝试调用 RPC
        val firstResult = http.postForm(rpcUrl, form, headers = headers, cookies = cookies)
        if (firstResult is AgentResult.Ok) {
            val root = LenientJson.parseToJsonElement(firstResult.value.first).jsonObject
            val result = root["result"] as? JsonObject
            if (result != null) {
                val moc = result.obj("mocTermDto").takeIf { it.isNotEmpty() } ?: result
                if (moc.array("chapters").isNotEmpty() || moc.array("exams").isNotEmpty()) {
                    return parseMocTerm(course.name, moc)
                }
            }
        }

        // icourse163 可能返回 code=0 但 result 为空，需要先访问首页预热会话
        Log.i("SJTUAgent.Icourse", "RPC returned empty result, warming up session by visiting homepage")
        http.getString("https://www.icourse163.org/", headers = headers, cookies = cookies)

        // 第二次尝试调用 RPC
        val retryResult = http.postForm(rpcUrl, form, headers = headers, cookies = cookies)
        if (retryResult !is AgentResult.Ok) return emptyList()
        val retryRoot = LenientJson.parseToJsonElement(retryResult.value.first).jsonObject
        val retryMoc = (retryRoot["result"] as? JsonObject)?.obj("mocTermDto") ?: (retryRoot["result"] as? JsonObject) ?: return emptyList()
        return parseMocTerm(course.name, retryMoc)
    }

    fun parseMocTerm(courseName: String, moc: JsonObject, now: Instant = Instant.now()): List<DeadlineItem> {
        val out = mutableListOf<DeadlineItem>()
        fun addIfPending(name: String, score: JsonElement?, deadlineMs: Long?, submitted: Boolean = false) {
            val scoreText = score?.jsonPrimitive?.contentOrNull
            if (!scoreText.isNullOrBlank() && scoreText.toDoubleOrNull()?.let { it > 0.0 } == true) return
            if (deadlineMs == null || deadlineMs == 0L) return
            val due = Instant.ofEpochMilli(deadlineMs)
            if (due < now) return
            out += DeadlineItem("icourse163", courseName, name.ifBlank { "未知测试" }, due, submitted)
        }
        moc.array("chapters").forEach { chapterEl ->
            val chapter = chapterEl as? JsonObject ?: return@forEach
            chapter.array("quizs").forEach { quizEl ->
                val quiz = quizEl as? JsonObject ?: return@forEach
                val test = quiz.obj("test")
                addIfPending(
                    name = quiz.string("name", "未知测试"),
                    score = test["userScore"] ?: test["testScore"],
                    deadlineMs = test.long("deadline").takeIf { it > 0 },
                    submitted = test.int("usedTryCount") > 0,
                )
            }
            chapter.array("lessons").forEach { lessonEl ->
                val lesson = lessonEl as? JsonObject ?: return@forEach
                lesson.array("units").forEach { unitEl ->
                    val unit = unitEl as? JsonObject ?: return@forEach
                    if (unit.int("contentType") != 5) return@forEach
                    addIfPending(
                        name = unit.string("name", "未知测试"),
                        score = unit["testScore"],
                        deadlineMs = (unit.long("deadline").takeIf { it > 0 } ?: unit.long("testEndTime").takeIf { it > 0 }),
                    )
                }
            }
        }
        moc.array("exams").forEach { examEl ->
            val exam = examEl as? JsonObject ?: return@forEach
            addIfPending(
                name = exam.string("name").ifBlank { exam.string("title", "未知考试") },
                score = exam["userScore"] ?: exam["testScore"],
                deadlineMs = exam.long("endTime").takeIf { it > 0 } ?: exam.long("deadline").takeIf { it > 0 },
            )
        }
        return out
    }
}

class JwxtClient(private val http: OkHttpClient) {
    suspend fun validateCookies(cookies: Map<String, String>): AgentResult<Unit> {
        if (cookies.isEmpty()) return AgentResult.Error("教务系统 Cookie 未配置，请先登录")
        val body = when (val result = http.postForm(
            "https://i.sjtu.edu.cn/kbcx/xskbcx_cxXsKb.html",
            form = mapOf("xnm" to "2025", "xqm" to "12"),
            headers = mapOf("User-Agent" to "Mozilla/5.0"),
            cookies = cookies,
        )) {
            is AgentResult.Error -> return AgentResult.Error("教务系统会话校验失败：${result.message}", result.cause)
            is AgentResult.Ok -> result.value.first
        }
        return if ("kbList" in body) {
            AgentResult.Ok(Unit)
        } else {
            AgentResult.Error("教务系统 session 已过期或未进入教务站点，请重新登录")
        }
    }

    suspend fun fetchSchedule(
        cookies: Map<String, String>,
        year: String = "",
        term: String = "",
    ): AgentResult<List<ScheduleCourse>> {
        if (cookies.isEmpty()) return AgentResult.Error("教务系统 Cookie 未配置，请先登录")
        val auto = currentAcademicYearTerm()
        val xnm = year.ifBlank { auto.first }
        val xqm = when (term.ifBlank { auto.second }) {
            "1" -> "3"
            "2" -> "12"
            "3" -> "16"
            else -> "12"
        }
        val body = when (val result = http.postForm(
            "https://i.sjtu.edu.cn/kbcx/xskbcx_cxXsKb.html",
            form = mapOf("xnm" to xnm, "xqm" to xqm),
            headers = mapOf("Referer" to "https://i.sjtu.edu.cn/"),
            cookies = cookies,
        )) {
            is AgentResult.Error -> return AgentResult.Error(result.message, result.cause)
            is AgentResult.Ok -> result.value.first
        }
        if (body.contains("jAccount", ignoreCase = true)) return AgentResult.Error("教务系统登录已过期")
        val courses = LenientJson.parseToJsonElement(body).jsonObject.array("kbList").mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            val slots = parseClassSlots(item.string("jcs", "1-1"))
            val weekSet = parseWeekSet(item.string("zcd"))
            ScheduleCourse(
                name = item.string("kcmc"),
                teacher = item.string("xm"),
                location = item.string("cdmc"),
                campus = item.string("xqmc"),
                day = item.int("xqj"),
                slotStart = slots.first,
                slotEnd = slots.second,
                timeStart = SlotTimes.getOrNull(slots.first - 1)?.first.orEmpty(),
                timeEnd = SlotTimes.getOrNull(slots.second - 1)?.second.orEmpty(),
                weeks = weekSet,
                weekText = item.string("zcd"),
            )
        }.sortedWith(compareBy({ it.day }, { it.slotStart }))
        return AgentResult.Ok(courses)
    }

    suspend fun queryGrades(
        cookies: Map<String, String>,
        year: String = "",
        semester: String = "",
    ): AgentResult<List<GradeItem>> {
        if (cookies.isEmpty()) return AgentResult.Error("教务系统 Cookie 未配置，请先登录")
        val pageBody = when (val page = http.getString(
            "https://i.sjtu.edu.cn/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005",
            cookies = cookies,
        )) {
            is AgentResult.Error -> return AgentResult.Error(page.message, page.cause)
            is AgentResult.Ok -> page.value.first
        }
        if (pageBody.contains("jAccount", ignoreCase = true)) return AgentResult.Error("教务系统登录已过期")
        val hidden = Jsoup.parse(pageBody).select("input[type=hidden]").associate {
            it.attr("name") to it.attr("value")
        }
        val xqm = mapOf("1" to "3", "2" to "12", "3" to "16")[semester].orEmpty()
        val form = hidden + mapOf(
            "xnm" to year,
            "xqm" to xqm,
            "kcbjdm" to "",
            "page" to "1",
            "rows" to "500",
            "sidx" to "xnm",
            "sord" to "desc",
            "_search" to "false",
            "nd" to System.currentTimeMillis().toString(),
            "zd_fzdm" to "N305005-xs",
        )
        val gradesBody = when (val result = http.postForm(
            "https://i.sjtu.edu.cn/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005",
            form = form,
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to "https://i.sjtu.edu.cn/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005",
            ),
            cookies = cookies,
        )) {
            is AgentResult.Error -> return AgentResult.Error(result.message, result.cause)
            is AgentResult.Ok -> result.value.first
        }
        val items = LenientJson.parseToJsonElement(gradesBody).jsonObject.array("items")
        return AgentResult.Ok(items.mapNotNull { el ->
            val item = el as? JsonObject ?: return@mapNotNull null
            GradeItem(
                year = "${item.string("xnm")}学年",
                semester = "第${item.string("xqmmc")}学期",
                courseId = item.string("kch"),
                courseName = item.string("kcmc"),
                score = item.string("cj"),
                gpa = item.string("jd"),
                credits = item.string("xf"),
                type = item.string("kcbj").trim(),
            )
        })
    }
}

class PhycaiClient(private val http: OkHttpClient) {
    suspend fun validateCookies(cookies: Map<String, String>): AgentResult<Unit> {
        if (cookies.isEmpty()) return AgentResult.Error("物理实验 Cookie 未配置，请先登录")
        val result = http.getString("http://www.phycai.sjtu.edu.cn/pe/student/select.aspx", cookies = cookies)
        if (result is AgentResult.Error) {
            return AgentResult.Error("物理实验会话校验失败：${result.message}", result.cause)
        }
        val ok = result as AgentResult.Ok
        val finalUrl = ok.value.second.request.url.toString()
        if ("login" in finalUrl.lowercase() || "Jlogin" in finalUrl) {
            return AgentResult.Error("物理实验 session 已过期或未登录，请重新通过 jAccount 登录")
        }
        val hasTable = Jsoup.parse(ok.value.first).select("table").isNotEmpty()
        return if (hasTable) AgentResult.Ok(Unit) else AgentResult.Error("物理实验页面未识别到实验表格，请确认登录成功后再保存")
    }

    suspend fun fetchNextLab(cookies: Map<String, String>): AgentResult<LabBooking?> {
        if (cookies.isEmpty()) return AgentResult.Error("物理实验 Cookie 未配置，请先登录")
        val body = when (val result = http.getString("http://www.phycai.sjtu.edu.cn/pe/student/select.aspx", cookies = cookies)) {
            is AgentResult.Error -> return AgentResult.Error(result.message, result.cause)
            is AgentResult.Ok -> result.value.first
        }
        val doc = Jsoup.parse(body)
        val table = doc.select("table").maxByOrNull { it.select("tr").size } ?: return AgentResult.Ok(null)
        val rows = table.select("tr")
        if (rows.size < 2) return AgentResult.Ok(null)
        val headers = rows.first()?.select("th,td")?.map { it.text().trim() }.orEmpty()
        fun indexOf(vararg names: String): Int = headers.indexOfFirst { header -> names.any { it in header } }
        val nameIdx = indexOf("实验项目", "实验名称", "项目名称", "项目")
        val dateIdx = indexOf("实验日期", "日期", "上课日期")
        val timeIdx = indexOf("实验时间", "时间", "上课时间")
        val roomIdx = indexOf("上课教室", "教室", "地点", "实验室")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val labs = rows.drop(1).mapNotNull { row ->
            val cells = row.select("td").map { it.text().trim() }
            fun cell(index: Int): String = if (index >= 0 && index < cells.size) cells[index] else ""
            val timeStart = Regex("\\d{1,2}:\\d{2}").find(cell(timeIdx))?.value ?: return@mapNotNull null
            val date = normalizeDate(cell(dateIdx)) ?: return@mapNotNull null
            val instant = runCatching {
                LocalDateTime.parse("$date $timeStart", formatter).atZone(ShanghaiZone).toInstant()
            }.getOrNull() ?: return@mapNotNull null
            if (instant <= Instant.now()) return@mapNotNull null
            LabBooking(
                name = cell(nameIdx),
                dateTime = instant,
                room = cell(roomIdx),
                timeText = cell(timeIdx),
            )
        }.sortedBy { it.dateTime }
        return AgentResult.Ok(labs.firstOrNull())
    }

    private fun normalizeDate(raw: String): String? {
        val direct = Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}").find(raw)?.value
        return direct?.replace("/", "-")?.split("-")?.let {
            if (it.size != 3) null else "%04d-%02d-%02d".format(it[0].toInt(), it[1].toInt(), it[2].toInt())
        }
    }
}

class CampusSearchClient(private val http: OkHttpClient) {
    suspend fun validateShuiyuanCookies(cookies: Map<String, String>): AgentResult<Unit> {
        if (cookies.isEmpty()) return AgentResult.Error("水源社区 Cookie 未配置，请先登录")
        val body = when (val result = http.getString(
            "https://shuiyuan.sjtu.edu.cn/session/current.json",
            headers = mapOf("Accept" to "application/json"),
            cookies = cookies,
        )) {
            is AgentResult.Error -> return AgentResult.Error("水源社区会话校验失败：${result.message}", result.cause)
            is AgentResult.Ok -> result.value.first
        }
        val root = runCatching { LenientJson.parseToJsonElement(body).jsonObject }.getOrNull()
        val currentUser = root?.get("current_user")
        return if (currentUser is JsonObject || cookies.keys.any { it.contains("session", ignoreCase = true) }) {
            AgentResult.Ok(Unit)
        } else {
            AgentResult.Error("水源社区 session 未识别到登录用户，请重新登录")
        }
    }

    suspend fun validateDywebToken(token: String): AgentResult<Unit> {
        val cleanToken = token.trim()
        if (cleanToken.isBlank()) return AgentResult.Error("传承·交大未获取到 sjtu_token，请先使用 jAccount 登录")
        val payload = buildJsonObject {
            put("keyword", "")
            put("page", 1)
            put("page_size", 1)
        }.toString()
        return when (val result = http.postJson(
            "https://api.share.dyweb.sjtu.cn/api/v1/course/search",
            payload,
            headers = mapOf("Origin" to "https://share.dyweb.sjtu.cn", "Referer" to "https://share.dyweb.sjtu.cn/"),
            cookies = mapOf("sjtu_token" to cleanToken),
        )) {
            is AgentResult.Error -> AgentResult.Error("传承·交大 token 校验失败：${result.message}", result.cause)
            is AgentResult.Ok -> AgentResult.Ok(Unit)
        }
    }

    suspend fun search(
        query: String,
        sites: Set<String>,
        maxResults: Int,
        shuiyuanApiKey: String,
        shuiyuanClientId: String,
        shuiyuanCookies: Map<String, String>,
        dywebToken: String,
    ): AgentResult<List<CampusSearchItem>> = coroutineScope {
        val tasks = mutableListOf<kotlinx.coroutines.Deferred<List<CampusSearchItem>>>()
        if ("jwc" in sites) tasks += async { searchJwc(query, maxResults) }
        if ("shuiyuan" in sites) tasks += async { searchShuiyuan(query, maxResults, shuiyuanApiKey, shuiyuanClientId, shuiyuanCookies) }
        if ("dyweb" in sites) tasks += async { searchDyweb(query, maxResults, dywebToken) }
        AgentResult.Ok(tasks.awaitAll().flatten())
    }

    private suspend fun searchJwc(query: String, maxResults: Int): List<CampusSearchItem> {
        val feed = "https://jwc.sjtu.edu.cn/system/resource/code/rss/rssfeed.jsp" +
            "?type=list&treeid=1292&viewid=1011878&mode=10&dbname=vsb" +
            "&owner=1707467176&ownername=jwc2021&contentid=1015253&number=50&httproot="
        val body = when (val result = http.getString(feed)) {
            is AgentResult.Error -> return listOf(CampusSearchItem("jwc", "教务处 RSS 获取失败", result.message))
            is AgentResult.Ok -> result.value.first
        }
        val doc = Jsoup.parse(body, "", Parser.xmlParser())
        val items = doc.select("item").map {
            CampusSearchItem(
                source = "jwc",
                title = it.selectFirst("title")?.text().orEmpty(),
                summary = it.selectFirst("description")?.text().orEmpty().take(300),
                url = it.selectFirst("link")?.text().orEmpty(),
                metadata = mapOf("date" to it.selectFirst("pubDate")?.text().orEmpty()),
            )
        }
        val q = query.lowercase()
        val matched = items.filter { q in it.title.lowercase() || q in it.summary.lowercase() }
        return (matched.ifEmpty { items }).take(maxResults)
    }

    private suspend fun searchShuiyuan(
        query: String,
        maxResults: Int,
        apiKey: String,
        clientId: String,
        cookies: Map<String, String>,
    ): List<CampusSearchItem> {
        if (apiKey.isBlank() && cookies.isEmpty()) {
            return listOf(CampusSearchItem("shuiyuan", "水源社区未配置", "请先登录水源或填写 User API Key"))
        }
        val headers = if (apiKey.isNotBlank()) {
            mapOf("User-Api-Key" to apiKey, "User-Api-Client-Id" to clientId, "Accept" to "application/json")
        } else {
            mapOf("Accept" to "application/json")
        }
        val url = "https://shuiyuan.sjtu.edu.cn/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("page", "1")
            .build()
            .toString()
        val body = when (val result = http.getString(url, headers = headers, cookies = cookies)) {
            is AgentResult.Error -> return listOf(CampusSearchItem("shuiyuan", "水源搜索失败", result.message))
            is AgentResult.Ok -> result.value.first
        }
        val root = LenientJson.parseToJsonElement(body).jsonObject
        val posts = root.array("posts").mapNotNull { it as? JsonObject }.associateBy { it.long("topic_id") }
        return root.array("topics").take(maxResults).mapNotNull { topicEl ->
            val topic = topicEl as? JsonObject ?: return@mapNotNull null
            val topicId = topic.long("id")
            val slug = topic.string("slug")
            val post = posts[topicId]
            CampusSearchItem(
                source = "shuiyuan",
                title = topic.string("fancy_title").ifBlank { topic.string("title") },
                summary = post?.string("blurb").orEmpty(),
                url = "https://shuiyuan.sjtu.edu.cn/t/$slug/$topicId",
                metadata = mapOf(
                    "views" to topic.long("views").toString(),
                    "replies" to topic.int("posts_count").toString(),
                ),
            )
        }
    }

    private suspend fun searchDyweb(query: String, maxResults: Int, token: String): List<CampusSearchItem> {
        if (token.isBlank()) {
            return listOf(CampusSearchItem("dyweb", "传承·交大未登录", "请先通过 Web 登录保存 sjtu_token"))
        }
        val coursePayload = buildJsonObject {
            put("keyword", query)
            put("page", 1)
            put("page_size", maxResults)
        }.toString()
        val headers = mapOf("Origin" to "https://share.dyweb.sjtu.cn", "Referer" to "https://share.dyweb.sjtu.cn/")
        val body = when (val coursesResult = http.postJson(
            "https://api.share.dyweb.sjtu.cn/api/v1/course/search",
            coursePayload,
            headers = headers,
            cookies = mapOf("sjtu_token" to token),
        )) {
            is AgentResult.Error -> return listOf(CampusSearchItem("dyweb", "传承·交大搜索失败", coursesResult.message))
            is AgentResult.Ok -> coursesResult.value.first
        }
        val courses = LenientJson.parseToJsonElement(body).jsonObject["data"] as? JsonArray ?: JsonArray(emptyList())
        return courses.take(maxResults).mapNotNull { courseEl ->
            val course = courseEl as? JsonObject ?: return@mapNotNull null
            val org = course.obj("organization").string("name")
            val id = course.long("id")
            CampusSearchItem(
                source = "dyweb",
                title = course.string("name"),
                summary = listOf(course.string("code"), org).filter { it.isNotBlank() }.joinToString(" · "),
                url = "https://share.dyweb.sjtu.cn/course/$id",
            )
        }
    }
}
