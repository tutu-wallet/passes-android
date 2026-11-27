package `is`.walt.passes.document

import `is`.walt.passes.core.ScannableFormat

/**
 * What [DocumentImporter] hands `persist`: the original [bytes] to store verbatim, and [thumbnailBytes], a PNG the
 * kernel encoded to fit [DocumentThumbnailBounds] (page 0 for a PDF, the sandbox raster scaled down for an image).
 */
public sealed interface DocumentPersist {
    public val label: String
    public val bytes: ByteArray
    public val thumbnailBytes: ByteArray

    public data class Pdf(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val pageCount: Int,
    ) : DocumentPersist

    /**
     * A plain image. [barcodeExtraction] names why it is not a composite; it defaults to
     * [BarcodeExtractionOutcome.NotAttempted], the outcome for a caller that never opted in.
     */
    public data class Image(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val format: ImageFormat,
        public val widthPx: Int,
        public val heightPx: Int,
        public val barcodeExtraction: BarcodeExtractionOutcome = BarcodeExtractionOutcome.NotAttempted,
    ) : DocumentPersist

    /**
     * A composite artifact (wpass-8lu): an image plus a barcode extracted from it, persisted as
     * ONE row. The image half mirrors [Image]; [barcodePayload] / [barcodeFormat] are the
     * isolated-decode result. The consumer maps this to `DocumentInsert.BarcodedImage`.
     */
    public data class BarcodedImage(
        public override val label: String,
        public override val bytes: ByteArray,
        public override val thumbnailBytes: ByteArray,
        public val format: ImageFormat,
        public val widthPx: Int,
        public val heightPx: Int,
        public val barcodePayload: String,
        public val barcodeFormat: ScannableFormat,
    ) : DocumentPersist
}
