package `is`.walt.passes.document

/**
 * The box every stored document thumbnail fits, aspect kept: page 0 of a PDF rendered to fit it, or
 * an imported image's raster scaled down into it. Changing it is a deliberate, test-breaking edit.
 */
public object DocumentThumbnailBounds {
    public const val MAX_WIDTH_PX: Int = 600
    public const val MAX_HEIGHT_PX: Int = 800
}
