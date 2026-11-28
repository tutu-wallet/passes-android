package `is`.walt.passes.document

import `is`.walt.passes.core.ScannableFormat

/** A composite's id: its own [DocumentId] arm, so it cannot stand in for an image or PDF id. */
@JvmInline
public value class BarcodedImageDocumentId(public override val value: String) : DocumentId

/**
 * A stored image plus the code the isolated decoder read from it, kept only if the consumer's import
 * hook accepted that code (ADR 0005 C1). [displayLabel] is the consumer's, never read from the image or code.
 */
public data class BarcodedImageDocument(
    public override val id: BarcodedImageDocumentId,
    public override val displayLabel: String,
    public override val byteCount: Long,
    public val widthPx: Int,
    public val heightPx: Int,
    public val barcodePayload: String,
    public val barcodeFormat: ScannableFormat,
    public override val importedAtEpochMs: Long,
    public override val provenance: Provenance = Provenance.UserProvided,
) : Document
