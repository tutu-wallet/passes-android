package `is`.walt.passes.document

import kotlin.math.roundToInt

internal data class ThumbnailSize(val widthPx: Int, val heightPx: Int)

/** [widthPx] x [heightPx] fitted into [DocumentThumbnailBounds], aspect kept and never enlarged. */
internal fun thumbnailSize(widthPx: Int, heightPx: Int): ThumbnailSize {
    val maxW = DocumentThumbnailBounds.MAX_WIDTH_PX
    val maxH = DocumentThumbnailBounds.MAX_HEIGHT_PX
    val fit = minOf(maxW.toFloat() / widthPx, maxH.toFloat() / heightPx, 1f)
    return ThumbnailSize(
        widthPx = (widthPx * fit).roundToInt().coerceIn(1, maxW),
        heightPx = (heightPx * fit).roundToInt().coerceIn(1, maxH),
    )
}

/** Sizes to scale through from [from] to [to]: halvings while the half still covers [to], then [to]. */
internal fun scaleSteps(from: ThumbnailSize, to: ThumbnailSize): List<ThumbnailSize> {
    val steps = mutableListOf<ThumbnailSize>()
    var current = from
    while (current.widthPx / 2 >= to.widthPx && current.heightPx / 2 >= to.heightPx) {
        current = ThumbnailSize(current.widthPx / 2, current.heightPx / 2)
        steps += current
    }
    if (current != to) steps += to
    return steps
}
