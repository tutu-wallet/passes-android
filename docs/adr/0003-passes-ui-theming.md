# ADR 0003: passes-ui theming and security composables

- Status: Accepted
- Decision context: `decision-wlt-0tn-q3-2` (in-app B3 confirmation), `decision-wlt-0tn-q5` (library shape), in [`docs/brainstorm-decisions.md`](../brainstorm-decisions.md)

## Context

`passes-ui` is the Android + Compose surface the host wallet calls to render passes and mediate the security-confirmation dialogs that pass-embedded URLs, phone numbers and email addresses require. This ADR fixes the theming contract the host supplies, the composable signatures the host calls, and the trust-claim-bearing flows. `COMPOSABLE_SIGNATURES.md` holds the API shape and `TRUST_CLAIMS.md` the surfaces.

## Decisions

### D1. The host's MaterialTheme is the source of truth for color and typography

`passes-ui` declares no brand palette, typography scale or font family. It reads the host's `MaterialTheme.colorScheme`, `MaterialTheme.typography` and `MaterialTheme.shapes` at composition time. A parallel theming contract that the host populates was rejected:

- **Single source of truth.** The host theme is the only place a designer changes a brand color; a parallel contract is a drift surface where `passes-ui` could be styled inconsistently with the rest of the wallet.
- **Material3-native.** The host already builds on M3, so `passes-ui` composables read `MaterialTheme.colorScheme.surface` like every other Compose surface, with no translation layer.
- **Review value.** A reader auditing this repository sees composables that read `MaterialTheme.X`; duplicating tokens here would force them to verify a copy is in sync.

### D2. `PassesSemantics` adds the slots M3 does not name

A small set of `passes-ui`-specific slots have no Material3 analogue. They are
expressed as a `PassesSemantics` data class and threaded through composition via
a single `LocalPassesSemantics` provided at `PassesTheme(...)` scope:

- `signatureBadge`: per-`SignatureStatusKind` foreground / background pair for
  the trust badge that sits on every rendered pass.
- `expiredBadge`: pill foreground / background and scrim alpha for the
  non-suppressible expired / voided overlay.
- `securitySheet`: emphasis, body, confirm, cancel slots for the URL / phone /
  email confirmation sheets, explicitly distinct from chrome dialogs so the
  user does not muscle-memory through.
- `categoryAccent`: per-`PassType` accent strip color for the wallet-list view.

Color values are packed ARGB integers (`ArgbColor(0xAARRGGBB)`, in `passes-ui-core`), matching `passes-core`'s `ColorValue.rgb` shape with an alpha channel. Compose-side conversion is a one-line `Color(argb.argb.toLong())` at the API boundary. The contract type stays primitive so the JVM `PublicApiSurfaceTest` exercises the whole shape without a device.

### D3. The host MUST scope passes-ui composables in `PassesTheme(semantics) { ... }`

There is no fallback default for `LocalPassesSemantics`. Reading it outside a `PassesTheme` scope throws `IllegalStateException` at first composition.

Why fail-fast: a silent default produces a "looks fine in dev, wrong in prod" hazard where the trust badge renders in a neutral the host did not approve.

### D4. Pass colors override the theme on the pass surface only

The PKPASS spec lets each pass declare its own `foregroundColor`,
`backgroundColor`, and `labelColor` (already surfaced as `PassColors` in
`passes-core`). On the pass surface itself, those colors override the host's
`MaterialTheme.colorScheme.surface` / `onSurface`. Off the pass, wallet list
chrome, bottom sheets, expired overlay, security confirmation sheets, the host
theme is authoritative.

Why scoped: a pass-supplied color does not get to recolor a security confirmation sheet; letting the pass influence them opens an "adversarial pass darkens cancel button into invisibility" attack.

The override is implemented by reading `Pass.colors` inside `PassFront` and
applying it to the pass-front composable's local surface, not by mutating
`MaterialTheme.colorScheme`. Composables outside `PassFront` (the security
sheet family) read `MaterialTheme.colorScheme` and `LocalPassesSemantics`
directly and ignore `Pass.colors` entirely.

