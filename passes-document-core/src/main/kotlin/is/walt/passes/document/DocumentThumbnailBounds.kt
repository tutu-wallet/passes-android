package `is`.walt.passes.document

/**
 * The box the importers fit a stored thumbnail to: a PDF's page 0 centred on white in exactly this size, or an
 * image's raster scaled down into it, aspect kept. Changing it is a deliberate, test-breaking edit.
 */
public object DocumentThumbnailBounds {
    public const val MAX_WIDTH_PX: Int = 600
    public const val MAX_HEIGHT_PX: Int = 800
}
