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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

@Composable
fun PageBookmarksScreen(
    state: PageBookmarksScreen.State,
    navigateUp: () -> Unit,
    onBookmarkClick: (PageBookmark) -> Unit,
    onDeleteBookmark: (PageBookmark) -> Unit,
    onUpdateNote: (Long, String) -> Unit,
    getThumbnailFile: (PageBookmark) -> File?,
) {
    var selectedBookmark by remember { mutableStateOf<PageBookmark?>(null) }
    var bookmarkToDelete by remember { mutableStateOf<PageBookmark?>(null) }
    var editingNote by remember { mutableStateOf<PageBookmark?>(null) }
    var noteText by remember { mutableStateOf("") }

    // Group bookmarks by chapter, ordered by the user's chapter sort setting
    val groupedBookmarks = remember(state.bookmarks, state.chapterIdToOrder) {
        state.bookmarks
            .groupBy { it.chapterId }
            .toList()
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
        } else if (state.bookmarks.isEmpty()) {
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
