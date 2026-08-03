package com.fotoxplorr.app.media

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

@Composable
fun MediaImage(
    asset: MediaAsset,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    AsyncImage(
        model = asset.contentUri,
        contentDescription = asset.displayName,
        modifier = modifier,
        contentScale = contentScale,
    )
}
