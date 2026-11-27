package `is`.walt.passes.document.ui.internal

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import `is`.walt.passes.pdf.android.RenderSourceRect
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Pinch-zoom and pan over one bitmap, translation clamped to the slot. While zoomed and idle, a
 * non-null [zoomedReplacement] draws at the on-screen box of the visible region.
 */
@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
internal fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String,
    modifier: Modifier = Modifier,
    minScale: Float = DEFAULT_MIN_SCALE,
    maxScale: Float = DEFAULT_MAX_SCALE,
    doubleTapScale: Float = DEFAULT_DOUBLE_TAP_SCALE,
    pageAspect: Float? = null,
    zoomedReplacement: ImageBitmap? = null,
    onZoomedRegionChanged: ((RenderSourceRect) -> Unit)? = null,
    onTransformStarted: (() -> Unit)? = null,
) {
    var scale by remember { mutableFloatStateOf(minScale) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var slotSize by remember { mutableStateOf(IntSize.Zero) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale * zoomChange).coerceIn(minScale, maxScale)
        val maxX = ((newScale - 1f) * slotSize.width / 2f).coerceAtLeast(0f)
        val maxY = ((newScale - 1f) * slotSize.height / 2f).coerceAtLeast(0f)
        val proposed = offset + panChange
        offset = Offset(
            proposed.x.coerceIn(-maxX, maxX),
            proposed.y.coerceIn(-maxY, maxY),
        )
        scale = newScale
        if (newScale <= minScale) offset = Offset.Zero
    }

    // onTransformStarted fires on the gesture's start edge, onZoomedRegionChanged on its settle
    // edge while zoomed; recomposition alone fires neither.
    if (onZoomedRegionChanged != null || onTransformStarted != null) {
        LaunchedEffect(transformableState) {
            snapshotFlow { transformableState.isTransformInProgress }
                .distinctUntilChanged()
                .collect { inProgress ->
                    if (inProgress) {
                        onTransformStarted?.invoke()
                    } else if (scale > minScale && slotSize != IntSize.Zero) {
                        onZoomedRegionChanged?.invoke(
                            visiblePageSubRect(scale, offset, slotSize, pageAspect),
                        )
                    }
                }
        }
    }

    val density = LocalDensity.current
    val transforming = transformableState.isTransformInProgress
    // Gated on scale too: a double-tap reset has no transform edge, and a stale sub-rect must
    // not show over the reset page.
    val replacementBox = if (zoomedReplacement != null && !transforming && scale > minScale) {
        visiblePageBoxOnScreen(scale, offset, slotSize, pageAspect)
    } else {
        null
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Clip on this unscaled layer, not the scaled one, so zoomed content never draws
            // outside the slot (ADR 0005 Z.7, ZoomableImageClipTest).
            .clipToBounds()
            .onSizeChanged { slotSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        // Any double-tap changes the transform; drop the stale sub-rect
                        // so it is never displayed against the new scale/offset.
                        onTransformStarted?.invoke()
                        if (scale > minScale) {
                            scale = minScale
                            offset = Offset.Zero
                        } else {
                            scale = doubleTapScale
                            offset = Offset.Zero
                            // No transform edge on double-tap, so request the sharp sub-rect
                            // render here, as a pinch settle would.
                            if (slotSize != IntSize.Zero) {
                                onZoomedRegionChanged?.invoke(
                                    visiblePageSubRect(doubleTapScale, Offset.Zero, slotSize, pageAspect),
                                )
                            }
                        }
                    },
                )
            }
            .transformable(state = transformableState, canPan = { scale > minScale }),
        contentAlignment = Alignment.Center,
    ) {
        if (replacementBox != null) {
            // The replacement shares its box's aspect ratio, so FillBounds places it at the
            // visible region's on-screen box without stretching.
            Image(
                bitmap = zoomedReplacement!!,
                contentDescription = contentDescription,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(replacementBox.left.roundToInt(), replacementBox.top.roundToInt()) }
                    .size(
                        with(density) { replacementBox.width.toDp() },
                        with(density) { replacementBox.height.toDp() },
                    ),
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    ),
            )
        }
    }
}

/**
 * The page rect in the slot ([page], letterbox excluded) and its intersection with the visible
 * window (`i*`), in untransformed slot pixels; null when the viewport shows only letterbox.
 */
private data class VisibleRegion(
    val ix0: Float,
    val iy0: Float,
    val ix1: Float,
    val iy1: Float,
    val page: PageRectInSlot,
    val visLeft: Float,
    val visTop: Float,
)

