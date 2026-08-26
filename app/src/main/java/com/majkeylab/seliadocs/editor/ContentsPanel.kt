package com.majkeylab.seliadocs.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.majkeylab.seliadocs.R
import com.majkeylab.seliadocs.data.ChapterEntity
import com.majkeylab.seliadocs.data.ElementKind
import com.majkeylab.seliadocs.data.PageEntity
import com.majkeylab.seliadocs.data.PaperTemplate

@Composable
internal fun PageLocationBar(
    state: EditorUiState,
    onOpenContents: () -> Unit,
    onBookmarkPage: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val page = state.selectedPage ?: return
    val bookmarkDescription =
        stringResource(if (page.bookmarked) R.string.remove_page_bookmark else R.string.bookmark_page)
    val bookmarkState =
        stringResource(if (page.bookmarked) R.string.bookmarked else R.string.not_bookmarked)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onOpenContents) { Text(stringResource(R.string.contents)) }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    page.title ?: stringResource(R.string.page_number, page.pageIndex + 1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.page_of_pages, page.pageIndex + 1, state.pages.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onBookmarkPage(page.id, !page.bookmarked) },
                modifier =
                    Modifier.semantics {
                        contentDescription = bookmarkDescription
                        stateDescription = bookmarkState
                    },
            ) {
                Text(if (page.bookmarked) "★" else "☆")
            }
        }
    }
}

