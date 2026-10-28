package org.tasks.importer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class ScheduleDraft(
    val id: Int,
    val title: String,
    val date: String,
    val start: String = "",
    val end: String = "",
    val notes: String = "",
    val selected: Boolean = true,
    val originallyCompleted: Boolean = false,
)

data class ScheduleEntry(
    val title: String,
    val date: LocalDate,
    val start: LocalTime?,
    val end: LocalTime?,
    val nextDay: Boolean,
    val notes: String,
) {
    fun startMillis(zone: ZoneId): Long = date.atTime(start ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
    fun endMillis(zone: ZoneId): Long = date.plusDays(if (nextDay) 1 else 0)
        .atTime(end ?: start ?: LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
    fun key(): String = scheduleKey(title, date.toString(), start?.toString().orEmpty(), (end ?: start)?.toString().orEmpty(), nextDay)
    fun taskNotes(): String = buildList {
        add("计划日期：$date")
        if (start != null) add("计划时间：${start.format(TIME)}" + (end?.let { "–${if (nextDay) "次日" else ""}${it.format(TIME)}" } ?: ""))
        if (notes.isNotBlank()) add(notes.trim())
    }.joinToString("\n")

    companion object { private val TIME = DateTimeFormatter.ofPattern("HH:mm") }
}

fun scheduleKey(title: String, date: String, start: String, end: String, nextDay: Boolean): String =
    listOf(title.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT), date, start, end, nextDay.toString()).joinToString("\u001f")

data class ScheduleValidation(val entry: ScheduleEntry? = null, val error: String? = null)
data class ScheduleParseResult(val items: List<ScheduleDraft>, val skippedLines: Int, val error: String? = null)

/** Deterministic, offline parsing. Never invents a time for an untimed item. */
object ScheduleTextParser {
    const val MAX_ITEMS = 200
    private val isoDate = DateTimeFormatter.ofPattern("uuuu-M-d").withResolverStyle(ResolverStyle.STRICT)
    private val absoluteDate = Regex("(?<!\\d)(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?!\\d)")
    private val monthDate = Regex("(?<!\\d)(\\d{1,2})月(\\d{1,2})日")
    private val relativeDate = Regex("今天|今日|明天|明日|后天")
    private val bullet = Regex("^(?:[-*+•●]\\s+|(?:\\d+|[一二三四五六七八九十]+)[.、．)）]\\s*|[（(](?:\\d+|[一二三四五六七八九十]+)[)）]\\s*)")
    private val checkbox = Regex("^\\[([ xX✓✔])]\\s*")
    private val clock = "(?:凌晨|早上|上午|中午|下午|晚上)?\\d{1,2}(?:[:：]\\d{1,2}|点(?:\\d{1,2}分?)?)"
    private val times = Regex("^($clock)\\s*(?:[-–—~～至到]\\s*((?:次日|翌日)?$clock))?\\s*[：:]?\\s*")
    private val timeValue = Regex("^(凌晨|早上|上午|中午|下午|晚上)?(\\d{1,2})(?:[:：](\\d{1,2})|点(?:(\\d{1,2})分?)?)$")
    private val focus = Regex("^#{0,6}\\s*(?:明日规划|明日计划|明天安排|明日安排|明日任务)(?!日期)")
    private val excludedSection = Regex("^(?:待确认|修订记录|更新记录|复盘|时间或精力不足|精力不足时|调整方案|依据与待调整)")

    fun parse(text: String, defaultDate: LocalDate, today: LocalDate = LocalDate.now()): ScheduleParseResult {
        if (text.length > 100_000) return ScheduleParseResult(emptyList(), 0, "文字过长，请分批导入。")
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
        val focusIndex = lines.indexOfFirst { focus.containsMatchIn(it.trim()) }
        val content = if (focusIndex >= 0) lines.drop(focusIndex) else lines
        val structured = content.any { line ->
            val trimmed = line.trim().replace("**", "")
            bullet.containsMatchIn(trimmed) || checkbox.containsMatchIn(trimmed) || times.containsMatchIn(trimmed) || trimmed.startsWith('|')
        }
        val preambleDate = if (focusIndex >= 0) lines.take(focusIndex).mapNotNull { line ->
            if (Regex("规划日期|计划日期|目标日期").containsMatchIn(line)) findDate(line, defaultDate.year, today)?.second else null
        }.lastOrNull() else null
        var activeDate = preambleDate ?: defaultDate.toString()
        var skipped = 0
        var excluded = false
        var table: List<String>? = null
        val result = mutableListOf<ScheduleDraft>()

        for (raw in content) {
            var line = raw.trim().replace("**", "")
            if (line.isBlank() || line.startsWith("```") || line.matches(Regex("[-*_]{3,}"))) continue
            val heading = line.startsWith('#')
            line = line.replace(Regex("^#{1,6}\\s*"), "")
            if (heading) excluded = excludedSection.containsMatchIn(line)
            if (excluded || excludedSection.containsMatchIn(line)) { skipped++; continue }

            val dateFound = findDate(line, defaultDate.year, today)?.takeUnless {
                preambleDate != null && heading && focus.containsMatchIn(raw.trim()) && absoluteDate.find(line) == null && monthDate.find(line) == null
            }
            val dateAtStart = dateFound?.let { it.first.range.first == 0 } == true
            val dateHeading = heading || Regex("^(日期|计划日期|目标日期|明日规划|明日计划|明天安排|明日安排|明日任务)[：: ]").containsMatchIn(line)
            if (dateFound != null && (dateHeading || dateAtStart && line.substring(dateFound.first.range.last + 1).trim().matches(Regex("(?:[（(].*[)）])?")))) {
                activeDate = dateFound.second
                skipped++
                continue
            }
            if (heading || line in setOf("主要任务", "任务清单", "时间安排", "生活与收尾", "安排", "待办")) { skipped++; continue }

            var notes = ""
            var explicitDate: String? = null
            var tableRow = false
            if (line.startsWith('|')) {
                val cells = line.trim('|').split(Regex("(?<!\\\\)\\|")).map { it.trim().replace("\\|", "|") }
                if (cells.all { it.matches(Regex(":?-{2,}:?")) }) continue
                if (cells.any { it in setOf("时间", "时段", "安排", "任务", "事项", "内容", "待办") }) { table = cells; skipped++; continue }
                val columns = table
                val titleIndex = columns?.indexOfFirst { it in setOf("安排", "任务", "事项", "内容", "待办") } ?: 1
                val timeIndex = columns?.indexOfFirst { it in setOf("时间", "时段") } ?: 0
                val dateIndex = columns?.indexOfFirst { it == "日期" } ?: -1
                if (titleIndex !in cells.indices) { skipped++; continue }
                explicitDate = cells.getOrNull(dateIndex)?.let { findDate(it, defaultDate.year, today)?.second ?: it }
                notes = cells.filterIndexed { index, _ -> index != titleIndex && index != timeIndex && index != dateIndex }.joinToString("；").takeUnless { it == "—" }.orEmpty()
                line = listOfNotNull(cells.getOrNull(timeIndex)?.takeUnless { it in setOf("—", "-", "") }, cells[titleIndex]).joinToString(" ")
                tableRow = true
            } else table = null

            var listed = false
            var completed = false
            repeat(2) {
                bullet.find(line)?.let { listed = true; line = line.substring(it.range.last + 1).trim() }
                checkbox.find(line)?.let { listed = true; completed = it.groupValues[1].isNotBlank(); line = line.substring(it.range.last + 1).trim() }
            }
            if (line.isBlank()) continue
            val inlineDate = findDate(line, defaultDate.year, today)?.takeIf { it.first.range.first == 0 }
            if (inlineDate != null) {
                explicitDate = inlineDate.second
                line = line.substring(inlineDate.first.range.last + 1).trim().trimStart('：', ':').trim()
            }
            val time = times.find(line)
            if (!tableRow && !listed && time == null && structured) {
                if (raw.firstOrNull()?.isWhitespace() == true && result.isNotEmpty()) {
                    result[result.lastIndex] = result.last().copy(notes = listOf(result.last().notes, line).filter(String::isNotBlank).joinToString("\n"))
                } else skipped++
                continue
            }
            val start = time?.groupValues?.get(1)?.let { normalizeTime(it, false) }.orEmpty()
            val end = time?.groupValues?.get(2)?.takeIf(String::isNotBlank)?.let {
                val inherited = if (!it.startsWith("次日") && !it.startsWith("翌日") && !Regex("凌晨|早上|上午|中午|下午|晚上").containsMatchIn(it)) Regex("^(凌晨|早上|上午|中午|下午|晚上)").find(time?.groupValues?.get(1).orEmpty())?.value.orEmpty() else ""
                val nextPrefix = if (it.startsWith("次日")) "次日" else if (it.startsWith("翌日")) "翌日" else ""
                normalizeTime(nextPrefix + inherited + it.removePrefix("次日").removePrefix("翌日"), true)
            }.orEmpty()
            val title = if (time != null) line.substring(time.range.last + 1).trim() else line.trim()
            if (title.isBlank() && time == null) { skipped++; continue }
            result += ScheduleDraft(result.size, title, explicitDate ?: activeDate, start, end, notes, selected = !completed, originallyCompleted = completed)
        }
        return ScheduleParseResult(result, skipped, if (result.size > MAX_ITEMS) "一次最多导入${MAX_ITEMS}条，请减少文字后重新预览。" else null)
    }

    fun validate(draft: ScheduleDraft, zone: ZoneId = ZoneId.systemDefault()): ScheduleValidation {
        if (draft.title.isBlank()) return ScheduleValidation(error = "请填写任务名称")
        val date = try { LocalDate.parse(draft.date.trim(), isoDate) } catch (_: Exception) { return ScheduleValidation(error = "日期无效，请使用 YYYY-MM-DD") }
        val startText = draft.start.trim()
        val endText = draft.end.trim()
        val start = if (startText.isBlank()) null else parseTime(startText) ?: return ScheduleValidation(error = "开始时间无效，请使用 HH:mm")
        if (start == null && endText.isNotBlank()) return ScheduleValidation(error = "填写结束时间前，请先填写开始时间")
        val nextDay = endText.startsWith("次日") || endText.startsWith("翌日") || endText == "24:00"
        val end = if (endText.isBlank()) null else if (endText == "24:00") LocalTime.MIDNIGHT else parseTime(endText.removePrefix("次日").removePrefix("翌日")) ?: return ScheduleValidation(error = "结束时间无效，请使用 HH:mm；跨日可写次日00:30")
        if (start != null && end != null && !nextDay && end <= start) return ScheduleValidation(error = "结束时间应晚于开始；跨日请写次日或24:00")
        for ((day, time) in listOf(date to start, date.plusDays(if (nextDay) 1 else 0) to end)) {
            if (time != null && zone.rules.getValidOffsets(LocalDateTime.of(day, time)).isEmpty()) return ScheduleValidation(error = "此时间在当前时区不存在，请修改")
        }
        return ScheduleValidation(ScheduleEntry(draft.title.trim(), date, start, end, nextDay, draft.notes))
    }

    private fun findDate(text: String, year: Int, today: LocalDate): Pair<MatchResult, String>? {
        absoluteDate.find(text)?.let { m ->
            val value = "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
            return m to runCatching { LocalDate.parse(value, isoDate).toString() }.getOrDefault(value)
        }
        monthDate.find(text)?.let { m ->
            val value = "$year-${m.groupValues[1]}-${m.groupValues[2]}"
            return m to runCatching { LocalDate.parse(value, isoDate).toString() }.getOrDefault(value)
        }
        relativeDate.find(text)?.let { m -> return m to today.plusDays(when (m.value) { "明天", "明日" -> 1; "后天" -> 2; else -> 0 }).toString() }
        return null
    }

    private fun normalizeTime(value: String, isEnd: Boolean): String {
        val next = value.startsWith("次日") || value.startsWith("翌日")
        val raw = value.removePrefix("次日").removePrefix("翌日")
        if (isEnd && raw.replace('：', ':') == "24:00") return "24:00"
        return parseTime(raw)?.format(DateTimeFormatter.ofPattern("HH:mm"))?.let { (if (next) "次日" else "") + it } ?: value
    }

    private fun parseTime(value: String): LocalTime? {
        val m = timeValue.matchEntire(value) ?: return null
        var hour = m.groupValues[2].toIntOrNull() ?: return null
        val minute = (m.groupValues[3].ifBlank { m.groupValues[4] }).ifBlank { "0" }.toIntOrNull() ?: return null
        val period = m.groupValues[1]
        if (period.isNotEmpty() && hour !in 0..12) return null
        if (period in setOf("下午", "晚上") && hour < 12 || period == "中午" && hour < 11) hour += 12
        if (period in setOf("凌晨", "上午", "早上") && hour == 12) hour = 0
        return runCatching { LocalTime.of(hour, minute) }.getOrNull()
    }
}
