package `is`.walt.passes.document

/**
 * Opaque identifier for a stored [ImageDocument]. Wrapped in a value class so calling code
 * cannot accidentally substitute a `String` from another domain (a pass id, a filename, a
 * user input) into APIs that expect a document id. The [DocumentId] arm for images, sibling
 * to [PdfDocumentId].
 */
@JvmInline
public value class ImageDocumentId(public override val value: String) : DocumentId

/**
 * An imported PNG, JPEG or WebP, a sibling of `Pass` like [PdfDocument]. [widthPx] and [heightPx]
 * are those of the raster decoded in the isolated sandbox; [displayLabel] never comes from EXIF/XMP.
 */
public data class ImageDocument(
    public override val id: ImageDocumentId,
    public override val displayLabel: String,
    public override val byteCount: Long,
    public val widthPx: Int,
    public val heightPx: Int,
    public override val importedAtEpochMs: Long,
    public override val provenance: Provenance = Provenance.UserProvided,
) : Document