private fun visibleRegion(
    scale: Float,
    offset: Offset,
    slotSize: IntSize,
    pageAspect: Float?,
): VisibleRegion? {
    val slotW = slotSize.width.toFloat()
    val slotH = slotSize.height.toFloat()
    val page = pageRectInSlot(slotW, slotH, pageAspect)
    // Visible window in slot pixels at the current zoom: the slot mapped back through
    // the graphicsLayer transform.
    val visW = slotW / scale
    val visH = slotH / scale
    val visLeft = slotW / 2f - offset.x / scale - visW / 2f
    val visTop = slotH / 2f - offset.y / scale - visH / 2f
    val ix0 = maxOf(visLeft, page.left)
    val iy0 = maxOf(visTop, page.top)
    val ix1 = minOf(visLeft + visW, page.left + page.width)
    val iy1 = minOf(visTop + visH, page.top + page.height)
    if (ix1 <= ix0 || iy1 <= iy0) return null
    return VisibleRegion(ix0, iy0, ix1, iy1, page, visLeft, visTop)
}

/**
 * The visible region of the page as a normalised [RenderSourceRect.SubRect]. A null
 * [pageAspect] treats the slot as filling the page.
 */
@Suppress("ReturnCount")
internal fun visiblePageSubRect(
    scale: Float,
    offset: Offset,
    slotSize: IntSize,
    pageAspect: Float?,
): RenderSourceRect.SubRect {
    // Pan entirely into letterbox zero-areas the intersection; the renderer rejects
    // zero-area rects, so emit a tiny centred fallback.
    val r = visibleRegion(scale, offset, slotSize, pageAspect)
        ?: return RenderSourceRect.SubRect(0.49f, 0.49f, 0.51f, 0.51f)
    val nLeft = ((r.ix0 - r.page.left) / r.page.width).coerceIn(0f, 1f)
    val nTop = ((r.iy0 - r.page.top) / r.page.height).coerceIn(0f, 1f)
    val nRight = ((r.ix1 - r.page.left) / r.page.width).coerceIn(0f, 1f)
    val nBottom = ((r.iy1 - r.page.top) / r.page.height).coerceIn(0f, 1f)
    if (nRight <= nLeft || nBottom <= nTop) {
        return RenderSourceRect.SubRect(0.49f, 0.49f, 0.51f, 0.51f)
    }
    return RenderSourceRect.SubRect(nLeft, nTop, nRight, nBottom)
}

/**
 * The on-screen box of the visible page region, from the same intersection as
 * [visiblePageSubRect] so it has the rendered region's aspect; null over letterbox only.
 */
internal fun visiblePageBoxOnScreen(
    scale: Float,
    offset: Offset,
    slotSize: IntSize,
    pageAspect: Float?,
): ScreenBox? {
    val r = visibleRegion(scale, offset, slotSize, pageAspect) ?: return null
    // The visible window [visLeft, visTop, +visW, +visH] scales by `scale` to fill the
    // slot, so a base-space point bx maps to screen (bx - visLeft) * scale.
    return ScreenBox(
        left = (r.ix0 - r.visLeft) * scale,
        top = (r.iy0 - r.visTop) * scale,
        width = (r.ix1 - r.ix0) * scale,
        height = (r.iy1 - r.iy0) * scale,
    )
}

internal data class ScreenBox(val left: Float, val top: Float, val width: Float, val height: Float)

private data class PageRectInSlot(val left: Float, val top: Float, val width: Float, val height: Float)

private fun pageRectInSlot(slotW: Float, slotH: Float, pageAspect: Float?): PageRectInSlot {
    if (pageAspect == null || !pageAspect.isFinite() || pageAspect <= 0f) {
        return PageRectInSlot(0f, 0f, slotW, slotH)
    }
    val slotAspect = slotW / slotH
    return if (pageAspect > slotAspect) {
        val h = slotW / pageAspect
        PageRectInSlot(0f, (slotH - h) / 2f, slotW, h)
    } else {
        val w = slotH * pageAspect
        PageRectInSlot((slotW - w) / 2f, 0f, w, slotH)
    }
}

// PDF pages stay sharp at 5x because the visible region is re-rendered at viewport
// resolution, within the 4 MP per-bitmap cap; the image arm decodes once and upsamples.
internal const val DEFAULT_MIN_SCALE: Float = 1f
internal const val DEFAULT_MAX_SCALE: Float = 5f
internal const val DEFAULT_DOUBLE_TAP_SCALE: Float = 2f
