package org.tasks.importer

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.tasks.R
import org.tasks.compose.FilterSelectionActivity.Companion.launch
import org.tasks.compose.FilterSelectionActivity.Companion.registerForFilterPickerResult
import org.tasks.filters.CaldavFilter
import org.tasks.intents.TaskIntents
import org.tasks.themes.TasksTheme
import org.tasks.themes.Theme
import javax.inject.Inject

@AndroidEntryPoint
class TextScheduleImportActivity : AppCompatActivity() {
    @Inject lateinit var theme: Theme
    private val viewModel: ScheduleImportViewModel by viewModels()
    private val listPicker = registerForFilterPickerResult { filter ->
        (filter as? CaldavFilter)?.let(viewModel::changeList)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.initialize(
            incomingText(intent),
            IntentCompat.getParcelableExtra(intent, EXTRA_FILTER, CaldavFilter::class.java),
        )
        setContent {
            TasksTheme(theme = theme.themeBase.index, primary = theme.themeColor.primaryColor) {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BackHandler(enabled = state.busy) { /* An accepted batch must finish before leaving. */ }
                BackHandler(enabled = state.preview && !state.busy && !state.complete) { viewModel.backToText() }
                ImportScreen(
                    state = state,
                    viewModel = viewModel,
                    onBack = { if (state.preview) viewModel.backToText() else finish() },
                    onList = { listPicker.launch(this, state.filter, listsOnly = true) },
                    onPaste = {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.let(viewModel::changeText)
                    },
                    onDone = {
                        startActivity(TaskIntents.getTaskListIntent(this, state.filter))
                        finish()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingText(intent)?.let(viewModel::receiveText)
    }

    private fun incomingText(intent: Intent): String? = intent.getStringExtra(EXTRA_TEXT)
        ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        ?: intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()

    companion object {
        const val EXTRA_TEXT = "schedule_import_text"
        const val EXTRA_FILTER = "schedule_import_filter"
        fun intent(context: Context, text: String? = null) = Intent(context, TextScheduleImportActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_TEXT, text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportScreen(
    state: ScheduleImportState,
    viewModel: ScheduleImportViewModel,
    onBack: () -> Unit,
    onList: () -> Unit,
    onPaste: () -> Unit,
    onDone: () -> Unit,
) {
    val selected = state.items.filter { it.selected && it.id !in state.duplicates }
    val validSelection = selected.isNotEmpty() && selected.all { ScheduleTextParser.validate(it, viewModel.zone).entry != null }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.text_import_title)) },
                navigationIcon = { TextButton(onClick = onBack, enabled = !state.busy) { Text(stringResource(R.string.text_import_back)) } },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    if (state.busy) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.text_import_progress, state.imported))
                        }
                    } else if (state.preview) {
                        Button(
                            onClick = viewModel::importSelected,
                            enabled = validSelection && !state.complete && state.items.size <= ScheduleTextParser.MAX_ITEMS,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.text_import_save, selected.size)) }
                    } else {
                        Button(onClick = viewModel::preview, enabled = state.text.isNotBlank() && state.filter != null, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.text_import_preview))
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.text_import_intro), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onList, enabled = !state.busy) {
                    Text(stringResource(R.string.text_import_list, state.filter?.title ?: stringResource(R.string.text_import_loading)))
                }
                Row {
                    Checkbox(checked = state.showFuture, onCheckedChange = viewModel::changeShowFuture, enabled = !state.busy)
                    Text(stringResource(R.string.text_import_show_future), modifier = Modifier.padding(top = 12.dp))
                }
            }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            if (!state.preview) {
                item {
                    OutlinedTextField(
                        value = state.defaultDate, onValueChange = viewModel::changeDate,
                        label = { Text(stringResource(R.string.text_import_default_date)) },
                        singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onPaste, enabled = !state.busy) { Text(stringResource(R.string.text_import_paste)) }
                        TextButton(onClick = { viewModel.changeText("日期：${state.defaultDate}\n08:00-09:00 复习统计信号分析\n09:15-10:00 CMOS：独立分析一个电路\n- [ ] 英语到期复习\n- [ ] 整理水声笔记") }, enabled = !state.busy) {
                            Text(stringResource(R.string.text_import_example))
                        }
                    }
                    OutlinedTextField(
                        value = state.text, onValueChange = viewModel::changeText,
                        label = { Text(stringResource(R.string.text_import_text)) },
                        minLines = 8, maxLines = 16, enabled = !state.busy, modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { Text(stringResource(R.string.text_import_help), style = MaterialTheme.typography.bodySmall) }
            } else {
                item {
                    Text(stringResource(R.string.text_import_count, state.items.size, selected.size), style = MaterialTheme.typography.titleMedium)
                    if (state.ignoredLines > 0) Text(stringResource(R.string.text_import_ignored, state.ignoredLines), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = viewModel::backToText, enabled = !state.busy) { Text(stringResource(R.string.text_import_edit_source)) }
                }
                items(state.items, key = { it.id }) { draft ->
                    DraftCard(draft, draft.id in state.duplicates, state.busy, viewModel)
                }
            }
            item { Text("", Modifier.padding(bottom = 8.dp)) }
        }
    }
    if (state.incomingText != null && !state.busy) {
        AlertDialog(
            onDismissRequest = viewModel::keepCurrentText,
            title = { Text(stringResource(R.string.text_import_incoming_title)) },
            text = { Text(stringResource(R.string.text_import_incoming_message)) },
            confirmButton = { TextButton(onClick = viewModel::replaceIncomingText) { Text(stringResource(R.string.text_import_replace)) } },
            dismissButton = { TextButton(onClick = viewModel::keepCurrentText) { Text(stringResource(R.string.text_import_keep)) } },
        )
    } else if (state.complete) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.text_import_done)) },
            text = { Text(stringResource(R.string.text_import_result, state.imported, state.skipped)) },
            confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.text_import_open_list)) } },
        )
    }
}

