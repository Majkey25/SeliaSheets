package com.majkeylab.seliadocs.editor

import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.motionEventSpy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.SeliaDocsDatabase
import com.majkeylab.seliadocs.data.SeliaDocsRepository
import com.majkeylab.seliadocs.recognition.RecognitionModelManager
import com.majkeylab.seliadocs.settings.AppSettings

internal data class WorkspacePane(val notebookId: String, val requestedPageId: String? = null)
internal enum class WorkspaceChange { BACK, SETTINGS, PRIMARY, SECONDARY, CLOSE_SECONDARY, SHARED_PAGE }
internal data class WorkspaceSave(val id: Long, val change: WorkspaceChange, val target: WorkspacePane? = null)

/** Two fixed panes and one pending operation survive activity recreation. No editor data is copied here. */
internal class EditorWorkspaceHolder : ViewModel() {
    private var key: String? = null
    private var nextRequest = 0L
    var primary by mutableStateOf<WorkspacePane?>(null)
    var secondary by mutableStateOf<WorkspacePane?>(null)
    var pending by mutableStateOf<WorkspaceSave?>(null)
        private set
    var failed by mutableStateOf(false)
    var failedRequest: WorkspaceSave? = null
        private set
    var activePane by mutableStateOf(0)
    var split by mutableStateOf(0.5f)
    var pickerPane by mutableStateOf<Int?>(null)
    var sharedPageSaved by mutableStateOf<String?>(null)

    fun prepare(sessionKey: String, notebookId: String, pageId: String?) {
        if (key == sessionKey) return
        key = sessionKey
        primary = WorkspacePane(notebookId, pageId)
        secondary = null
        pending = null
        failed = false
        failedRequest = null
        activePane = 0
        pickerPane = null
        sharedPageSaved = null
    }

    fun request(change: WorkspaceChange, target: WorkspacePane? = null) {
        if (pending != null) return
        failed = false
        pending = WorkspaceSave(++nextRequest, change, target)
    }

    fun finish(saved: Boolean) {
        failed = !saved
        failedRequest = if (saved) null else pending
        pending = null
    }

    fun synchronizeSharedPage(pageId: String?) {
        if (pageId == null) sharedPageSaved = null
        else if (pending == null && !failed && sharedPageSaved != pageId) request(WorkspaceChange.SHARED_PAGE)
    }

    fun forget() { key = null }
}

