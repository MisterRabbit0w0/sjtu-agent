package edu.sjtu.agent

import edu.sjtu.agent.network.IcourseClient
import edu.sjtu.agent.network.AihaokeClient
import edu.sjtu.agent.network.defaultHttpClient
import edu.sjtu.agent.network.parseCookieHeader
import edu.sjtu.agent.util.LenientJson
import edu.sjtu.agent.util.parseClassSlots
import edu.sjtu.agent.util.parseWeekSet
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant

class ParserTest {
    @Test
    fun parsesWeekRangesAndParity() {
        assertEquals(listOf(1, 2, 3, 4), parseWeekSet("1-4周"))
        assertEquals(listOf(1, 3, 5), parseWeekSet("1-6周(单)"))
        assertEquals(listOf(2, 4, 6), parseWeekSet("1-6周(双)"))
        assertEquals(listOf(1, 3, 5, 6), parseWeekSet("1,3,5-6周"))
    }

    @Test
    fun parsesClassSlots() {
        assertEquals(3 to 4, parseClassSlots("3-4"))
        assertEquals(5 to 5, parseClassSlots("5"))
    }

    @Test
    fun parsesIcourseRpcDeadlines() {
        val future = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
        val json = """
            {
              "chapters": [
                {
                  "quizs": [
                    {"name": "第一章测试", "test": {"deadline": $future, "usedTryCount": 0}}
                  ],
                  "lessons": [
                    {"units": [
                      {"contentType": 5, "name": "单元测验", "deadline": $future}
                    ]}
                  ]
                }
              ],
              "exams": [
                {"name": "期末考试", "endTime": $future}
              ]
            }
        """.trimIndent()
        val client = IcourseClient(defaultHttpClient())
        val items = client.parseMocTerm("大学物理", LenientJson.parseToJsonElement(json).jsonObject)
        assertEquals(3, items.size)
        assertFalse(items.any { it.submitted })
    }

    @Test
    fun parsesAihaokeCourseShapes() {
        val client = AihaokeClient(defaultHttpClient())
        val teachClassJson = """
            {
              "code": 0,
              "data": {
                "teachClassResponseList": [
                  {"classId": 123, "instanceId": 456, "instanceName": "人工智能导论"}
                ]
              }
            }
        """.trimIndent()
        val rowListJson = """
            {
              "code": 200,
              "data": {
                "rowList": [
                  {"courseId": 789, "courseName": "程序设计"}
                ]
              }
            }
        """.trimIndent()

        val teachClasses = client.parseAihaokeCourses(LenientJson.parseToJsonElement(teachClassJson).jsonObject)
        val rowList = client.parseAihaokeCourses(LenientJson.parseToJsonElement(rowListJson).jsonObject)

        assertEquals(123L, teachClasses.single().courseId)
        assertEquals(456L, teachClasses.single().instanceId)
        assertEquals("人工智能导论", teachClasses.single().name)
        assertEquals(789L, rowList.single().courseId)
        assertEquals("程序设计", rowList.single().name)
    }

    @Test
    fun parsesCookieHeadersForWebAuth() {
        val cookies = parseCookieHeader("sjtu_token=abc.def==; NTESSTUDYSI=xyz; empty=")

        assertEquals("abc.def==", cookies["sjtu_token"])
        assertEquals("xyz", cookies["NTESSTUDYSI"])
        assertEquals("", cookies["empty"])
    }
}
