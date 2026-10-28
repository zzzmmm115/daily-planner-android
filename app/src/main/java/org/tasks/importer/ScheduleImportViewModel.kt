package org.tasks.importer

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.todoroo.astrid.service.TaskCreator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tasks.broadcast.RefreshBroadcaster
import org.tasks.data.createDueDate
import org.tasks.data.createHideUntil
import org.tasks.data.dao.TaskDao
import org.tasks.data.entity.Task
import org.tasks.filters.CaldavFilter
import org.tasks.preferences.DefaultFilterProvider
import org.tasks.preferences.Preferences
import timber.log.Timber
import javax.inject.Inject

data class ScheduleImportState(
    val text: String = "",
    val defaultDate: String = LocalDate.now().toString(),
    val filter: CaldavFilter? = null,
    val items: List<ScheduleDraft> = emptyList(),
    val duplicates: Set<Int> = emptySet(),
    val preview: Boolean = false,
    val busy: Boolean = false,
    val imported: Int = 0,
    val skipped: Int = 0,
    val ignoredLines: Int = 0,
    val error: String? = null,
    val complete: Boolean = false,
    val showFuture: Boolean = true,
    val incomingText: String? = null,
)

@HiltViewModel
class ScheduleImportViewModel @Inject constructor(
    private val taskCreator: TaskCreator,
    private val taskDao: TaskDao,
    private val defaultFilterProvider: DefaultFilterProvider,
    private val refreshBroadcaster: RefreshBroadcaster,
    private val savedStateHandle: SavedStateHandle,
    private val preferences: Preferences,
) : ViewModel() {
    val zone: ZoneId = ZoneId.systemDefault()
    private val _state = MutableStateFlow(ScheduleImportState(
        text = savedStateHandle["schedule_text"] ?: "",
        defaultDate = savedStateHandle["schedule_date"] ?: LocalDate.now(zone).toString(),
        showFuture = savedStateHandle["schedule_show_future"] ?: true,
    ))
    val state = _state.asStateFlow()
    private var initialized = false
    private var existingKeys: Set<String> = emptySet()

    fun initialize(text: String?, filter: CaldavFilter?) {
        if (initialized) return
        initialized = true
        if (_state.value.text.isEmpty() && text != null) changeText(text)
        viewModelScope.launch {
            try {
                val destination = filter?.takeIf { it.isWritable } ?: defaultFilterProvider.getDefaultList()
                _state.value = _state.value.copy(filter = destination)
            } catch (e: Exception) {
                Timber.e(e, "Unable to select an import list")
                _state.value = _state.value.copy(error = "无法读取任务清单，请返回应用创建一个本地清单后重试。")
            }
        }
    }

    fun changeText(text: String) {
        if (_state.value.busy) return
        savedStateHandle["schedule_text"] = text
        _state.value = _state.value.copy(text = text, error = null, complete = false)
    }

    fun changeDate(date: String) {
        if (_state.value.busy) return
        savedStateHandle["schedule_date"] = date
        _state.value = _state.value.copy(defaultDate = date, error = null)
    }

    fun changeList(filter: CaldavFilter) {
        if (!_state.value.busy && filter.isWritable) _state.value = _state.value.copy(filter = filter)
    }

    fun changeShowFuture(value: Boolean) {
        if (_state.value.busy) return
        savedStateHandle["schedule_show_future"] = value
        _state.value = _state.value.copy(showFuture = value)
    }

    fun backToText() {
        if (!_state.value.busy) _state.value = _state.value.copy(preview = false, error = null, complete = false)
    }

    fun receiveText(text: String) {
        if (text.isBlank()) return
        if (!_state.value.busy && !_state.value.preview && _state.value.text.isBlank()) {
            changeText(text)
        } else {
            _state.value = _state.value.copy(incomingText = text)
        }
    }

    fun replaceIncomingText() {
        if (_state.value.busy) return
        val text = _state.value.incomingText ?: return
        savedStateHandle["schedule_text"] = text
        _state.value = _state.value.copy(
            text = text, items = emptyList(), duplicates = emptySet(), preview = false,
            imported = 0, skipped = 0, ignoredLines = 0, error = null,
            complete = false, incomingText = null,
        )
    }

    fun keepCurrentText() {
        _state.value = _state.value.copy(incomingText = null)
    }

    fun preview() {
        val before = _state.value
        if (before.busy || before.filter == null) return
        val date = runCatching { LocalDate.parse(before.defaultDate.trim()) }.getOrNull()
        if (date == null) {
            _state.value = before.copy(error = "请填写有效的默认日期，例如 2026-10-28。")
            return
        }
        _state.value = before.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.Default) { ScheduleTextParser.parse(before.text, date, LocalDate.now(zone)) }
                existingKeys = withContext(Dispatchers.IO) { taskDao.getAll().filterNot { it.isDeleted }.mapNotNull(::keyForTask).toSet() }
                val duplicates = findDuplicates(result.items)
                _state.value = _state.value.copy(
                    items = result.items.map { if (it.id in duplicates) it.copy(selected = false) else it },
                    duplicates = duplicates,
                    preview = result.items.isNotEmpty(),
                    busy = false,
                    imported = 0,
                    skipped = 0,
                    ignoredLines = result.skippedLines,
                    error = result.error ?: if (result.items.isEmpty()) "没有找到待办，请按每行一个任务或“08:00-09:00 任务”填写。" else null,
                )
            } catch (e: Exception) {
                Timber.e(e, "Unable to prepare text import")
                _state.value = _state.value.copy(busy = false, error = "预览失败，原有任务未受影响，请重试。")
            }
        }
    }

    fun edit(id: Int, transform: (ScheduleDraft) -> ScheduleDraft) {
        if (_state.value.busy || _state.value.complete) return
        val items = _state.value.items.map { if (it.id == id) transform(it) else it }
        _state.value = _state.value.copy(items = items, duplicates = findDuplicates(items), error = null)
    }

    fun importSelected() {
        val before = _state.value
        if (before.busy || before.complete || before.filter == null || before.items.size > ScheduleTextParser.MAX_ITEMS) return
        val selected = before.items.filter { it.selected && it.id !in before.duplicates }
        if (selected.isEmpty()) return
        val checked = selected.map { it to ScheduleTextParser.validate(it, zone) }
        if (checked.any { it.second.entry == null }) {
            _state.value = before.copy(error = "请修改红色提示的条目，或取消勾选后再导入。")
            return
        }
        _state.value = before.copy(busy = true, error = null, imported = 0, skipped = 0)
        viewModelScope.launch {
            // Finish an accepted batch across rotation or activity teardown. Each saved item is
            // tracked independently so a failed/retried batch never silently re-adds its prefix.
            withContext(NonCancellable + Dispatchers.IO) {
                var imported = 0
                var skipped = before.duplicates.size
                val consumed = mutableSetOf<Int>()
                try {
                    val keys = taskDao.getAll().filterNot { it.isDeleted }.mapNotNull(::keyForTask).toMutableSet()
                    for ((draft, validation) in checked) {
                        val entry = validation.entry!!
                        if (entry.key() in keys) {
                            skipped++
                        } else {
                            taskCreator.basicQuickAddTask(entry.title, before.filter, parseTitle = false, applyDefaults = false) { task ->
                                task.hideUntil = task.createHideUntil(
                                    if (entry.start == null) Task.HIDE_UNTIL_SPECIFIC_DAY else Task.HIDE_UNTIL_SPECIFIC_DAY_TIME,
                                    entry.startMillis(zone),
                                )
                                task.dueDate = createDueDate(
                                    if (entry.start == null) Task.URGENCY_SPECIFIC_DAY else Task.URGENCY_SPECIFIC_DAY_TIME,
                                    entry.endMillis(zone),
                                )
                                task.notes = entry.taskNotes()
                                task.estimatedSeconds = if (entry.start != null && entry.end != null) {
                                    Duration.ofMillis(entry.endMillis(zone) - entry.startMillis(zone)).seconds.coerceAtLeast(0).toInt()
                                } else 0
                            }
                            imported++
                            keys += entry.key()
                        }
                        consumed += draft.id
                        _state.value = _state.value.copy(imported = imported, skipped = skipped)
                    }
                    existingKeys = keys
                    if (imported > 0 && before.showFuture) preferences.showHidden = true
                    _state.value = _state.value.copy(busy = false, complete = true, imported = imported, skipped = skipped)
                } catch (e: Exception) {
                    Timber.e(e, "Text import stopped after %s tasks", imported)
                    existingKeys = runCatching { taskDao.getAll().filterNot { it.isDeleted }.mapNotNull(::keyForTask).toSet() }.getOrDefault(existingKeys)
                    val remaining = _state.value.items.map { if (it.id in consumed) it.copy(selected = false) else it }
                    _state.value = _state.value.copy(
                        busy = false, items = remaining, duplicates = findDuplicates(remaining),
                        error = "本次已保存${imported}项、跳过${skipped}项，其余未完成。请重试，已保存的任务会自动查重。",
                    )
                } finally {
                    refreshBroadcaster.broadcastRefresh()
                }
            }
        }
    }

    private fun findDuplicates(items: List<ScheduleDraft>): Set<Int> {
        val seen = existingKeys.toMutableSet()
        return buildSet {
            items.forEach { item ->
                ScheduleTextParser.validate(item, zone).entry?.key()?.let { key ->
                    if (!seen.add(key)) add(item.id)
                }
            }
        }
    }

    private fun keyForTask(task: Task): String? {
        val timestamp = task.hideUntil.takeIf { it > 0 } ?: task.dueDate.takeIf { it > 0 } ?: return null
        val date = Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()
        val start = if (task.hasStartTime()) Instant.ofEpochMilli(task.hideUntil).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString() else ""
        val end = if (task.hasDueTime()) Instant.ofEpochMilli(task.dueDate).atZone(zone).toLocalTime().withSecond(0).withNano(0).toString() else ""
        val nextDay = task.dueDate > 0 && Instant.ofEpochMilli(task.dueDate).atZone(zone).toLocalDate().isAfter(date)
        return scheduleKey(task.title.orEmpty(), date.toString(), start, end, nextDay)
    }
}