@Composable
internal fun EditorWorkspace(
    notebookId: String,
    libraryGeneration: Long,
    recognitionModelManager: RecognitionModelManager,
    settings: AppSettings,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    initialPageId: String? = null,
    onInitialPageOpened: () -> Unit = {},
) {
    val factory = remember { viewModelFactory { initializer { EditorSessionHolder() } } }
    val primaryHolder: EditorSessionHolder = viewModel(key = "editor-session-holder", factory = factory)
    val secondaryHolder: EditorSessionHolder = viewModel(key = "secondary-editor-session-holder", factory = factory)
    val workspaceFactory = remember { viewModelFactory { initializer { EditorWorkspaceHolder() } } }
    val workspace: EditorWorkspaceHolder = viewModel(key = "editor-workspace", factory = workspaceFactory)
    workspace.prepare("$libraryGeneration:$notebookId", notebookId, initialPageId)
    val primaryPage by primaryHolder.selectedPage.collectAsStateWithLifecycle()
    val secondaryPage by secondaryHolder.selectedPage.collectAsStateWithLifecycle()
    val primaryResult by primaryHolder.workspaceSaveResult.collectAsStateWithLifecycle()
    val secondaryResult by secondaryHolder.workspaceSaveResult.collectAsStateWithLifecycle()
    val sharedPage = primaryPage?.takeIf { workspace.secondary != null && it == secondaryPage }
    val operation = workspace.pending
    val busy = operation != null
    LaunchedEffect(sharedPage, operation, workspace.failed) {
        workspace.synchronizeSharedPage(sharedPage)
    }
    LaunchedEffect(operation) {
        val request = operation ?: return@LaunchedEffect
        if (request.change != WorkspaceChange.CLOSE_SECONDARY && request.change != WorkspaceChange.SHARED_PAGE) {
            primaryHolder.requestAction(EditorAction.WorkspaceSave(request.id))
        }
        if (workspace.secondary != null) secondaryHolder.requestAction(EditorAction.WorkspaceSave(request.id))
    }
    LaunchedEffect(operation, primaryResult, secondaryResult) {
        val request = operation ?: return@LaunchedEffect
        val needsPrimary = request.change != WorkspaceChange.CLOSE_SECONDARY && request.change != WorkspaceChange.SHARED_PAGE
        val needsSecondary = workspace.secondary != null
        val first = if (needsPrimary) primaryResult?.takeIf { it.first == request.id }?.second else true
        val second = if (needsSecondary) secondaryResult?.takeIf { it.first == request.id }?.second else true
        if (first == null || second == null) return@LaunchedEffect
        if (!first || !second) { workspace.finish(false); return@LaunchedEffect }
        when (request.change) {
            WorkspaceChange.BACK -> {
                listOf(primaryHolder, secondaryHolder).forEach { holder ->
                    holder.beginClose(EditorCloseIntent.BACK)
                    holder.completeClose(true)
                    holder.consumeCompletedClose()
                }
                workspace.forget()
                onBack()
            }
            WorkspaceChange.SETTINGS -> onSettings()
            WorkspaceChange.PRIMARY -> workspace.primary = requireNotNull(request.target)
            WorkspaceChange.SECONDARY -> {
                workspace.secondary = requireNotNull(request.target)
                workspace.activePane = 1
            }
            WorkspaceChange.CLOSE_SECONDARY -> {
                secondaryHolder.beginClose(EditorCloseIntent.BACK)
                secondaryHolder.completeClose(true)
                secondaryHolder.consumeCompletedClose()
                workspace.secondary = null
                workspace.activePane = 0
            }
            WorkspaceChange.SHARED_PAGE -> workspace.sharedPageSaved = sharedPage
        }
        workspace.finish(true)
    }
    fun close(intent: EditorCloseIntent) = workspace.request(
        if (intent == EditorCloseIntent.SETTINGS) WorkspaceChange.SETTINGS else WorkspaceChange.BACK,
    )
    BackHandler {
        if (workspace.secondary != null) workspace.request(WorkspaceChange.CLOSE_SECONDARY)
        else close(EditorCloseIntent.BACK)
    }
    val context = LocalContext.current
    val repository = remember(context.applicationContext) { SeliaDocsRepository(SeliaDocsDatabase.get(context)) }
    val notebooks by remember(repository) { repository.observeNotebooks() }.collectAsStateWithLifecycle(initialValue = emptyList())
    workspace.pickerPane?.let { pane ->
        AlertDialog(
            modifier = Modifier.testTag("workspace-notebook-picker"),
            onDismissRequest = { workspace.pickerPane = null },
            title = { Text(stringResource(R.string.workspace_picker)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(notebooks, key = { it.id }) { notebook ->
                        TextButton(onClick = {
                            workspace.pickerPane = null
                            workspace.request(if (pane == 0) WorkspaceChange.PRIMARY else WorkspaceChange.SECONDARY,
                                WorkspacePane(notebook.id))
                        }, modifier = Modifier.fillMaxWidth()) { Text(notebook.title) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { workspace.pickerPane = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    @Composable
    fun Pane(index: Int, pane: WorkspacePane, modifier: Modifier) {
        Column(modifier.testTag(if (index == 0) "primary-editor" else "secondary-editor")
            .motionEventSpy { if (it.actionMasked == MotionEvent.ACTION_DOWN && !busy) workspace.activePane = index }) {
            if (workspace.failed && index == if (workspace.secondary == null) 0 else 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.workspace_save_failed), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).padding(12.dp).testTag("workspace-save-failed"))
                    TextButton(onClick = {
                        workspace.failedRequest?.let { workspace.request(it.change, it.target) }
                    }, modifier = Modifier.testTag("workspace-retry")) { Text(stringResource(R.string.retry)) }
                }
            }
            if (workspace.secondary != null) Row(Modifier.fillMaxWidth().testTag("workspace-pane-header"), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (index == 0) R.string.workspace_primary else R.string.workspace_secondary),
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
                TextButton(onClick = { workspace.pickerPane = index }, enabled = !busy) { Text(stringResource(R.string.replace_pane)) }
                if (index == 1) TextButton(onClick = { workspace.request(WorkspaceChange.CLOSE_SECONDARY) }, enabled = !busy) {
                    Text(stringResource(R.string.close_pane))
                }
            }
            EditorRoute(
                notebookId = pane.notebookId, libraryGeneration = libraryGeneration,
                recognitionModelManager = recognitionModelManager, settings = settings, onUpdateSettings = onUpdateSettings,
                onBack = onBack, onSettings = onSettings,
                initialPageId = pane.requestedPageId,
                onInitialPageOpened = {
                    if (index == 0) { workspace.primary = pane.copy(requestedPageId = null); onInitialPageOpened() }
                    else workspace.secondary = pane.copy(requestedPageId = null)
                },
                onOpenPage = { notebook, page ->
                    workspace.request(if (index == 0) WorkspaceChange.PRIMARY else WorkspaceChange.SECONDARY, WorkspacePane(notebook, page))
                },
                holderKey = if (index == 0) "editor-session-holder" else "secondary-editor-session-holder",
                editable = !busy && (sharedPage == null || (index == 0 && workspace.sharedPageSaved == sharedPage)),
                ownsTextFocus = workspace.activePane == index,
                handleSystemBack = false, onWorkspaceClose = ::close,
                onOpenBeside = { workspace.pickerPane = 1 }, workspaceBusy = busy,
                readOnlyPageId = if (index == 1) primaryPage
                    else secondaryPage?.takeUnless { workspace.sharedPageSaved == it },
            )
        }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val primary = requireNotNull(workspace.primary)
            val secondary = workspace.secondary
            if (secondary == null) Pane(0, primary, Modifier.fillMaxSize())
            else if (maxWidth >= 840.dp) {
                val availableWidth = maxWidth - 24.dp
                val minimum = 320.dp / availableWidth
                val fraction = workspace.split.coerceIn(minimum, 1f - minimum)
                val widthPx = with(LocalDensity.current) { availableWidth.toPx() }
                val resizeLabel = stringResource(R.string.workspace_resize)
                Row(Modifier.fillMaxSize()) {
                    Pane(0, primary, Modifier.width(availableWidth * fraction).fillMaxHeight())
                    Box(Modifier.width(24.dp).fillMaxHeight().testTag("workspace-divider")
                        .draggable(rememberDraggableState { delta ->
                            if (delta.isFinite()) workspace.split = (fraction + delta / widthPx).coerceIn(minimum, 1f - minimum)
                        }, Orientation.Horizontal, enabled = !busy)
                        .semantics {
                            contentDescription = resizeLabel
                            progressBarRangeInfo = ProgressBarRangeInfo(fraction, minimum..(1f - minimum))
                            setProgress {
                                if (!it.isFinite() || busy) false
                                else { workspace.split = it.coerceIn(minimum, 1f - minimum); true }
                            }
                        }, contentAlignment = Alignment.Center) { VerticalDivider() }
                    Pane(1, secondary, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Pane(0, primary, Modifier.fillMaxSize())
                Dialog(onDismissRequest = { workspace.request(WorkspaceChange.CLOSE_SECONDARY) },
                    properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
                        dismissOnBackPress = true, dismissOnClickOutside = false)) {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp),
                        contentAlignment = Alignment.Center) {
                        Surface(Modifier.fillMaxSize(), shape = MaterialTheme.shapes.large) {
                            Pane(1, secondary, Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
