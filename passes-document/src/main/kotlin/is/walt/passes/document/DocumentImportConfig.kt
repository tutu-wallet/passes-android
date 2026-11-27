package `is`.walt.passes.document

/**
 * [DocumentImporter]'s caps: [maxBytes] bounds the read before sniffing; [maxImageDecodePx] bounds the image
 * sandbox's raster per side, which gives the dimensions and is scaled into [DocumentThumbnailBounds] as the thumbnail.
 */
public data class DocumentImportConfig(
    public val maxBytes: Long = PdfImportConfig.DEFAULT_MAX_BYTES,
    public val pdfConfig: PdfImportConfig = PdfImportConfig(),
    public val imageTelemetryGuard: ImageImportTelemetryGuard = ImageImportTelemetryGuard.NoOp,
    public val maxImageDecodePx: Int = DEFAULT_MAX_IMAGE_DECODE_PX,
) {
    public companion object {
        public const val DEFAULT_MAX_IMAGE_DECODE_PX: Int = 2048
    }
}