@Composable
private fun DraftCard(draft: ScheduleDraft, duplicate: Boolean, busy: Boolean, viewModel: ScheduleImportViewModel) {
    val validation = ScheduleTextParser.validate(draft, viewModel.zone)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Checkbox(checked = draft.selected && !duplicate, onCheckedChange = { value -> viewModel.edit(draft.id) { it.copy(selected = value) } }, enabled = !duplicate && !busy)
                Column(Modifier.padding(top = 10.dp)) {
                    Text(stringResource(R.string.text_import_item, draft.id + 1))
                    if (duplicate) Text(stringResource(R.string.text_import_duplicate), style = MaterialTheme.typography.bodySmall)
                    else if (draft.originallyCompleted) Text(stringResource(R.string.text_import_completed_source), style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(
                value = draft.title, onValueChange = { value -> viewModel.edit(draft.id) { it.copy(title = value) } },
                label = { Text(stringResource(R.string.text_import_task_title)) },
                enabled = !busy, maxLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(value = draft.date, onValueChange = { value -> viewModel.edit(draft.id) { it.copy(date = value) } },
                    label = { Text(stringResource(R.string.text_import_date)) }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1.25f))
                OutlinedTextField(value = draft.start, onValueChange = { value -> viewModel.edit(draft.id) { it.copy(start = value) } },
                    label = { Text(stringResource(R.string.text_import_start)) }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
                OutlinedTextField(value = draft.end, onValueChange = { value -> viewModel.edit(draft.id) { it.copy(end = value) } },
                    label = { Text(stringResource(R.string.text_import_end)) }, singleLine = true, enabled = !busy, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(
                value = draft.notes, onValueChange = { value -> viewModel.edit(draft.id) { it.copy(notes = value) } },
                label = { Text(stringResource(R.string.text_import_notes)) }, enabled = !busy, maxLines = 4, modifier = Modifier.fillMaxWidth(),
            )
            validation.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
