package edu.sjtu.agent.llm

import edu.sjtu.agent.data.AgentResult
import edu.sjtu.agent.data.CampusRepository
import edu.sjtu.agent.util.formatCampusTime
import edu.sjtu.agent.util.relativeFromNow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ToolRouter(private val repository: CampusRepository) {
    val toolsJson: String = """
        [
          {"type":"function","function":{"name":"get_ddls","description":"获取 Canvas、AI 好课、MOOC 未完成 DDL","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"get_schedule","description":"查询今日课表","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"query_grades","description":"查询成绩和绩点","parameters":{"type":"object","properties":{"year":{"type":"string"},"semester":{"type":"string"}}}}},
          {"type":"function","function":{"name":"search_campus","description":"搜索教务处、水源社区、传承交大","parameters":{"type":"object","properties":{"query":{"type":"string"},"sites":{"type":"array","items":{"type":"string","enum":["jwc","shuiyuan","dyweb"]}}},"required":["query"]}}},
          {"type":"function","function":{"name":"add_reminder","description":"添加提醒事项","parameters":{"type":"object","properties":{"title":{"type":"string"},"start":{"type":"string"},"end":{"type":"string"},"note":{"type":"string"}},"required":["title","start"]}}},
          {"type":"function","function":{"name":"list_reminders","description":"列出提醒事项","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"remove_reminder","description":"删除提醒事项","parameters":{"type":"object","properties":{"id":{"type":"integer"}},"required":["id"]}}}
        ]
    """.trimIndent()

    suspend fun runTool(name: String, arguments: JsonObject): String = when (name) {
        "get_ddls" -> {
            when (val result = repository.refreshDeadlines()) {
                is AgentResult.Error -> errorJson(result.message)
                is AgentResult.Ok -> buildJsonObject {
                    put("ddls", buildJsonArray {
                        result.value.forEach { item ->
                            add(buildJsonObject {
                                put("platform", item.platform)
                                put("course", item.course)
                                put("name", item.title)
                                put("due", item.dueAt.toString())
                                put("due_display", item.dueAt.formatCampusTime())
                                put("time_left", item.dueAt.relativeFromNow())
                                put("submitted", item.submitted)
                                put("url", item.url)
                            })
                        }
                    })
                }.toString()
            }
        }
        "get_schedule" -> {
            when (val result = repository.fetchSchedule()) {
                is AgentResult.Error -> errorJson(result.message)
                is AgentResult.Ok -> buildJsonObject {
                    put("courses", buildJsonArray {
                        result.value.forEach { course ->
                            add(buildJsonObject {
                                put("name", course.name)
                                put("teacher", course.teacher)
                                put("location", course.location)
                                put("campus", course.campus)
                                put("day", course.day)
                                put("slot_start", course.slotStart)
                                put("slot_end", course.slotEnd)
                                put("time", "${course.timeStart}-${course.timeEnd}")
                                put("week_text", course.weekText)
                            })
                        }
                    })
                }.toString()
            }
        }
        "query_grades" -> {
            val year = arguments.string("year")
            val semester = arguments.string("semester")
            when (val result = repository.queryGrades(year, semester)) {
                is AgentResult.Error -> errorJson(result.message)
                is AgentResult.Ok -> buildJsonObject {
                    put("grades", buildJsonArray {
                        result.value.forEach { grade ->
                            add(buildJsonObject {
                                put("course", grade.courseName)
                                put("score", grade.score)
                                put("gpa", grade.gpa)
                                put("credits", grade.credits)
                                put("semester", grade.semester)
                            })
                        }
                    })
                }.toString()
            }
        }
        "search_campus" -> {
            val query = arguments.string("query")
            val sites = (arguments["sites"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                ?: setOf("jwc", "shuiyuan", "dyweb")
            when (val result = repository.searchCampus(query, sites)) {
                is AgentResult.Error -> errorJson(result.message)
                is AgentResult.Ok -> buildJsonObject {
                    put("results", buildJsonArray {
                        result.value.forEach { item ->
                            add(buildJsonObject {
                                put("source", item.source)
                                put("title", item.title)
                                put("summary", item.summary)
                                put("url", item.url)
                            })
                        }
                    })
                }.toString()
            }
        }
        "add_reminder" -> {
            val title = arguments.string("title")
            val start = arguments.string("start")
            val end = arguments.string("end")
            val note = arguments.string("note")
            when (val result = repository.addReminder(title, start, end, note)) {
                is AgentResult.Error -> errorJson(result.message)
                is AgentResult.Ok -> buildJsonObject {
                    put("ok", true)
                    put("id", result.value.id)
                    put("title", result.value.title)
                    put("start", result.value.startAt)
                }.toString()
            }
        }
        "list_reminders" -> {
            when (val result = repository.listReminders()) {
                is AgentResult.Error -> errorJson(result.message)
                is AgentResult.Ok -> buildJsonObject {
                    put("reminders", buildJsonArray {
                        result.value.forEach { reminder ->
                            add(buildJsonObject {
                                put("id", reminder.id)
                                put("title", reminder.title)
                                put("start", reminder.startAt)
                                put("end", reminder.endAt)
                                put("note", reminder.note)
                                put("expired", reminder.expired)
                            })
                        }
                    })
                }.toString()
            }
        }
        "remove_reminder" -> {
            val id = arguments["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: -1L
            when (val result = repository.removeReminder(id)) {
                is AgentResult.Error -> errorJson(result.message)
                is AgentResult.Ok -> """{"ok":true,"removed_id":$id}"""
            }
        }
        else -> errorJson("未知工具: $name")
    }

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun errorJson(message: String): String = buildJsonObject {
        put("error", JsonPrimitive(message))
    }.toString()
}
