package `is`.walt.passes.ui

/**
 * The largest bitmap `BoundedImage` produces. A larger image is decoded at its size divided by the
 * smallest whole number that fits the box, so it comes back between half the box and the box.
 */
public data class ImageRenderBounds(
    public val maxWidthPx: Int,
    public val maxHeightPx: Int,
) {
    init {
        require(maxWidthPx > 0) { "maxWidthPx must be positive, was $maxWidthPx" }
        require(maxHeightPx > 0) { "maxHeightPx must be positive, was $maxHeightPx" }
    }

    public companion object {
        /** 1920 x 1920, well above every image size Apple documents for a pass. */
        public val Default: ImageRenderBounds = ImageRenderBounds(maxWidthPx = 1920, maxHeightPx = 1920)
    }
}
