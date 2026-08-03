package com.fotoxplorr.app.gallery

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.fotoxplorr.app.organize.LibraryState
import com.fotoxplorr.app.organize.MediaCollection

@Composable
fun LibraryScreen(
    library: LibraryState,
    privateAlbumCount: Int,
    trashCount: Int,
    onOpenCollection: (MediaCollection) -> Unit,
    onOpenTag: (String) -> Unit,
    onOpenArchive: () -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPrivateFolders: () -> Unit,
    onOpenSettings: () -> Unit,
    onExportMetadata: () -> Unit,
    onImportMetadata: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (privateAlbumCount > 0) {
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenPrivateFolders,
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null)
                Text(" Browse $privateAlbumCount protected folder${if (privateAlbumCount == 1) "" else "s"}")
            }
        }
        LibraryScreen(
            library = library,
            privateAlbumCount = privateAlbumCount,
            trashCount = trashCount,
            onOpenCollection = onOpenCollection,
            onOpenTag = onOpenTag,
            onOpenArchive = onOpenArchive,
            onOpenTrash = onOpenTrash,
            onOpenSettings = onOpenSettings,
            onExportMetadata = onExportMetadata,
            onImportMetadata = onImportMetadata,
        )
    }
}
