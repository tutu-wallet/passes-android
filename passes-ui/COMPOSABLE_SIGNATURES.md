# passes-ui composable signatures

The public-API contract for the composables the host (walt-android's `feature/passes`)
calls, with the properties that carry trust claims (see `TRUST_CLAIMS.md`). The code is
authoritative for exact signatures; imports assumed below:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import is.walt.passes.core.Barcode
import is.walt.passes.core.ImageBytes
import is.walt.passes.core.ImageRole
import is.walt.passes.core.Pass
import is.walt.passes.core.PassInstant
import is.walt.passes.core.PassLocale
import is.walt.passes.core.SignatureStatus
import is.walt.passes.ui.B3UrlIntent
import is.walt.passes.ui.EmailIntent
import is.walt.passes.ui.ExpiredOverlayState
import is.walt.passes.ui.ImageRenderBounds
import is.walt.passes.ui.PhoneIntent
import is.walt.passes.ui.SecurityIntent
import is.walt.passes.ui.UiTelemetryGuard
import is.walt.passes.ui.theme.PassesSemantics
```

## Theming entry point

```kotlin
/**
 * Provides the host's [PassesSemantics] to every passes-ui composable underneath. Wraps
 * the host's own MaterialTheme; the host is expected to call this inside its own theme
 * scope (e.g. inside walt-android's WaltTheme).
 */
@Composable
public fun PassesTheme(
    semantics: PassesSemantics,
    content: @Composable () -> Unit,
)

/** CompositionLocal accessor for the semantics. Reads default to an error in release. */
public val LocalPassesSemantics: ProvidableCompositionLocal<PassesSemantics>
```

The host MUST wrap pass-rendering composables in `PassesTheme(...)`. Reading
`LocalPassesSemantics.current` outside a `PassesTheme` scope is a programmer error and
fails fast.

## Pass front

```kotlin
/**
 * Renders the front of a pass. Layout switches on `pass.type`:
 *
 * - BoardingPass: header / primary "from / to" prominence / secondary / auxiliary, plus
 *   the barcode at the bottom.
 * - EventTicket: header / primary / secondary, with the strip image (if any) above
 *   primary fields.
 * - Coupon, StoreCard, Generic: header / primary / secondary / auxiliary with no
 *   special prominence.
 *
 * The pass's own [Pass.colors] override the host's MaterialTheme.colorScheme for the
 * pass surface only; chrome around the pass (the bottom sheet, the wallet list
 * background) keeps using the host theme.
 *
 * The signature trust badge and expired overlay default to always-on. The two
 * opt-in booleans below let a host suppress the kernel
 * chrome only when it has disclosed the equivalent signal in its own surrounding
 * UI; see ADR 0003 D5 for the bounded-opt-in rules.
 */
@Composable
public fun PassFront(
    pass: Pass,
    signatureStatus: SignatureStatus,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
    userLabel: String? = null,
    nowEpochMillis: Long = System.currentTimeMillis(),
    showSignatureBadge: Boolean = true,
    showExpiredOverlay: Boolean = true,
)
```

## Pass back

```kotlin
/**
 * Renders the back of a pass: the list of `Pass.backFields`, with detected URLs,
 * phone numbers, and email addresses rendered as tappable affordances.
 *
 * Tapping such an affordance fires the matching callback with the parsed
 * [SecurityIntent]; passes-ui never invokes the host's outbound Intent directly. The
 * host's expected wiring is to display the corresponding confirmation sheet and call
 * `Intent.ACTION_VIEW` (or `ACTION_DIAL`, `ACTION_SENDTO`) only on confirm.
 */
@Composable
public fun PassBack(
    pass: Pass,
    onUrlIntent: (B3UrlIntent) -> Unit,
    onPhoneIntent: (PhoneIntent) -> Unit,
    onEmailIntent: (EmailIntent) -> Unit,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
)
```

## Back-field link scanner

Public so consumers that render their own back-field layouts (no kernel `Surface`
card, flat rows on a host-owned gradient, etc.) can route through the canonical
detector instead of hand-rolling regex. The scanner returns spans whose intents
carry the *verbatim* substring from the field (no normalization, no scheme
injection), so a confirmation sheet built from a span shows the user exactly the
string that will leave the device. The trust contract (bidi-spoofing rejection,
RFC 3986 URL class, phone digit-count + adjacency hints, email shape) lives
entirely inside [FieldLinkScanner.scan], and `LinkSpan`'s constructor is
`internal`, so every span a consumer holds has been through that validation.
`copy(...)` on a scanner-produced span still works for styling needs.

`start` / `endExclusive` are UTF-16 char offsets into the field string passed to
`scan`, the same units `Regex`, `String.substring`, and `AnnotatedString` use.

```kotlin
public object FieldLinkScanner {
    public fun scan(fieldValue: String, source: SourceField): List<LinkSpan>
}

public data class LinkSpan internal constructor(
    public val start: Int,
    public val endExclusive: Int,
    public val intent: SecurityIntent,
)
```

## Barcode

```kotlin
/**
 * Renders [barcode] using ZXing. The composable enforces a minimum on-screen size
 * (240 dp x 240 dp for QR / Aztec; 320 dp x 96 dp for PDF417 / Code128) so the
 * barcode is reliably scannable at gate distance.
 *
 * `altText`, when present in the [Barcode], is rendered below the barcode for
 * accessibility and as a fallback for scanners that fail.
 */
@Composable
public fun BarcodeView(
    barcode: Barcode,
    modifier: Modifier = Modifier,
)
```

## Security confirmation sheets

Each sheet displays the verbatim target string from its [SecurityIntent], the source
field's label and the issuer's `organizationName`, then a confirm button.
`emphasisStyle` chooses between the original `Container` layout and the
`DomainHero` layout; both display the verbatim target, fire the same telemetry, and
block dispatch on explicit confirm. See ADR 0003 D5.

```kotlin
public sealed interface B3EmphasisStyle {
    public data object Container : B3EmphasisStyle
    public data object DomainHero : B3EmphasisStyle
}

@Composable
public fun B3UrlConfirmSheet(
    intent: B3UrlIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    emphasisStyle: B3EmphasisStyle = B3EmphasisStyle.Container,
)

@Composable
public fun PhoneConfirmSheet(
    intent: PhoneIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    emphasisStyle: B3EmphasisStyle = B3EmphasisStyle.Container,
)

@Composable
public fun EmailConfirmSheet(
    intent: EmailIntent,
    passType: PassType,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    emphasisStyle: B3EmphasisStyle = B3EmphasisStyle.Container,
)
```

The sheets fire `telemetry.onSecuritySheetShown` on first composition,
`telemetry.onSecuritySheetConfirmed` on confirm tap, and
`telemetry.onSecuritySheetDismissed` on any other dismissal. Confirming closes the
sheet AND calls `onConfirm`; dismissal closes WITHOUT firing `onConfirm`.

## Create-time URI confirmation gate (byte-capable formats)

```kotlin
/**
 * Create-time URI-scheme confirmation gate for a `ScannableCard`. Shown by the
 * consumer's create flow between input validation and persistence whenever the
 * classified [QrPayloadKind] would auto-act on a future scanner phone. Cancel is
 * the focused, filled action; Confirm is the lower-emphasis text button - inverted
 * from B3UrlConfirmSheet by design. Payload renders verbatim, wrapped in FSI/PDI
 * via `isolated()`; crypto addresses (Bitcoin / Ethereum) render in full with
 * monospace + wrap so create-time transcription review can see every character.
 *
 * [QrPayloadKind.PlainText] short-circuits to no output, so callers can keep the
 * gate unconditional; [requiresCreateConfirmation] lets callers branch when they
 * want to skip the composable entirely.
 *
 * Fires `telemetry.onBarcodeCreateGateShown` on first composition (per non-PlainText
 * payload kind), `onBarcodeCreateGateConfirmed` on confirm tap, and
 * `onBarcodeCreateGateDismissed` on cancel tap or any sheet dismissal. The kind
 * dimension is the coarse [BarcodeCreateKind] family of the underlying payload; the
 * user-typed string never crosses the boundary, matching the PII discipline pinned
 * by `PublicApiSurfaceTest`.
 */
@Composable
public fun BarcodeCreateConfirmSheet(
    payloadKind: QrPayloadKind,
    telemetry: UiTelemetryGuard,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
)

/**
 * Returns `false` for [QrPayloadKind.PlainText] and for the 1D symbologies, which
 * no scanner auto-acts on. Consumer create flows branch on this to persist
 * directly without invoking the gate. [format] is a parameter rather than an
 * assumed `Qr` so that a roster member added later is a compile error here rather
 * than a gate that quietly stops covering a format (C4).
 */
public fun QrPayloadKind.requiresCreateConfirmation(format: ScannableFormat): Boolean

/**
 * Test tags for the two action buttons on `BarcodeCreateConfirmSheet`. Exposed
 * so per-arm focus and click assertions can target the buttons structurally
 * rather than by their localized labels.
 */
public object BarcodeCreateConfirmTestTags {
    public const val Cancel: String
    public const val Confirm: String
}
```

## Expired badge

```kotlin
/**
 * Renders the non-suppressible expired/voided overlay. [state] is computed via
 * [ExpiredOverlayState.from]; if the host wants to "hide the badge" the only way is
 * to deliberately not call [PassFront] for that pass. The badge composable itself
 * has no `enabled` parameter.
 */
@Composable
public fun ExpiredOverlay(
    state: ExpiredOverlayState,
    modifier: Modifier = Modifier,
    locale: PassLocale = PassLocale("en"),
)
```

## Bounded image

```kotlin
/**
 * Decodes [bytes] as a PNG using Android `ImageDecoder`, with the source ceiling and
 * the [bounds] applied via `setOnHeaderDecodedListener`. An image larger than [bounds]
 * is downsampled to fit. The decoder is given only the chunks that carry the
 * picture; text, metadata and unknown chunks are left out. A refused image (not a
 * PNG, over its source ceiling, or undecodable) is not decoded, draws nothing and
 * fires `telemetry.onImageDecodeRejected`. An `OutOfMemoryError` from the decode
 * propagates. With a [cache], an image it holds draws at once and is not decoded
 * again; a refusal it holds is still reported. At most two pass image decodes run
 * at once in the process.
 *
 * Coil's default decoder must NOT be used for these images:
 * the bounds enforcement is the trust claim, and a third-party loader would have
 * to be re-audited for that property on every dependency upgrade.
 */
@Composable
public fun BoundedImage(
    bytes: ImageBytes,
    role: ImageRole,
    contentDescription: String?,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    bounds: ImageRenderBounds = ImageRenderBounds.Default,
    cache: PassImageCache? = null,
)

/**
 * What [BoundedImage] decoded or refused, kept up to [maxBytes] of pixels and image bytes. Hoist one per list
 * with `remember { PassImageCache() }`; clear it once a deleted pass has left the list. Only clearing is public.
 */
public class PassImageCache(maxBytes: Int = DEFAULT_MAX_BYTES) {
    public fun clear()

    public companion object {
        public const val DEFAULT_MAX_BYTES: Int = 32 * 1024 * 1024
    }
}
```

## What must not change

These signature properties are the trust-claim interface. Changing any of them is a
security-policy edit, not a refactor:

1. `onUrlIntent`, `onPhoneIntent`, `onEmailIntent` on `PassBack` are NOT defaulted to
   no-op. The host must supply them explicitly. This forces a conversation if a
   future host author "forgets" to wire confirmation sheets.
2. `ExpiredOverlay` has no `enabled` / `suppress` parameter. On `PassFront` the state
   comes only from `ExpiredOverlayState.from`; `showExpiredOverlay = false` is the
   single bounded host opt-out (ADR 0003 D5, TRUST_CLAIMS section 5).
3. The signature badge has no suppress parameter other than
   `PassFront.showSignatureBadge`, the single bounded host opt-out;
   `onPassRendered(type, band)` fires either way and the color comes from
   `PassesSemantics.signatureBadge`.
4. `BoundedImage` has no nullable `bounds` and no parameter that reaches the source
   ceiling. `bounds` sets only the size of the bitmap produced: a larger
   `ImageRenderBounds(...)` yields a larger bitmap, never one larger than its source.
   `cache` holds only what `BoundedImage` itself decoded or refused: `PassImageCache`
   has no public API (its other members are Kotlin `internal`) to put a bitmap in,
   take one out or change a limit, and no parameter hands `BoundedImage` pixels.
5. The security confirmation sheets do not accept a "skip confirmation" parameter.
   The host's outbound `Intent` fires only on user confirm.
6. `BarcodeCreateConfirmSheet` does not accept a `skipConfirmation`, per-arm
   silence flag, or "auto-confirm safe arms" toggle. PlainText is the only arm
   that short-circuits the gate, and that branch lives in the composable, not
   in a caller-visible parameter.
