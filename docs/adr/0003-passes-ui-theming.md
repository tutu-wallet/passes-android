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
theme is authoritative. In a consumer's own list only the pass tile takes a
pass color, and only `backgroundColor` (addendum below); the rest of the item
is list chrome.

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
- `BoundedImage` does not accept `bounds: ImageRenderBounds? = null`, and no
  parameter reaches its source ceiling. `bounds` sets only the size of the bitmap
  produced, so an `ImageRenderBounds(...)` above the default is a visible signal at
  the call site and still never yields a bitmap larger than its source.
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
lock. It forbids a second overload of `BoundedImage`, but not of `PassFront` or
`ExpiredOverlay`; review enforces those.

### D6. Telemetry follows the `TelemetryGuard` discipline

`UiTelemetryGuard` mirrors the structural-PII-prevention pattern of `TelemetryGuard` and `StorageTelemetryGuard`: every event method takes enums (`PassType`, `SignatureBand`, `SecurityIntentKind`, `ImageDecodeRejection`, `ParseFailureKind`, `BarcodeCreateKind`) and primitives only, never a `String`, `Pass` or `PassField`. `PublicApiSurfaceTest` fails if a free-form `String` parameter is added to an event method.

Events and the signal each carries:

- `onPassRendered(type, signatureBand)`: baseline.
- `onPassBackOpened(type)`: pass-back-tap rate.
- `onSecuritySheetShown` / `Confirmed` / `Dismissed(intentKind, type)`: shown is the denominator, confirmed and dismissed the numerators. A spike in dismissals is the phishing signal: users open the sheet and bail.
- `onImageDecodeRejected(reason)`: refused-image rate. Without a `PassImageCache`, one event per decode, so again each time the image re-enters composition; with one, at most one per refusal it holds (amendment below). An image that is only larger than its bounds is downsampled and fires nothing, so a spike means images in the wild that are not PNG, malformed or over their ceiling.
- `onImportConfirmShown` / `Confirmed` / `Dismissed(type, band)`, `onImportRejected(kind)`, and `onBarcodeCreateGateShown` / `Confirmed` / `Dismissed(kind)`: the import-confirm and barcode-create gates.

`NoopUiTelemetryGuard` is provided for hosts that have not wired a guard.

### D7. Contract types are pure Kotlin so the lock tests run on the JVM

Theming data classes, security intents, image bounds, the expired-state ADT and the telemetry interface are plain Kotlin types. `PublicApiSurfaceTest` and `ComposableSurfaceLockTest` pin their shape on the JVM CI host without an Android device.

## Consequences

- A reader can verify the trust-claim surface from `TRUST_CLAIMS.md` (what the surfaces are), `COMPOSABLE_SIGNATURES.md` (the API shape) and this ADR (why each shape is fixed).
- `BoundedImage` decodes through `ImageDecoder.setOnHeaderDecodedListener`, not Coil's default loader.

## Open questions

- Accessibility: every composable takes a `contentDescription: String?` where applicable, and the trust badge, expired pill and barcode altText must pass non-null descriptions. Accessibility-test coverage is incomplete.

## Addendum: class color on any item, issuer color on `PassFront` and the pass tile (D4)

An item's color has two sources and neither depends on signature status. A consumer may color any item by artifact class and let the user reassign it; `PassFront` and a pass tile may instead take colors the pass file declares.

