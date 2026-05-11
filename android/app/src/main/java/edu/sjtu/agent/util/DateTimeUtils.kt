package edu.sjtu.agent.util

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val ShanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")

fun parseCampusInstant(raw: String?): Instant? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    runCatching {
        return Instant.parse(text.replace("Z", "Z"))
    }
    runCatching {
        return java.time.OffsetDateTime.parse(text).toInstant()
    }
    val patterns = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd",
    )
    for (pattern in patterns) {
        runCatching {
            val formatter = DateTimeFormatter.ofPattern(pattern)
            val local = if (pattern.contains("H")) {
                LocalDateTime.parse(text, formatter)
            } else {
                LocalDate.parse(text, formatter).atStartOfDay()
            }
            return local.atZone(ShanghaiZone).toInstant()
        }
    }
    return null
}

fun Instant.formatCampusTime(): String {
    val formatter = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ShanghaiZone)
    return formatter.format(this)
}

fun Instant.relativeFromNow(now: Instant = Instant.now()): String {
    val duration = Duration.between(now, this)
    val past = duration.isNegative
    val abs = duration.abs()
    val prefix = if (past) "已过" else "还有"
    return when {
        abs.toDays() >= 1 -> "$prefix ${abs.toDays()} 天"
        abs.toHours() >= 1 -> "$prefix ${abs.toHours()} 小时"
        else -> "$prefix ${abs.toMinutes().coerceAtLeast(0)} 分钟"
    }
}

fun currentAcademicYearTerm(now: LocalDate = LocalDate.now(ShanghaiZone)): Pair<String, String> {
    val year = now.year
    return when {
        now.monthValue >= 9 -> year.toString() to "1"
        now.monthValue == 1 -> (year - 1).toString() to "1"
        else -> (year - 1).toString() to "2"
    }
}

fun parseWeekSet(text: String): List<Int> {
    val weeks = linkedSetOf<Int>()
    text.split(",").forEach { raw ->
        var part = raw.trim()
        var step = 1
        val oddOnly = part.contains("(单)")
        val evenOnly = part.contains("(双)")
        if (oddOnly || evenOnly) {
            step = 2
            part = part.replace("(单)", "").replace("(双)", "")
        }
        part = part.replace("周", "").trim()
        if (part.isEmpty()) return@forEach
        if ("-" in part) {
            val start = part.substringBefore("-").toIntOrNull() ?: return@forEach
            val end = part.substringAfter("-").toIntOrNull() ?: return@forEach
            val first = when {
                oddOnly && start % 2 == 0 -> start + 1
                evenOnly && start % 2 == 1 -> start + 1
                else -> start
            }
            for (week in first..end step step) weeks.add(week)
        } else {
            part.toIntOrNull()?.let(weeks::add)
        }
    }
    return weeks.toList()
}

fun parseClassSlots(raw: String): Pair<Int, Int> {
    val parts = raw.split("-")
    val start = parts.firstOrNull()?.toIntOrNull() ?: 1
    val end = parts.lastOrNull()?.toIntOrNull() ?: start
    return start to end
}

val SlotTimes: List<Pair<String, String>> = listOf(
    "8:00" to "8:45",
    "8:55" to "9:40",
    "10:00" to "10:45",
    "10:55" to "11:40",
    "12:00" to "12:45",
    "12:55" to "13:40",
    "14:00" to "14:45",
    "14:55" to "15:40",
    "16:00" to "16:45",
    "16:55" to "17:40",
    "18:00" to "18:45",
    "18:55" to "19:40",
    "20:00" to "20:45",
    "20:55" to "21:40",
)
