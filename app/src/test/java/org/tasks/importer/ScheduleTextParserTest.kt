package org.tasks.importer

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ScheduleTextParserTest {
    private val today = LocalDate.of(2026, 10, 27)
    private val tomorrow = today.plusDays(1)
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun parse(text: String) = ScheduleTextParser.parse(text.trimIndent(), tomorrow, today)
    private fun valid(item: ScheduleDraft) = ScheduleTextParser.validate(item, zone).also { assertNull(it.error) }.entry!!

    @Test fun mixedTimedAndUntimedImportPreservesTimesAndNotes() {
        val result = parse("""
            日期：2026-10-28
            08:00—09:00 统计信号分析
            - [ ] 英语复习
            2、整理水声笔记
                完成标准：列出两个疑问
        """)
        assertEquals(3, result.items.size)
        assertEquals(LocalTime.of(8, 0), valid(result.items[0]).start)
        assertEquals(LocalTime.of(9, 0), valid(result.items[0]).end)
        assertNull(valid(result.items[1]).start)
        assertNull(valid(result.items[1]).end)
        assertEquals("完成标准：列出两个疑问", result.items[2].notes)
    }

    @Test fun markdownTableKeepsCompletionCriteria() {
        val result = parse("""
            ## 明日规划：2026-10-28（周三）
            | 时间 | 安排 | 完成标准或说明 |
            | --- | --- | --- |
            | 07:00—07:45 | 早餐与出门 | 预留通勤 |
            | 08:00—09:35 | 上课 | 记录作业 |
        """)
        assertEquals(2, result.items.size)
        assertEquals("早餐与出门", result.items[0].title)
        assertEquals("预留通勤", result.items[0].notes)
        assertEquals(tomorrow, valid(result.items[1]).date)
    }

    @Test fun tableWithDateAndNoTimeSupportsChecklists() {
        val result = parse("""
            | 日期 | 任务 | 备注 |
            | --- | --- | --- |
            | 2026-10-29 | 阅读教材 | 完成6.6 |
        """)
        assertEquals("2026-10-29", result.items.single().date)
        assertEquals("阅读教材", result.items.single().title)
        assertNull(valid(result.items.single()).start)
    }

    @Test fun relativeDateResolvedBeforeImport() {
        val result = parse("日期：后天\n- [ ] 作业")
        assertEquals("2026-10-29", result.items.single().date)
    }

    @Test fun inlineDatesDoNotLeakToOtherRows() {
        val result = parse("2026/10/29 08:00-09:00 开会\n- [ ] 复习")
        assertEquals("2026-10-29", result.items[0].date)
        assertEquals(tomorrow.toString(), result.items[1].date)
    }

    @Test fun completedCheckboxIsNotImportedByDefault() {
        val result = parse("- [x] 已做过\n- [ ] 未开始")
        assertFalse(result.items[0].selected)
        assertTrue(result.items[0].originallyCompleted)
        assertTrue(result.items[1].selected)
    }

    @Test fun invalidCalendarDateIsNotNormalized() {
        val item = parse("日期：2026-02-30\n- [ ] 作业").items.single()
        assertNull(ScheduleTextParser.validate(item, zone).entry)
    }

    @Test fun invalidClockIsKeptForCorrection() {
        val item = parse("25:90-26:00 作业").items.single()
        assertEquals("25:90", item.start)
        assertNotNull(ScheduleTextParser.validate(item, zone).error)
    }

    @Test fun reversedRangeRequiresExplicitNextDay() {
        assertNotNull(ScheduleTextParser.validate(parse("23:00-00:30 阅读").items.single(), zone).error)
        val entry = valid(parse("23:00-次日00:30 阅读").items.single())
        assertTrue(entry.nextDay)
        assertEquals(90 * 60 * 1000L, entry.endMillis(zone) - entry.startMillis(zone))
    }

    @Test fun midnightEndIsNextDayButMidnightStartIsInvalid() {
        val entry = valid(parse("23:00-24:00 阅读").items.single())
        assertTrue(entry.nextDay)
        assertEquals(LocalTime.MIDNIGHT, entry.end)
        assertNotNull(ScheduleTextParser.validate(parse("24:00-01:00 阅读").items.single(), zone).error)
    }

    @Test fun chinesePeriodsAndFullWidthColon() {
        val entry = valid(parse("下午2点30-3点 开会").items.single())
        assertEquals(LocalTime.of(14, 30), entry.start)
        assertEquals(LocalTime.of(15, 0), entry.end)
        assertEquals(LocalTime.of(8, 0), valid(parse("08：00～09：00 复习").items.single()).start)
    }

    @Test fun explicitOvernightChinesePeriod() {
        val entry = valid(parse("晚上11点-次日凌晨1点 阅读").items.single())
        assertEquals(LocalTime.of(23, 0), entry.start)
        assertEquals(LocalTime.of(1, 0), entry.end)
        assertTrue(entry.nextDay)
    }

    @Test fun singleTimeDoesNotInventAnEnd() {
        val entry = valid(parse("14:00 开会").items.single())
        assertEquals(LocalTime.of(14, 0), entry.start)
        assertNull(entry.end)
    }

    @Test fun untimedItemStaysDateOnlyAndHasNoDuration() {
        val entry = valid(parse("- [ ] CMOS-002 阅读6.6.1").items.single())
        assertNull(entry.start)
        assertNull(entry.end)
        assertEquals("CMOS-002 阅读6.6.1", entry.title)
    }

    @Test fun dailyReportBeforePlanIsNotImported() {
        val result = parse("""
            # 每日记录
            明日规划日期：2026-10-12（周一）
            ## 当日报告
            - 今天读完6.6
            ## 明日规划：第一版
            ### 主要任务
            1. 复习统计信号分析
            ### 待确认
            - 询问课程版本
        """)
        assertEquals(1, result.items.size)
        assertEquals("2026-10-12", result.items.single().date)
        assertEquals("复习统计信号分析", result.items.single().title)
    }

    @Test fun plainLinesAndCrlfAreSupported() {
        val result = parse("英语复习\r\n整理笔记\r下载软件")
        assertEquals(3, result.items.size)
    }

    @Test fun missingTitleMustBeCorrected() {
        val item = parse("08:00-09:00").items.single()
        assertNotNull(ScheduleTextParser.validate(item, zone).error)
    }

    @Test fun daylightSavingGapIsRejected() {
        val item = ScheduleDraft(0, "开会", "2026-03-08", "02:30", "03:30")
        assertNotNull(ScheduleTextParser.validate(item, ZoneId.of("America/New_York")).error)
    }

    @Test fun duplicateKeyIgnoresWhitespaceButDistinguishesDateAndTime() {
        val first = valid(parse("08:00-09:00 复习  CMOS").items.single())
        val second = valid(parse("08:00-09:00 复习 CMOS").items.single())
        assertEquals(first.key(), second.key())
        assertNotEquals(first.key(), first.copy(date = tomorrow.plusDays(1)).key())
        assertNotEquals(first.key(), first.copy(end = LocalTime.of(10, 0)).key())
    }

    @Test fun oversizedBatchesAreBlockedRatherThanTruncated() {
        val result = parse((1..201).joinToString("\n") { "- [ ] 任务$it" })
        assertEquals(201, result.items.size)
        assertNotNull(result.error)
    }
}