- **D4's scope rule covers a consumer's list.** `Pass.colors` overrides the theme on `PassFront`. In a consumer's list, the pass tile of a pkpass item may show one image from the pass file on `Pass.colors.background`, and no other pass color reaches the item (`SCANNABLE_CARD_THREAT_MODEL.md`, consumer list concession, condition 4). The pass mark, the item's text and its controls sit outside the pass tile in the host theme, so a pass-supplied color cannot hide the mark or restyle host text. A consumer that renders its own pass faces applies no pass color outside a pass tile; one that composes `PassFront` gets D4's behavior.
- **Pass colors are the same for every signature status.** They are read from the pass file alone. No item color, face tint or pass tile color is chosen or varied by signature status, verification outcome, signing identity or an identity the pass file claims. The trust badge's `signatureBadge` colors (D2) are the one status-keyed color and stay on the badge.
- **Class colors reach kernel surfaces as consumer-supplied tints.** `ScannableCardScreen(faceTint)` and `DocumentView(faceTint)` take an already-constructed `Color`; the kernel stores none, parses none and infers nothing from one. Ink on a tinted face is derived from the tint's luminance (`inkOn`), pinned at 4.5:1 or better, so an opaque consumer color cannot render the label or the payload readback illegible; a translucent one composites over host paint the kernel cannot see.
- **The security-sheet family is untouched.** `B3UrlConfirmSheet`, `PhoneConfirmSheet` and `EmailConfirmSheet` read `MaterialTheme.colorScheme` and `LocalPassesSemantics` only. They take no tint parameter and must not: the attack D4 names applies verbatim to a user- or consumer-chosen color.
- **Color carries no trust meaning.** See `SCANNABLE_CARD_THREAT_MODEL.md` ("colour carries no trust meaning") and ADR 0005 D1.C.

Tests: `ScannableCardTrustSurfaceTest` (`inkOnClearsWcagAaAgainstEveryTintIncludingTheWorstCase`; a tint cannot suppress the label or payload; the code panel is literally white) and `ComposableSurfaceLockTest` (parameter-count locks on the three security sheets: adding a tint breaks the count, but swapping one of the six parameters for a color would not, so a reviewer reads the enumeration in each test's comment).

## Amendment to D5: `BoundedImage` downsamples to its bounds

`BoundedImage` first refused any image over 1920 px on a side or 4 MP, while the parser admits images up to 4096 x 4096 px of area, so an image could import and then never draw. `ImageRenderBounds` is now the output box only: a larger source is decoded at a whole-number division of its size that fits the box. The refusal moved to a fixed source ceiling of 4096 x 4096 px of area, equal to the parser's default cap, which `BoundedImage` applies from the header and no parameter raises.

Trade-off: the largest source the in-process decode accepts rose from 1920 x 1920 (3.7 MP) to 16.7 MP. The bitmap produced at the default bounds is no larger than before, and the whole-number division keeps the decoder from scaling down a full-size bitmap to produce it. What the codec itself buffers is the residual risk `TRUST_CLAIMS.md` section 6 states.

## Amendment to D5: `BoundedImage` decodes PNG only

Pass images are PNG by the format's contract, and `BoundedImage` holds them to it. It reads the PNG signature and IHDR chunk from the bytes first (`passes-core`'s `PngHeader`) and refuses anything else as `ImageDecodeRejection.NotPng` before the platform decoder is called; the decode is refused again unless the decoder reports `image/png`. A refused image is not decoded at all.

An interlaced PNG has a lower source ceiling, 1920 x 1920 px of area, because the platform codec holds an interlaced image whole at any output size. This reopens the "imports, then never draws" gap for one case: the parser admits an interlaced PNG up to its 4096 x 4096 default, and `BoundedImage` refuses it above 1920 x 1920 as `ExceedsArea`. Pass images at the sizes the format calls for are far below either.

Trade-off: a pass whose images are JPEG or another format under a `.png` name drew them before and draws nothing now. The parser leaves such an image out of a newly parsed pass (ADR 0001 addendum), so a consumer falls back as for a missing image; a row stored earlier still holds the bytes, and a consumer that wants the same fallback there tests them with `PngHeader.read`.

## Amendment to D5: `BoundedImage` decodes the picture chunks only

