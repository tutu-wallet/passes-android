package `is`.walt.passes.ui

/**
 * The largest bitmap `BoundedImage` produces. A larger image is downsampled at decode time to fit
 * the box, aspect ratio kept; a smaller one is never upscaled. A list passes its tile size here.
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
