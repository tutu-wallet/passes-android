package `is`.walt.passes.document.ui

/**
 * Where the inline document detail surface (`DocumentView`) carries its provenance signal.
 * [HostedTypeRow] is a bounded D5 concession (ADR 0005 D5.T); `FullScreenDocumentView` always docks (Z.8).
 */
public sealed interface TrustCaptionPlacement {
    /** The kernel renders its verbatim [DocumentTrustCaption] in its built-in position (default). */
    public data object Docked : TrustCaptionPlacement

    /** The kernel renders no caption; the host's own "Pass type" row carries provenance. */
    public data object HostedTypeRow : TrustCaptionPlacement
}