### D5. Trust-claim composables have no off switch

Five composable surfaces are trust-claim-bearing: the signature trust badge,
the expired/voided overlay, the URL confirmation sheet, the phone confirmation
sheet, and the email confirmation sheet. Their public composable signatures
deliberately omit the parameters that would let a caller suppress them:

- `PassFront` does not accept `expiredOverlay: ExpiredOverlayState`. The state
  is computed inside the composable from `Pass.expirationDate`, `Pass.voided`,
  and a `nowEpochMillis` parameter. There is no API path that lets a host
  display an expired pass as not-expired.
- `B3UrlConfirmSheet`, `PhoneConfirmSheet`, `EmailConfirmSheet` do not accept a
  `skipConfirmation: Boolean` shortcut. The host's outbound `Intent` fires
  inside `onConfirm`, which the user's tap is the only thing that calls.
- `BoundedImage` does not accept `bounds: ImageRenderBounds? = null`. The
  default is the only way to take the default; opting out requires explicitly
  constructing an `ImageRenderBounds(...)` with higher caps, which is a visible
  signal at the call site.
- `PassBack` does not default the `onUrlIntent` / `onPhoneIntent` /
  `onEmailIntent` callbacks. The host must wire all three; missing any one is
  a compile error, not a runtime no-op.

#### Bounded opt-ins for cross-chrome disclosure

Two cases admit a single opt-in boolean each: the host has already disclosed the same signal in its own chrome and the kernel chrome would double-label the user. Both are explicit, non-default, and pinned in `ComposableSurfaceLockTest` so they cannot grow without review:

- `PassFront.showSignatureBadge: Boolean = true`. `false` suppresses the pill but not the `onPassRendered(type, band)` telemetry; the band is still derived and recorded. For surfaces where a sibling host chip already shows the band. A host that opts out without rendering an equivalent disclosure breaches the contract; review at the call site.
- `PassFront.showExpiredOverlay: Boolean = true`. `false` suppresses the scrim composition only; the underlying `Pass.expirationDate` / `voided` data is unchanged, the `ExpiredOverlay` composable stays non-suppressible if a host calls it directly, and no API *displays* an arbitrary expiry state. For surfaces where a sibling host eyebrow and pill already present the same status.

`B3UrlConfirmSheet`, `PhoneConfirmSheet` and `EmailConfirmSheet` take `emphasisStyle: B3EmphasisStyle = Container`, a pure layout switch, not a suppression. `Container` and `DomainHero` both render the verbatim target on-screen, fire the same telemetry on the same gestures and block dispatch on an explicit confirm tap. The split exists so the verbatim target does not visually equate with the issuer Verified band on hosts that surface both.

#### What is still forbidden

Removing or weakening any non-opt-out property above is a deliberate
security-policy edit, not a refactor. The bounded opt-ins do NOT open the door
to:

- `PassFront(.., signatureBand: SignatureBand = ...)` or any path that lets a
  host *display* a band different from what `signatureStatus.toBand()` produces.
- `PassFront(.., expiredOverlay: ExpiredOverlayState = ...)` or any path that
  lets a host *display* an expiry state different from
  `ExpiredOverlayState.from(pass, nowEpochMillis)`.
- A `skipConfirmation: Boolean` on the security sheets.
- An overload of `ExpiredOverlay` or `BoundedImage` that elides the existing
  shape.

`ComposableSurfaceLockTest` pins the user-visible parameter count of each
surface's widest overload (`PassFront`'s is nine), so adding a parameter fails the
lock. It does not forbid a second `PassFront` overload or an overload of
`ExpiredOverlay` or `BoundedImage`; review enforces those.

### D6. Telemetry follows the `TelemetryGuard` discipline

