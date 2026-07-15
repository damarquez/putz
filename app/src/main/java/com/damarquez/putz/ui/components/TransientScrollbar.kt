package com.damarquez.putz.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Draws a thin, auto-hiding scroll indicator on the trailing edge of a LazyColumn,
 * similar to the transient fast-scroll thumb seen in native mobile lists. It appears
 * while the list is being scrolled and fades out shortly after scrolling stops.
 */
@Composable
fun Modifier.transientVerticalScrollbar(
    listState: LazyListState,
    thumbWidth: Dp = 4.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    hideDelayMillis: Long = 800L,
): Modifier {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            alpha.snapTo(1f)
        } else {
            delay(hideDelayMillis)
            alpha.animateTo(0f, animationSpec = tween(durationMillis = 300))
        }
    }

    return this.drawWithContent {
        drawContent()

        val currentAlpha = alpha.value
        if (currentAlpha <= 0f) return@drawWithContent

        val layoutInfo = listState.layoutInfo
        val visibleItems = layoutInfo.visibleItemsInfo
        val totalItems = layoutInfo.totalItemsCount
        if (visibleItems.isEmpty() || totalItems == 0) return@drawWithContent

        val viewportHeight = layoutInfo.viewportSize.height.toFloat()
        val avgItemHeight = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
        val estimatedContentHeight = avgItemHeight * totalItems
        if (estimatedContentHeight <= viewportHeight) return@drawWithContent

        val firstVisible = visibleItems.first()
        val scrolledPastPx = firstVisible.index * avgItemHeight - firstVisible.offset
        val maxScrollPx = estimatedContentHeight - viewportHeight
        val scrollFraction = (scrolledPastPx / maxScrollPx).coerceIn(0f, 1f)

        val thumbHeightPx = (viewportHeight * (viewportHeight / estimatedContentHeight))
            .coerceIn(viewportHeight * 0.06f, viewportHeight)
        val maxThumbOffsetPx = viewportHeight - thumbHeightPx
        val thumbOffsetY = maxThumbOffsetPx * scrollFraction

        val thumbWidthPx = thumbWidth.toPx()
        drawRoundRect(
            color = thumbColor,
            alpha = currentAlpha * 0.6f,
            topLeft = Offset(size.width - thumbWidthPx, thumbOffsetY),
            size = Size(thumbWidthPx, thumbHeightPx),
            cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f),
        )
    }
}

/**
 * Same transient scroll indicator as [transientVerticalScrollbar], but for a plain
 * [Column] driven by [Modifier.verticalScroll] instead of a [LazyColumn].
 */
@Composable
fun Modifier.transientVerticalScrollbar(
    scrollState: ScrollState,
    thumbWidth: Dp = 4.dp,
    thumbColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    hideDelayMillis: Long = 800L,
): Modifier {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(scrollState.isScrollInProgress) {
        if (scrollState.isScrollInProgress) {
            alpha.snapTo(1f)
        } else {
            delay(hideDelayMillis)
            alpha.animateTo(0f, animationSpec = tween(durationMillis = 300))
        }
    }

    return this.drawWithContent {
        drawContent()

        val currentAlpha = alpha.value
        if (currentAlpha <= 0f) return@drawWithContent

        val viewportHeight = scrollState.viewportSize.toFloat()
        val maxScrollPx = scrollState.maxValue.toFloat()
        if (viewportHeight <= 0f || maxScrollPx <= 0f) return@drawWithContent

        val contentHeight = viewportHeight + maxScrollPx
        val scrollFraction = (scrollState.value.toFloat() / maxScrollPx).coerceIn(0f, 1f)

        val thumbHeightPx = (viewportHeight * (viewportHeight / contentHeight))
            .coerceIn(viewportHeight * 0.06f, viewportHeight)
        val maxThumbOffsetPx = viewportHeight - thumbHeightPx
        val thumbOffsetY = maxThumbOffsetPx * scrollFraction

        val thumbWidthPx = thumbWidth.toPx()
        drawRoundRect(
            color = thumbColor,
            alpha = currentAlpha * 0.6f,
            topLeft = Offset(size.width - thumbWidthPx, thumbOffsetY),
            size = Size(thumbWidthPx, thumbHeightPx),
            cornerRadius = CornerRadius(thumbWidthPx / 2f, thumbWidthPx / 2f),
        )
    }
}
