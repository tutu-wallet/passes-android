package `is`.walt.passes.ui.theme

/**
 * Semantic slots a host supplies to `passes-ui` that Material 3 has no analogue for; all other
 * chrome comes from the host's `MaterialTheme` (ADR 0003). Colours are packed [ArgbColor]s.
 */
public data class PassesSemantics(
    public val signatureBadge: SignatureBadgeColors,
    public val expiredBadge: ExpiredBadgeStyle,
    public val securitySheet: SecuritySheetStyle,
)

/**
 * Color slots for the trust badge that appears on every rendered pass. The four slots
 * mirror `passes-core`'s `SignatureStatusKind`; adding an arm to `SignatureStatusKind`
 * forces a corresponding addition here, making "what color does the new trust band
 * wear?" a deliberate design choice rather than an unspecified default.
 *
 * `appleVerified` is the only slot that should read as positive / trusted; the others
 * read as informational, cautionary, or warning depending on the host's palette.
 * Concrete color values are the host theme's responsibility.
 */
public data class SignatureBadgeColors(
    public val unsignedBackground: ArgbColor,
    public val unsignedForeground: ArgbColor,
    public val selfSignedBackground: ArgbColor,
    public val selfSignedForeground: ArgbColor,
    public val appleVerifiedBackground: ArgbColor,
    public val appleVerifiedForeground: ArgbColor,
    public val certChainIncompleteBackground: ArgbColor,
    public val certChainIncompleteForeground: ArgbColor,
)

/**
 * Visual treatment of the "Expired" overlay. The overlay is non-suppressible — see
 * ADR 0003 D5 — so its style is fixed by the host theme rather than per-pass.
 *
 * [scrimAlpha] is the alpha (0..255) of the scrim that dims the front of an expired
 * pass. The scrim's RGB is the host's `MaterialTheme.colorScheme.scrim`; only the
 * alpha is contract-controlled here so a host can dial the badge up or down without
 * forking the rendering code.
 */
public data class ExpiredBadgeStyle(
    public val pillBackground: ArgbColor,
    public val pillForeground: ArgbColor,
    public val scrimAlpha: Int,
)

/**
 * Styling for the URL / phone / email confirmation sheets. These sheets present the
 * *visible* target of an outbound action (the actual URL, phone number, or address)
 * before the user confirms. The styling exists so the sheets visibly differ from
 * chrome dialogs and bottom sheets used elsewhere in walt-android, reducing the chance
 * of muscle-memory dismissal.
 *
 * [emphasisBackground] / [emphasisForeground] style the panel that contains the
 * to-be-actioned target string itself; [bodyForeground] styles the explanatory copy.
 *
 * [eyebrowForeground] and [mutedForeground] are consumed by the [B3EmphasisStyle.DomainHero]
 * layout (wpass-48v) for the `LEAVING THIS APP` / `CALLING` / `EMAILING` eyebrow and the
 * forensic / provenance / hairline chrome respectively. Defaults approximate M3
 * `colorScheme.outline` (eyebrow) and `outlineVariant` (muted) so a host that has not
 * yet wired R2 tokens still gets a reasonable hierarchy; production callers
 * (walt-android) override both to flow the brand palette through the same way
 * [bodyForeground] already does.
 */
public data class SecuritySheetStyle(
    public val sheetBackground: ArgbColor,
    public val emphasisBackground: ArgbColor,
    public val emphasisForeground: ArgbColor,
    public val bodyForeground: ArgbColor,
    public val confirmContainer: ArgbColor,
    public val confirmForeground: ArgbColor,
    public val cancelForeground: ArgbColor,
    public val eyebrowForeground: ArgbColor = ArgbColor(0xFF73777F.toInt()),
    public val mutedForeground: ArgbColor = ArgbColor(0xFFC4C7C5.toInt()),
)