`UiTelemetryGuard` mirrors the structural-PII-prevention pattern of `TelemetryGuard` and `StorageTelemetryGuard`: every event method takes enums (`PassType`, `SignatureBand`, `SecurityIntentKind`, `ImageDecodeRejection`, `ParseFailureKind`, `BarcodeCreateKind`) and primitives only, never a `String`, `Pass` or `PassField`. `PublicApiSurfaceTest` fails if a free-form `String` parameter is added to an event method.

Events and the signal each carries:

- `onPassRendered(type, signatureBand)`: baseline.
- `onPassBackOpened(type)`: pass-back-tap rate.
- `onSecuritySheetShown` / `Confirmed` / `Dismissed(intentKind, type)`: shown is the denominator, confirmed and dismissed the numerators. A spike in dismissals is the phishing signal: users open the sheet and bail.
- `onImageDecodeRejected(reason)`: bound-violation rate; a spike means malformed archives in the wild or a bound that needs tuning.
- `onImportConfirmShown` / `Confirmed` / `Dismissed(type, band)`, `onImportRejected(kind)`, and `onBarcodeCreateGateShown` / `Confirmed` / `Dismissed(kind)`: the import-confirm and barcode-create gates.

`NoopUiTelemetryGuard` is provided for hosts that have not wired a guard.

### D7. Contract types are pure Kotlin so the lock tests run on the JVM

Theming data classes, security intents, image bounds, the expired-state ADT and the telemetry interface are plain Kotlin types. `PublicApiSurfaceTest` and `ComposableSurfaceLockTest` pin their shape on the JVM CI host without an Android device.

## Consequences

- A reader can verify the trust-claim surface from `TRUST_CLAIMS.md` (what the surfaces are), `COMPOSABLE_SIGNATURES.md` (the API shape) and this ADR (why each shape is fixed).
- `BoundedImage` decodes through `ImageDecoder.setOnHeaderDecodedListener`, not Coil's default loader.

## Open questions

- Accessibility: every composable takes a `contentDescription: String?` where applicable, and the trust badge, expired pill and barcode altText must pass non-null descriptions. Accessibility-test coverage is incomplete.

## Addendum: card color is class-based and user-assigned, not issuer-derived (D4)

Colour means class and nothing else. The pass file's `backgroundColor` is read, never rendered, on a consumer's list item: every pkpass renders the same class tint regardless of `pass.json`, every other artifact class gets its own class tint, and the user can reassign any item's color from a picker on the detail surface.

- **D4 itself is unchanged.** `Pass.colors` overrides the theme on the kernel pass surface (`PassFront`) and nowhere else. A consumer that renders its own card faces ignores `PassColors`; one that composes `PassFront` gets D4's behavior.
- **Colors reach kernel surfaces as consumer-supplied tints.** `ScannableCardScreen(faceTint)` and `DocumentView(faceTint)` take an already-constructed `Color`; the kernel stores none, parses none and infers nothing from one. Ink on a tinted face is derived from the tint's luminance (`inkOn`), pinned at 4.5:1 or better, so an arbitrary consumer color cannot render the in-words trust text illegible. This is the theming analogue of D5's "no off switch."
- **The security-sheet family is untouched.** `B3UrlConfirmSheet`, `PhoneConfirmSheet` and `EmailConfirmSheet` read `MaterialTheme.colorScheme` and `LocalPassesSemantics` only. They take no tint parameter and must not: the attack D4 names applies verbatim to a user- or consumer-chosen color.
- **Color carries no trust meaning.** See `SCANNABLE_CARD_THREAT_MODEL.md` ("colour carries no trust meaning") and ADR 0005 D1.C.

Tests: `ScannableCardTrustSurfaceTest` (`inkOnClearsWcagAaAgainstEveryTintIncludingTheWorstCase`; a tint cannot suppress barcode, label, payload or trust caption; the code panel is literally white) and `ComposableSurfaceLockTest` (parameter-count locks on the three security sheets: adding a tint breaks the count, but swapping one of the six parameters for a color would not, so a reviewer reads the enumeration in each test's comment).
