package `is`.walt.passes.ui

/**
 * Where a `ScannableCard` detail surface (`ScannableCardScreen`) carries its provenance signal.
 * [HostedTypeRow] is a bounded C2 concession, argued in `docs/SCANNABLE_CARD_THREAT_MODEL.md`.
 */
public sealed interface TrustCaptionPlacement {
    /** The kernel docks its verbatim "Created by you" caption at the bottom of the surface (default). */
    public data object Docked : TrustCaptionPlacement

    /** The kernel renders no caption; the host's own "Pass type" row carries provenance. */
    public data object HostedTypeRow : TrustCaptionPlacement
}
