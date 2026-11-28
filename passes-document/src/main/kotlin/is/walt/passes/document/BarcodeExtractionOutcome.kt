package `is`.walt.passes.document

import `is`.walt.passes.core.DecodeFailureReason

/**
 * Why an imported image stayed a plain image. No payload here: a boarding pass's names its passenger,
 * so only [DocumentPersist.BarcodedImage] carries one, after the consumer's hook accepted it.
 */
public sealed interface BarcodeExtractionOutcome {
    /** No `confirmBarcode` hook, so no decode ran: whether the image holds a code is unknown. */
    public data object NotAttempted : BarcodeExtractionOutcome

    /** The isolated decode completed and located no barcode in the image. */
    public data object NoCodeFound : BarcodeExtractionOutcome

    /** The isolated decode could not complete; [reason] is the decoder's own bucket, verbatim. */
    public data class Failed(public val reason: DecodeFailureReason) : BarcodeExtractionOutcome

    /** The consumer's hook declined the decoded code, or threw, so a confirm-UI bug cannot fail the import. */
    public data object Declined : BarcodeExtractionOutcome
}