@Composable
internal fun ContentsPanel(
    state: EditorUiState,
    onSelectPage: (String) -> Unit,
    onCreateChapter: (String) -> Unit,
    onDeleteChapter: (String) -> Unit,
    onRenamePage: (String, String?) -> Unit,
    onBookmarkPage: (String, Boolean) -> Unit,
    onAssignPage: (String, String?) -> Unit,
    onDuplicatePage: (String) -> Unit,
    onDeletePage: (String) -> Unit,
    loadPagePreview: suspend (String) -> PagePreviewData,
    modifier: Modifier = Modifier,
) {
    var createChapter by rememberSaveable { mutableStateOf(false) }
    var renamePage by remember { mutableStateOf<PageEntity?>(null) }
    var movePage by remember { mutableStateOf<PageEntity?>(null) }
    var deleteChapter by remember { mutableStateOf<ChapterEntity?>(null) }
    var deletePage by remember { mutableStateOf<PageEntity?>(null) }

    if (createChapter) {
        NameDialog(
            title = stringResource(R.string.add_chapter),
            initial = "",
            allowEmpty = false,
            onDismiss = { createChapter = false },
            onSave = {
                onCreateChapter(it)
                createChapter = false
            },
        )
    }
    renamePage?.let { page ->
        NameDialog(
            title = stringResource(R.string.rename_page),
            initial = page.title.orEmpty(),
            allowEmpty = true,
            onDismiss = { renamePage = null },
            onSave = {
                onRenamePage(page.id, it.ifBlank { null })
                renamePage = null
            },
        )
    }
    movePage?.let { page ->
        MovePageDialog(
            chapters = state.chapters,
            onDismiss = { movePage = null },
            onSelect = {
                onAssignPage(page.id, it)
                movePage = null
            },
        )
    }
    deleteChapter?.let { chapter ->
        AlertDialog(
            onDismissRequest = { deleteChapter = null },
            title = { Text(stringResource(R.string.delete_chapter)) },
            text = { Text(stringResource(R.string.delete_chapter_message, chapter.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteChapter(chapter.id)
                        deleteChapter = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteChapter = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
    deletePage?.let { page ->
        AlertDialog(
            onDismissRequest = { deletePage = null },
            title = { Text(stringResource(R.string.delete_page_title)) },
            text = { Text(stringResource(R.string.delete_page_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeletePage(page.id)
                        deletePage = null
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletePage = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    LazyColumn(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.contents),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { createChapter = true }) { Text(stringResource(R.string.add_chapter)) }
            }
            HorizontalDivider()
        }
        val unfiled = state.pages.filter { it.chapterId == null }
        if (unfiled.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.unfiled), null) }
            items(unfiled, key = PageEntity::id) { page ->
                ContentsPageRow(page, state, onSelectPage, onBookmarkPage, { renamePage = it }, { movePage = it }, onDuplicatePage, { deletePage = it }, loadPagePreview)
            }
        }
        state.chapters.forEach { chapter ->
            item {
                SectionHeader(chapter.title, Color(chapter.colorArgb), onDelete = { deleteChapter = chapter })
            }
            items(state.pages.filter { it.chapterId == chapter.id }, key = PageEntity::id) { page ->
                ContentsPageRow(page, state, onSelectPage, onBookmarkPage, { renamePage = it }, { movePage = it }, onDuplicatePage, { deletePage = it }, loadPagePreview)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, color: Color?, onDelete: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, top = 14.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (color != null) Box(Modifier.padding(end = 8.dp).size(10.dp).background(color, RoundedCornerShape(5.dp)))
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.remove)) }
    }
}

@Composable
private fun PageMiniature(
    page: PageEntity,
    revision: Long,
    loadPreview: suspend (String) -> PagePreviewData,
) {
    val preview by
        produceState<PagePreviewData?>(null, page.id, revision) {
            value = runCatching { loadPreview(page.id) }.getOrNull()
        }
    val description = stringResource(R.string.page_preview_description, page.pageIndex + 1)
    Surface(
        color = Color(0xFFFFFEFA),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFD5D7DC)),
        modifier =
            Modifier.size(50.dp, 66.dp).semantics {
                contentDescription = description
            },
    ) {
        Box {
            Canvas(Modifier.fillMaxSize()) {
                val scaleX = size.width / page.widthPoints
                val scaleY = size.height / page.heightPoints
                val paper = runCatching { PaperTemplate.valueOf(page.paper) }.getOrDefault(PaperTemplate.BLANK)
                val spacingX = 28f * scaleX
                val spacingY = 28f * scaleY
                val paperColor = Color(0xFFD9DBE0)
                when (paper) {
                    PaperTemplate.BLANK -> Unit
                    PaperTemplate.RULED -> {
                        var y = spacingY
                        while (y < size.height) {
                            drawLine(paperColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
                            y += spacingY
                        }
                    }
                    PaperTemplate.GRID -> {
                        var x = spacingX
                        while (x < size.width) {
                            drawLine(paperColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.4.dp.toPx())
                            x += spacingX
                        }
                        var y = spacingY
                        while (y < size.height) {
                            drawLine(paperColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.4.dp.toPx())
                            y += spacingY
                        }
                    }
                    PaperTemplate.DOT -> {
                        var y = spacingY
                        while (y < size.height) {
                            var x = spacingX
                            while (x < size.width) {
                                drawCircle(paperColor, radius = 0.7.dp.toPx(), center = Offset(x, y))
                                x += spacingX
                            }
                            y += spacingY
                        }
                    }
                }
                preview?.pdfBackground?.let { image ->
                    drawImage(
                        image = image,
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }
                preview?.elements.orEmpty().forEach { element ->
                    val kind = runCatching { ElementKind.valueOf(element.kind) }.getOrNull()
                    val color = if (kind == ElementKind.IMAGE) Color(0xFF9EACD1) else Color(0xFF707680)
                    drawRect(
                        color = color,
                        topLeft = Offset(element.x * scaleX, element.y * scaleY),
                        size = Size(element.width * scaleX, element.height * scaleY),
                        alpha = if (kind == ElementKind.IMAGE) 0.45f else 0.75f,
                        style = if (kind == ElementKind.IMAGE) androidx.compose.ui.graphics.drawscope.Fill else Stroke(0.7.dp.toPx()),
                    )
                }
                preview?.strokes.orEmpty().forEach { stroke ->
                    val points = runCatching { stroke.toStrokePath().points }.getOrDefault(emptyList())
                    if (points.isNotEmpty()) {
                        val path = Path().apply {
                            moveTo(points.first().x * scaleX, points.first().y * scaleY)
                            points.drop(1).forEach { point -> lineTo(point.x * scaleX, point.y * scaleY) }
                        }
                        drawPath(path, Color(0xFF27292D), style = Stroke(0.8.dp.toPx()))
                    }
                }
            }
            val text = preview?.blocks.orEmpty().joinToString("\n") { it.text.orEmpty() }.take(160)
            if (text.isNotEmpty()) {
                Text(
                    text = text,
                    color = Color(0xFF34363A),
                    fontSize = 3.5.sp,
                    lineHeight = 4.5.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.fillMaxSize().padding(start = 4.dp, top = 5.dp, end = 4.dp, bottom = 5.dp),
                )
            }
            Text(
                "${page.pageIndex + 1}",
                fontSize = 7.sp,
                color = Color(0xFF696760),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp),
            )
        }
    }
}

@Composable
private fun ContentsPageRow(
    page: PageEntity,
    state: EditorUiState,
    onSelect: (String) -> Unit,
    onBookmark: (String, Boolean) -> Unit,
    onRename: (PageEntity) -> Unit,
    onMove: (PageEntity) -> Unit,
    onDuplicate: (String) -> Unit,
    onDelete: (PageEntity) -> Unit,
    loadPagePreview: suspend (String) -> PagePreviewData,
) {
    val selected = state.selectedPage?.id == page.id
    var menu by remember { mutableStateOf(false) }
    val actionsDescription = stringResource(R.string.page_actions, page.pageIndex + 1)
    val bookmarkDescription =
        stringResource(if (page.bookmarked) R.string.remove_page_bookmark else R.string.bookmark_page)
    val bookmarkState =
        stringResource(if (page.bookmarked) R.string.bookmarked else R.string.not_bookmarked)
    Surface(
        onClick = { onSelect(page.id) },
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).testTag("page-thumbnail"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, end = 4.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PageMiniature(
                page = page,
                revision = state.notebook?.updatedAt ?: 0L,
                loadPreview = loadPagePreview,
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(
                    page.title ?: stringResource(R.string.page_number, page.pageIndex + 1),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    stringResource(R.string.page_position, page.pageIndex + 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = { onBookmark(page.id, !page.bookmarked) },
                modifier =
                    Modifier.testTag("contents-bookmark").semantics {
                        contentDescription = bookmarkDescription
                        stateDescription = bookmarkState
                    },
            ) {
                Text(if (page.bookmarked) "★" else "☆")
            }
            Box {
                TextButton(
                    onClick = { menu = true },
                    modifier = Modifier.semantics { contentDescription = actionsDescription },
                ) { Text("⋮") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename_page)) },
                        onClick = {
                            menu = false
                            onRename(page)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.move_to_chapter)) },
                        onClick = {
                            menu = false
                            onMove(page)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.duplicate_page)) },
                        onClick = {
                            menu = false
                            onDuplicate(page.id)
                        },
                    )
                    if (state.pages.size > 1) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete_page)) },
                            onClick = {
                                menu = false
                                onDelete(page)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    allowEmpty: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(160) },
                label = { Text(title) },
                singleLine = true,
                modifier = Modifier.testTag("name-dialog-input"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(value) }, enabled = allowEmpty || value.isNotBlank()) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun MovePageDialog(
    chapters: List<ChapterEntity>,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_to_chapter)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                item {
                    TextButton(onClick = { onSelect(null) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.unfiled))
                    }
                }
                items(chapters, key = ChapterEntity::id) { chapter ->
                    TextButton(
                        onClick = { onSelect(chapter.id) },
                        modifier = Modifier.fillMaxWidth().testTag("move-chapter-option"),
                    ) {
                        Text(chapter.title)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