The platform PNG reader does work for chunks that draw nothing. Measured on API 34 and 36, it holds a text chunk for the whole decode, inflated when it is compressed, before or after the image data, and on API 36 it decodes a gain map carried in a PNG. The header checks bound pixels, not that work. So `BoundedImage` gives the platform decoder a copy built by `passes-core`'s `PngPicture`, holding IHDR, then sRGB, gAMA, cHRM, iCCP, PLTE and tRNS ahead of the image data, every IDAT and an empty IEND. Each of those kinds is decided by its first chunk alone, kept when its length is within the range the format sets for the kind and left out otherwise. Every other chunk is left out, with no list of chunks to refuse: a chunk the platform starts reading in a later release is left out too.

A filter was chosen over a refusal because design tools write text, XMP and EXIF chunks into ordinary images, and refusing those would stop real pass images from drawing. The colour chunks are kept because dropping them changes how a correctly tagged image is drawn. A colour profile is the one kept chunk besides the image data that is compressed, so it is inflated into a scratch buffer and kept only when its chunk is at most 263,168 bytes and holds one whole stream, with nothing after it, of at most 262,144 bytes that opens with its own inflated length, as an ICC profile does. One that is larger, unreadable or declares another length is left out, and the image draws from the colour chunks that remain.

Trade-offs. Nothing a left-out chunk says is applied: an EXIF orientation, a gain map, a `cICP` colour description. A file the PNG format calls invalid can draw: one cut at a chunk boundary is given its IEND, image data split by other chunks is joined, and a second header, an unknown critical chunk or a chunk left out for its length does not reach the codec to fail there. The pixels are the file's own either way. Each decode holds up to two more copies of the image's bytes while it builds the picture. A PNG that ends inside a chunk is `Malformed` from its bytes and never reaches the codec. The stored bytes stay as imported, since the manifest hashes cover them, so the filter runs at each display and not at import.

The isolated decodes (`passes-barcode`, `passes-image`) do not apply it. They accept other containers as well, and what a file costs there is spent inside a sandbox whose exhaustion or death the host reads as a rejection.

## Amendment to D5 and D6: `BoundedImage` reuses decodes through a cache the host holds

A list that draws one pass image per row ran every image through a fresh decode each time its row came into view: the row was blank for the decode, nothing limited how many decodes ran together, and a refused image was reported on every bind. Measured on an API 34 emulator, twelve interlaced 16-bit images decoding together peaked at about 400 MB of codec buffers.

`BoundedImage` takes an optional `PassImageCache`, which the host constructs, hoists to the list and can clear, and nothing else. `BoundedImage` reads it while composing, so a cached image draws in the first frame, and stores into it on the decode thread, so a decode that has started is kept when its row leaves; a clear since the row asked drops it instead. Entries are keyed by the image bytes and the bounds and evicted least recently used against a byte budget counting pixels and image bytes. The first result kept for a key wins, so rows decoding the same image at the same moment share one bitmap and one refusal, and a refusal is reported at most once while cached (D6). An evicted bitmap is not recycled, since a row on screen may still draw it. Separately, at most two pass image decodes run at once in the process, with or without a cache, which held that peak to 75 MB.

The shape follows `PdfThumbnailCache` in `passes-document-ui`: the cache is the host's to scope and size, while every decode and every limit stays in the kernel. A cache inside `BoundedImage` alone would keep issuer pixels for the life of the process with no budget or clear the host controls. Handing the host a bitmap or painter to cache would let decoded pixels reach whatever the host draws with them. A kernel pass tile could own the cache later; it would still need one at list scope, so nothing here waits on it.

Trade-offs. Image bytes and decoded pixels stay in memory while cached, also after their pass is deleted, so the app clears the cache on delete (an app-held control in `SECURITY.md`); nothing is written to disk. The key is the content of the bytes, so each entry into composition hashes them once, on the main thread. Rows that miss together each decode, and all but the first result are dropped. A refusal is not retried while cached, `Other` included. Two images decoding at once is a cap, not a queue order: a long decode delays the rows behind it.
