package `is`.walt.passes.document

import `is`.walt.passes.image.android.ImageDecodeRejectedKind

/**
 * The outcome of [DocumentImporter.import]. Each reject arm keeps its backend's own taxonomy (ADR 0005 I2);
 * [StorageHandoffFailed] means `persist` threw.
 */
public sealed interface DocumentImportResult {
    public data class ImportedPdf(public val doc: PdfDocument) : DocumentImportResult

    public data class ImportedImage(public val doc: ImageDocument) : DocumentImportResult

    /** An image whose isolated decode found a code that the consumer's `confirmBarcode` hook accepted. */
    public data class ImportedBarcodedImage(
        public val doc: BarcodedImageDocument,
    ) : DocumentImportResult

    public data class PdfRejected(public val kind: DocumentRejectedKind) : DocumentImportResult

    public data class ImageRejected(public val kind: ImageDecodeRejectedKind) : DocumentImportResult

    public data object Unrecognized : DocumentImportResult

    public data object StorageHandoffFailed : DocumentImportResult
}
