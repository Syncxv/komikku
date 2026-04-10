package eu.kanade.presentation.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.tachiyomi.ui.manga.pagebookmarks.PageBookmarksScreen
import tachiyomi.domain.pagebookmarks.model.PageBookmark
import tachiyomi.i18n.MR
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import tachiyomi.domain.chapter.model.Chapter
import androidx.compose.material3.DropdownMenu

@Composable
fun PageBookmarksScreen(
    state: PageBookmarksScreen.State,
    navigateUp: () -> Unit,
    onBookmarkClick: (PageBookmark) -> Unit,
    onDeleteBookmark: (PageBookmark) -> Unit,
    onUpdateNote: (Long, String) -> Unit,
    onMigrateBookmarks: (Map<Long, Chapter>) -> Unit,
    getThumbnailFile: (PageBookmark) -> File?,
    getAutoMatchPreview: (List<PageBookmark>) -> Map<PageBookmark, Chapter?>,
) {
    var selectedBookmark by remember { mutableStateOf<PageBookmark?>(null) }
    var bookmarkToDelete by remember { mutableStateOf<PageBookmark?>(null) }
    var editingNote by remember { mutableStateOf<PageBookmark?>(null) }
    var noteText by remember { mutableStateOf("") }
    var showOrphanDialog by remember { mutableStateOf(false) }

    // Group bookmarks by chapter, ordered by the user's chapter sort setting
    val groupedBookmarks = remember(state.bookmarks, state.chapterIdToOrder) {
        state.bookmarks
            .groupBy { it.chapterId }
            .map { (chapterId, bookmarks) ->
                chapterId to bookmarks.sortedBy { it.pageIndex }
            }
            .sortedBy { (chapterId, _) -> state.chapterIdToOrder[chapterId] ?: Int.MAX_VALUE }
    }

    Scaffold(
        topBar = { topBarScrollBehavior ->
            AppBar(
                titleContent = {
                    AppBarTitle(
                        title = stringResource(KMR.strings.page_bookmarks),
                        subtitle = state.mangaTitle,
                    )
                },
                navigateUp = navigateUp,
                scrollBehavior = topBarScrollBehavior,
            )
        },
    ) { contentPadding ->
        if (state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.bookmarks.isEmpty() && state.orphanedBookmarks.isEmpty()) {
            EmptyBookmarksContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    // Use modifier padding instead of contentPadding so stickyHeaders work correctly
                    .padding(contentPadding),
            ) {
                if (state.orphanedBookmarks.isNotEmpty()) {
                    item(key = "orphaned-warning") {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.padding.small)
                                .clip(RoundedCornerShape(8.dp))
                                .combinedClickable(
                                    onClick = { showOrphanDialog = true }
                                ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(MaterialTheme.padding.medium),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.BookmarkBorder,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.size(MaterialTheme.padding.medium))
                                Column {
                                    Text(
                                        text = "${state.orphanedBookmarks.size} orphaned bookmarks",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        text = "Chapters not found. Please re-map them.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }

                groupedBookmarks.forEach { (chapterId, bookmarks) ->
                    val chapterName = bookmarks.first().chapterName
                    stickyHeader(
                        key = "header-$chapterId",
                        contentType = "chapter-header",
                    ) {
                        Surface(color = MaterialTheme.colorScheme.surface) {
                            Text(
                                text = chapterName,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = MaterialTheme.padding.medium,
                                        vertical = MaterialTheme.padding.small,
                                    ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item(
                        key = "items-$chapterId",
                        contentType = "chapter-items",
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = MaterialTheme.padding.extraSmall,
                                    end = MaterialTheme.padding.extraSmall,
                                    bottom = MaterialTheme.padding.small,
                                ),
                        ) {
                            bookmarks.chunked(3).forEach { rowItems ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    rowItems.forEach { bookmark ->
                                        BookmarkThumbnailTile(
                                            bookmark = bookmark,
                                            getThumbnailFile = getThumbnailFile,
                                            onTap = { onBookmarkClick(bookmark) },
                                            onLongPress = { selectedBookmark = bookmark },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    // Fill remaining empty slots so items stay the same width
                                    repeat(3 - rowItems.size) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showOrphanDialog) {
        OrphanBookmarksMigrateDialog(
            orphans = state.orphanedBookmarks,
            chapters = state.chapters,
            onDismissRequest = { showOrphanDialog = false },
            onMigrate = onMigrateBookmarks,
            getAutoMatchPreview = getAutoMatchPreview,
        )
    }

    // Long-press actions dialog
    selectedBookmark?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { selectedBookmark = null },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = bookmark.chapterName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(KMR.strings.page_bookmark_chapter_page, bookmark.pageIndex + 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(KMR.strings.page_bookmark_added_on, formatDate(bookmark.addedAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (bookmark.note.isNotBlank()) {
                        Text(
                            text = bookmark.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val b = bookmark
                    selectedBookmark = null
                    onBookmarkClick(b)
                }) {
                    Text(stringResource(KMR.strings.page_bookmark_open))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        editingNote = bookmark
                        noteText = bookmark.note
                        selectedBookmark = null
                    }) {
                        Text(stringResource(KMR.strings.page_bookmark_edit_note))
                    }
                    TextButton(onClick = {
                        bookmarkToDelete = bookmark
                        selectedBookmark = null
                    }) {
                        Text(
                            text = stringResource(KMR.strings.page_bookmark_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
        )
    }

    // Delete confirmation dialog
    bookmarkToDelete?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { bookmarkToDelete = null },
            text = { Text(stringResource(KMR.strings.page_bookmark_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteBookmark(bookmark)
                    bookmarkToDelete = null
                }) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkToDelete = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }

    // Note editing dialog
    editingNote?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { editingNote = null },
            title = { Text(stringResource(KMR.strings.page_bookmark_edit_note)) },
            text = {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    placeholder = { Text(stringResource(KMR.strings.page_bookmark_note_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUpdateNote(bookmark.id, noteText)
                    editingNote = null
                }) {
                    Text(stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingNote = null }) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrphanBookmarksMigrateDialog(
    orphans: List<PageBookmark>,
    chapters: List<Chapter>,
    onDismissRequest: () -> Unit,
    onMigrate: (Map<Long, Chapter>) -> Unit,
    getAutoMatchPreview: (List<PageBookmark>) -> Map<PageBookmark, Chapter?>,
) {
    val mappings = remember { mutableStateMapOf<Long, Chapter>() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 16.dp),
        title = {
            Text(
                text = "Map Orphaned Bookmarks",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
                text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Text(
                        text = "Select a new chapter for each bookmark, or use Auto-Match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items(orphans) { orphan ->
                    var expanded by remember { mutableStateOf(false) }
                    val selectedChapter = mappings[orphan.id]
                    val nameText = selectedChapter?.let {
                        if (it.scanlator.isNullOrBlank()) it.name else "${it.name} [${it.scanlator}]"
                    } ?: "Select Chapter"

                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Page ${orphan.pageIndex + 1} from ${orphan.chapterName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (orphan.chapterNumber != -1.0) {
                                            Text(
                                                text = "SAVED: ${orphan.chapterNumber}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                            )
                                            Text(
                                                text = "|",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                            )
                                            Text(
                                                text = "SCANLATOR: ${orphan.scanlator ?: "Any"}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }

                                Column(
                                    horizontalAlignment = Alignment.End,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.History,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = "ORPHANED",
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                    if (orphan.chapterNumber == -1.0) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.errorContainer,
                                            shape = MaterialTheme.shapes.small
                                        ) {
                                            Text(
                                                text = "OLD BACKUP",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            ExposedDropdownMenuBox(
                                expanded = expanded,
                                onExpandedChange = { expanded = it },
                            ) {
                                OutlinedTextField(
                                    value = nameText,
                                    onValueChange = {},
                                    readOnly = true,
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Outlined.Book,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                                    },
                                    shape = MaterialTheme.shapes.medium,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor(
                                            type = androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable
                                        )
                                )

                                ExposedDropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("None", color = MaterialTheme.colorScheme.onSurface) },
                                        onClick = {
                                            mappings.remove(orphan.id)
                                            expanded = false
                                        }
                                    )
                                    val filteredChapters = remember(orphan, chapters) {
                                        if (orphan.chapterNumber != -1.0) {
                                            val candidates = chapters.filter { it.chapterNumber == orphan.chapterNumber }
                                            candidates.ifEmpty { chapters }
                                        } else {
                                            chapters
                                        }
                                    }
                                    filteredChapters.forEach { chapter ->
                                        val textVal = if (chapter.scanlator.isNullOrBlank()) chapter.name else "${chapter.name} [${chapter.scanlator}]"
                                        DropdownMenuItem(
                                            text = { Text(textVal, color = MaterialTheme.colorScheme.onSurface) },
                                            onClick = {
                                                mappings[orphan.id] = chapter
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
confirmButton = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(
                    onClick = {
                        val autoMatched = getAutoMatchPreview(orphans)
                        mappings.clear()
                        autoMatched.forEach { (bookmark, chapter) ->
                            if (chapter != null) {
                                mappings[bookmark.id] = chapter
                            }
                        }
                    }
                ) {
                    Text(
                        text = "Auto-Match",
                        fontWeight = FontWeight.Bold
                    )
                }
                Button(
                    onClick = {
                        onMigrate(mappings)
                        onDismissRequest()
                    },
                    enabled = mappings.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Text(
                        text = "Migrate",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismissRequest
            ) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}



@Composable
private fun BookmarkThumbnailTile(
    bookmark: PageBookmark,
    getThumbnailFile: (PageBookmark) -> File?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbFile = remember(bookmark.id) { getThumbnailFile(bookmark) }

    Box(
        modifier = modifier
            .padding(MaterialTheme.padding.extraSmall)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        if (thumbFile != null) {
            val context = LocalPlatformContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(thumbFile)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyBookmarksContent(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            Icon(
                imageVector = Icons.Outlined.BookmarkBorder,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(KMR.strings.page_bookmarks_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(KMR.strings.page_bookmarks_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDate(epochMillis: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(epochMillis))
}
