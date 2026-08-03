@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.fotoxplorr.app.experience

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.fotoxplorr.app.media.MediaAsset
import com.fotoxplorr.app.media.MediaImage
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
fun GalleryPreviewScreen(
    assets: List<MediaAsset>,
    initialIndex: Int = 0,
    onOpenAsset: (MediaAsset, List<MediaAsset>) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val visible = assets.filterNot { it.isTrashed }
    if (visible.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No media available for Gallery preview")
        }
        return
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, visible.lastIndex),
        pageCount = { visible.size },
    )
    val scope = rememberCoroutineScope()
    val focusRequester = androidx.compose.runtime.remember { FocusRequester() }
    val density = LocalDensity.current.density
    val current = visible[pagerState.currentPage.coerceIn(0, visible.lastIndex)]

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF08080B))
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        scope.launch { pagerState.animateScrollToPage((pagerState.currentPage - 1).coerceAtLeast(0)) }
                        true
                    }
                    Key.DirectionRight -> {
                        scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(visible.lastIndex)) }
                        true
                    }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        onOpenAsset(current, visible)
                        true
                    }
                    Key.Escape, Key.Back -> {
                        onClose()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Outlined.Close, contentDescription = "Close Gallery preview", tint = Color.White)
            }
            Column(Modifier.weight(1f)) {
                Text("Gallery preview", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${pagerState.currentPage + 1} of ${visible.size}",
                    color = Color.White.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { onOpenAsset(current, visible) }) {
                Icon(Icons.Outlined.Fullscreen, contentDescription = "Open full viewer", tint = Color.White)
            }
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 54.dp, vertical = 20.dp),
                pageSpacing = (-72).dp,
                beyondViewportPageCount = 3,
                flingBehavior = rememberSnapFlingBehavior(pagerState),
                key = { page -> visible[page].id.value },
            ) { page ->
                val pageOffset = (
                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).coerceIn(-3f, 3f)
                val absOffset = pageOffset.absoluteValue
                val selected = absOffset < 0.5f
                val asset = visible[page]

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp, vertical = if (selected) 8.dp else 44.dp)
                        .graphicsLayer {
                            rotationY = pageOffset * -42f
                            scaleX = 1f - absOffset.coerceAtMost(1f) * 0.22f
                            scaleY = 1f - absOffset.coerceAtMost(1f) * 0.22f
                            alpha = (1f - absOffset * 0.18f).coerceIn(0.36f, 1f)
                            translationX = pageOffset * 36f * density
                            cameraDistance = 18f * density
                            shadowElevation = if (selected) 28f else 8f
                            clip = true
                        }
                        .clip(MaterialTheme.shapes.large)
                        .background(Color(0xFF17171D))
                        .clickable {
                            if (selected) onOpenAsset(asset, visible)
                            else scope.launch { pagerState.animateScrollToPage(page) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MediaImage(
                        asset = asset,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            }

            IconButton(
                enabled = pagerState.currentPage > 0,
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                modifier = Modifier.align(Alignment.CenterStart),
            ) {
                Icon(Icons.Outlined.KeyboardArrowLeft, contentDescription = "Previous", tint = Color.White)
            }
            IconButton(
                enabled = pagerState.currentPage < visible.lastIndex,
                onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Icon(Icons.Outlined.KeyboardArrowRight, contentDescription = "Next", tint = Color.White)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = Color.White.copy(alpha = 0.72f))
            Column(Modifier.weight(1f)) {
                Text(current.displayName, color = Color.White, maxLines = 1)
                Text(
                    buildString {
                        append(current.mimeType.ifBlank { "media" })
                        if (current.width > 0 && current.height > 0) append(" · ${current.width} × ${current.height}")
                        current.bucketName?.let { append(" · $it") }
                    },
                    color = Color.White.copy(alpha = 0.62f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Text(
                if (current.isVideo) "Video" else "Open",
                color = Color.White,
                modifier = Modifier.clickable { onOpenAsset(current, visible) }.padding(8.dp),
            )
        }
    }
}
