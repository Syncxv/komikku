package eu.kanade.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    getThumbnailFile: (PageBookmark) -> File?,
) {
    var bookmarkToDelete by remember { mutableStateOf<PageBookmark?>(null) }

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
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                items(
                    items = state.bookmarks,
                    key = { it.id },
                ) { bookmark ->
                    PageBookmarkItem(
                        bookmark = bookmark,
                        onClick = { onBookmarkClick(bookmark) },
                        onDelete = { bookmarkToDelete = bookmark },
                        getThumbnailFile = getThumbnailFile,
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    bookmarkToDelete?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { bookmarkToDelete = null },
            text = {
                Text(stringResource(KMR.strings.page_bookmark_delete_confirm))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteBookmark(bookmark)
                        bookmarkToDelete = null
                    },
                ) {
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
}

@Composable
private fun PageBookmarkItem(
    bookmark: PageBookmark,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    getThumbnailFile: (PageBookmark) -> File?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Thumbnail
            BookmarkThumbnail(
                bookmark = bookmark,
                getThumbnailFile = getThumbnailFile,
                modifier = Modifier
                    .size(width = 80.dp, height = 120.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )

            // Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = bookmark.chapterName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(KMR.strings.page_bookmark_chapter_page, bookmark.pageIndex + 1),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatDate(bookmark.addedAt),
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

            // Delete button
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = stringResource(KMR.strings.page_bookmark_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BookmarkThumbnail(
    bookmark: PageBookmark,
    getThumbnailFile: (PageBookmark) -> File?,
    modifier: Modifier = Modifier,
) {
    val thumbFile = remember(bookmark.id) { getThumbnailFile(bookmark) }

    if (thumbFile != null) {
        val context = LocalPlatformContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(thumbFile)
                .build(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.FillWidth,
        )
    } else {
        // Placeholder when image is not available
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .aspectRatio(0.7f),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.BookmarkBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
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
