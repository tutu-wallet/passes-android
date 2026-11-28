# passes-ui trust-claim-bearing UI flows

The trust claim that this repository carries is "every security-and-privacy-critical
behavior Walt makes about pass handling lives in code you can read." For `passes-ui`,
that decomposes into six surfaces. Each lists what the user sees, the trust claim, and
how tests pin it. Where a surface leaves a control to the host, its section says what
the host must do.

## 1. Signature trust badge

**What the user sees.** A small pill on every rendered `PassFront`, color-coded by
`SignatureStatus`:

| Status                  | Pill copy           | Meaning                                                  |
|-------------------------|---------------------|----------------------------------------------------------|
| `Unsigned`              | "Unsigned"          | The archive carried no signature.                        |
| `SelfSigned`            | "Self-signed"       | A signature was present but did not chain to Apple's CA. |
| `AppleVerified`         | "Verified"          | The PKCS#7 signature chained to Apple's WWDR root.       |
| `CertChainIncomplete`   | "Signature unknown" | A signature was present but the chain was incomplete.    |

**Trust claim.** The badge reflects the `SignatureStatus` the host passes to
`PassFront`; the UI cannot upgrade or downgrade it. Passing the status
`passes-storage` recorded (`StoredPass`) is a host obligation the kernel cannot
enforce.

`PassFront.showSignatureBadge` (default `true`) is a presentation opt-out for a host
that has already disclosed the band in its own chrome (e.g. walt-android's
import-confirm `TrustChip`), so the user is not shown two different "verified"
affordances. The band itself remains non-suppressible: `onPassRendered(type, band)`
fires regardless of the boolean, and the band is derived from the status the host passes.
A host that opts out without disclosing the band elsewhere breaks the contract.
`ComposableSurfaceLockTest.passFrontHasExactlyNineUserVisibleParameters` forces a
reviewer conversation if anyone adds a further suppression parameter.

**How tested.**

- `PublicApiSurfaceTest` pins `SignatureBand` to the four documented bands.
- No test in this repo pins the pill color to
  `PassesSemantics.signatureBadge.<bandBackground>`, and none can pin that a caller
  displays the stored `SignatureStatus` while `PassFront` takes the status as a
  parameter.

## 2. URL confirmation (B3 sheet family)

**What the user sees.** When the user taps a URL on the back of a pass, a bottom
sheet shows the issuer's `organizationName`, the source field's label, the verbatim
URL the host is about to open, and a "Confirm" / "Cancel" pair.

**Trust claim.** Three parts.

1. *No URL leaves the device without the user seeing the verbatim target.* The host
   does not open `Intent.ACTION_VIEW` for a pass-derived URL except inside the
   confirm callback of `B3UrlConfirmSheet`. This holds for both
   `B3EmphasisStyle.Container` and `B3EmphasisStyle.DomainHero`: the latter places the
   verbatim URL in a smaller monospace "forensic row" rather than the orange
   container, but the string is still on-screen before the user can tap confirm.
2. *The displayed URL is identical to the URL that opens.* The sheet does not show
   `example.com` and open `attacker.example`. The string in [B3UrlIntent.url] IS the
   string the host hands to its `Intent`. The `DomainHero` layout's "hero" line
   shows [B3UrlIntent.registrableDomain] when present, but this is a presentation
   aid above the verbatim row, never a substitute. A `B3UrlIntent` constructed
   with a hero string that disagrees with the URL still routes the URL to the
   `Intent`; the registrable-domain field is structurally non-actionable.
3. *The displayed URL is rendered as it was typed.* The sheet defends against the
   Unicode-bidi class of attacks, where a pass author embeds U+202E
   (Right-to-Left Override), zero-width marks, or other formatting/control
   characters in a URL so the visual rendering of the string differs from the
   bytes that resolve via `Uri.parse`. Two layers:
   - **Detection-stage rejection.** `FieldLinkScanner.containsFieldHazard`
     rejects any field whose value contains a Unicode Cf (format) or Cc (control)
     codepoint, or the line and paragraph separators U+2028 / U+2029. Only line
     feed, tab, and carriage return directly followed by line feed are allowed,
     since they separate lines and columns in real back fields; a lone carriage
     return is rejected because it may render with no width and hide where a link
     ends. The rejection is field-level: a clean URL adjacent to a hostile one in
     the same field becomes non-tappable too, since the surrounding context is
     untrustworthy. This gate is what keeps Cf and Cc codepoints out of every
     match. Every match is then checked again with `containsRenderingHazard`,
     which rejects any Cf or Cc codepoint (line feed, carriage return and tab
     included) and U+2028 / U+2029; no pattern can match the line feed, tab or
     CRLF the field gate admits, so this is defence in depth that becomes
     load-bearing only if a gate or pattern is relaxed. Both checks classify code
     points, not UTF-16 units, so supplementary-plane format characters (e.g. TAG
     characters U+E0001, U+E0020-U+E007F) are caught. URLs end at any Unicode
     White_Space character. Phone numbers use ASCII digits only, so the dialled
     digits are exactly the ones displayed, on any engine; the only whitespace
     admitted between digit groups is U+0020, U+00A0, U+2007, U+2009 or U+202F,
     so a match never crosses a tab or line break. A run (decimal digits plus
     those separators and `+-()`) holding any non-ASCII decimal digit yields no
     link rather than a truncated one; a run joins across a space, so a non-ASCII
     digit one space away drops the whole phone number. Run detection is one
     linear pass per field. An email match starts only where a run of
     local-part characters starts, and never right after an `@`, so a long run
     without a usable `@` is read once rather than once per position. The
     patterns spell out explicit character classes rather than `\s` / `\d`, so
     the JVM unit tests describe which strings match on Android; the timing
     tripwires run on the JVM engine only, not ICU.
   - **Rendering-stage isolation.** The sheet wraps every user-controlled string
     (the URL, the issuer's organization name, the source field's label) in
     U+2068 / U+2069 (FSI / PDI) bidi isolates. Within the isolate, the Unicode
     Bidirectional Algorithm cannot reorder glyphs across the boundary, so any
     residual directional context from chrome cannot reorder the displayed target
     and any directional content within cannot leak outward.

**How tested.**

- `PublicApiSurfaceTest` pins the three arms of `SecurityIntent`.
- `FieldLinkScannerTest` includes bidi-spoofing cases: U+202E,
  U+200B (zero-width space), U+200E (LTR mark), U+061C (Arabic Letter Mark),
  ASCII control bytes, the same hazards in phone and email positions, and a
  "clean URL adjacent to hostile URL" case that asserts the entire field
  surfaces no links, including when the hazard sits on another line. Multi-line
  cases (LF, CRLF, tab) pin that links in fields split into lines or columns
  stay tappable with exact ranges, that a phone number after a line or column
  ending in a digit is still found, that a URL ends before every Unicode
  White_Space character the field gate admits, that a lone carriage return,
  U+2028 or U+2029 leaves the field with no links, and that a URL containing a
  supplementary-plane TAG character is rejected. Engine-independence cases pin
  phone numbers spaced with U+00A0 / U+202F as one span, and Arabic-Indic,
  fullwidth or mixed ASCII and non-ASCII digit runs as unlinked. Timing
  tripwires catch quadratic regressions in phone and email matching and
  mixed-digit run detection. Plus `urlBytesAreVerbatimNoCfStripping`
  (a clean URL passes through byte-equal, no silent sanitization) and the
  categorization helper test covering Cf and Cc enumerable points.
- `TrustClaimSurfaceTest.securitySheetUrlIsBidiIsolated` and
  `securitySheetIsolatesOrganizationName` verify the sheet's rendered text is
  bracketed by FSI / PDI.
- No test in this repo asserts that no `ACTION_VIEW` intent is dispatched without
  the user crossing the sheet; the host builds the intent, so this is a host
  obligation.

## 3. Phone confirmation

**What the user sees.** Identical structure to the URL sheet: organization name,
source field label, verbatim phone digits, confirm/cancel pair.

**Trust claim.** A pass-embedded phone number cannot trigger `Intent.ACTION_DIAL`
without explicit confirmation. The displayed digits are the digits dialled.

**How tested.** Same shape as URL. No test in this repo asserts that no dial
intent fires without confirmation; the host builds the intent, so this is a host
obligation.

## 4. Email confirmation

**What the user sees.** Same structure: organization name, source field label,
verbatim email address, confirm/cancel pair. The composer launched on confirm
contains ONLY the address; the pass-supplied subject line and body are NOT
forwarded into the composer's pre-fill.

**Trust claim.** A pass cannot pre-fill the user's outbound email beyond the
recipient address. (Subject and body could be a phishing vector if pre-filled
under the user's display name.)

**How tested.** No test in this repo asserts that the resulting `Intent`'s extras
contain no `Intent.EXTRA_SUBJECT` or `Intent.EXTRA_TEXT`; the host builds the
intent, so this is a host obligation. `EmailIntent` carries only `emailAddress`
and `sourceField` (`SecurityIntent.kt:64-67`), so the kernel gives the host no
subject or body to forward.

## 5. Expired / voided overlay

**What the user sees.** A pass that has either passed its `expirationDate` or been
marked `voided: true` carries a dim scrim over its front and a pill reading
"Expired" or "Voided".

**Trust claim.** A pass whose validity window has closed cannot present as valid
*without the host disclosing that status elsewhere on the same screen*. `ExpiredOverlay`
has no `enabled` parameter and no theme token that hides it. `PassFront.showExpiredOverlay = false`
suppresses only the scrim composition step on `PassFront`, for a host whose own chrome
already presents the expired/voided state (e.g. walt-android's archival detail surface
with a `PAST PASS` eyebrow and an `Expired {date}` pill). The expiration date and voided
flag still flow through to the host, and `ExpiredOverlay` called directly stays
non-suppressible. A host that suppresses the overlay without an equivalent disclosure
breaks the contract; the bounded opt-in (one boolean on one composable, no overload
that lets a host *display* an arbitrary expiry state) keeps the breach legible in
code review.

**How tested.**

- `PublicApiSurfaceTest` covers `ExpiredOverlayState.from` end-to-end: voided over
  date, equal epoch as expired, future as none, no expiration as none.
- `TrustClaimSurfaceTest` covers the default overlay and each opt-out on `PassFront`.
- `ComposableSurfaceLockTest.passFrontHasExactlyNineUserVisibleParameters` pins the
  widest `PassFront` overload, which has no `ExpiredOverlayState` parameter. No test
  forbids a second `PassFront` overload; review enforces it.
- No screenshot test pins how the scrim and pill render; `TrustClaimSurfaceTest` asserts the pill text is displayed.

## 6. Bounded image rendering

**What the user sees.** Pass images (logo, icon, strip, background, thumbnail,
footer, plus their @2x and @3x variants) are decoded and displayed at the size the
layout calls for. An image larger than the caller's bounds is scaled down to fit. An
image the decoder refuses draws nothing. In a list that keeps a cache, an image
already decoded draws at once when its row comes back into view. A document's row in
the list shows its stored thumbnail the same way (`SCANNABLE_CARD_THREAT_MODEL.md`,
consumer list concession, condition 4); everything below holds for it as for a pass
image. The host passes any `ImageRole`, which the decode does not read, and a refusal
reaches `onImageDecodeRejected` as a pass image's does.

**Trust claim.** Four limits apply to every pass image, whatever configuration its
bytes were imported or stored under. No parameter loosens the first three.

- **PNG only.** `BoundedImage` reads the PNG signature and IHDR chunk from the bytes
  with `passes-core`'s `PngHeader`. Bytes that do not start with both are refused
  before the platform decoder is given them, so no JPEG, WebP, GIF, HEIF or other
  codec parses pass bytes, header included. Behind that check, the decode is refused
  again unless the platform decoder itself reports `image/png`.
- **Picture chunks only.** The platform decoder is not given the stored bytes. It is
  given a copy that `passes-core`'s `PngPicture` builds from them, holding only the
  chunks that carry the picture: IHDR, then sRGB, gAMA, cHRM, iCCP, PLTE and tRNS
  ahead of the image data, every IDAT, and an empty IEND. Each of those kinds is
  decided by its first chunk alone: it is kept when its length is within the range the
  PNG format sets for the kind, and a later chunk of the kind is never looked at. A
  colour profile (iCCP) is kept only when its chunk is at most 263,168 bytes and holds
  one whole stream, with nothing after it, that inflates to at most 262,144 bytes and
  opens with its own inflated length, as an ICC profile does. Everything else is left
  out: text (tEXt, zTXt, iTXt), EXIF, animation, a gain map, nine-patch and unknown
  chunks, any chunk but image data once the image data has begun, and every byte after
  IEND. On API 34 and 36, where it was measured, the platform PNG reader holds a text
  chunk for the whole decode, inflated when it is compressed, before or after the
  image data, and on API 36 it decodes a gain map carried in a PNG. Neither is done for
  a pass image. A PNG within its ceiling that ends inside a chunk is refused as
  `Malformed` before the platform decoder is given anything. The stored bytes are not changed.
- **Source ceiling.** A PNG declaring more than 4096 x 4096 pixels of area
  (16,777,216, the parser's default `maxImagePixelCount`) is refused. An interlaced PNG
  is refused above 1920 x 1920 pixels of area (3,686,400), because the platform codec
  holds an interlaced image whole at any output size. Both are decided from the bytes,
  and the first again from the size the platform decoder reports.
- **Output bound.** The bitmap handed to the UI never exceeds the caller's
  `ImageRenderBounds` (default 1920 x 1920). A larger source is decoded at its size
  divided by the smallest whole number that fits the bounds. On its tighter axis it
  comes back between half the bounds and the bounds, and its aspect ratio is kept up to
  rounding. A smaller source is never upscaled. A host that draws a small tile can pass
  a small box and decode a small bitmap; with a box of twice the drawn size, a source
  at least as large as the tile comes back at least as large as the tile.

A refused image is never decoded. One refused from its bytes never reaches the platform
decoder; one the platform's own reading refuses ends before its image data is read.

**Reuse and concurrency.** At most two pass image decodes run at once in the process.
A host may hoist a `PassImageCache` and pass it to every `BoundedImage` in a list. It
keeps what `BoundedImage` decoded or refused, keyed by the image bytes and the bounds,
and drops the least recently used entry once the decoded pixels and image bytes it
holds pass a budget the host sets (32 MiB by default). An entry over the budget is not
kept. Through its public API the host can construct the cache and clear it, nothing
else: it cannot put a bitmap in, take one out or change the limits above, which every
decode meets before its result is kept. A cached image is drawn without a decode. A cached refusal is not
decoded again but is reported to telemetry each time a row shows it, as a fresh one
is, so a host can draw its own fallback from the event and deduplicates if it wants a
count per image. Two rows that decode the same image at the same moment share the
first result kept. A decode that has started is kept when its row leaves composition,
unless the cache was cleared since the row asked for it; one still waiting for a
decode slot then never runs.

**What a host that keeps a cache must do.** Clear it after the composition that drops a
deleted pass's or document's row, for example from an effect keyed on the list's
items. A `PassImageCache` keeps a deleted item's image bytes and decoded pixels in
memory until then, and the kernel cannot see a deletion. A row that composes after the
clear but before that composition decodes and keeps the image again, so clearing as
soon as the list's data drops the item is too early. The host's test: once a deleted
item's row has left composition, the list's cache has been cleared.

**Residual risk.** The picture chunks of an accepted PNG are fed through the platform's
PNG codec in the app process, not in an isolated one. What the codec buffers is the
platform's, and was measured only for the cases named here:

- On API 34, a non-interlaced PNG decodes without a full-size intermediate (the device
  test below pins that).
- An interlaced PNG is held whole whatever the output size. At the lower ceiling that
  measured about 14 MB for an 8-bit image on API 35 and 39 MB for a 16-bit RGBA one on
  API 34, on top of the output bitmap. No test pins those figures.
- Two decodes at once hold two of those buffers. On an API 34 emulator, twelve
  interlaced 16-bit RGBA images at the lower ceiling, started together and decoded
  into a small tile, peaked at 75 MB two at a time, against about 400 MB with no
  limit. No test pins those figures. A decode that exhausts memory can take the host
  process down; an `OutOfMemoryError` from it propagates to the host.
- A `PassImageCache` holds image bytes, every chunk included, and decoded pixels in
  process memory, never on disk. After eviction or a clear they stay until nothing
  references them: a row still on screen may be drawing the bitmap, and it is not
  recycled. The kernel does not clear the cache on its own, on deletion or under
  memory pressure.
- A PNG whose chunks can be walked and whose image data is malformed still reaches the
  codec, which is where it is found to be malformed. No chunk's CRC is checked before
  the codec.
- A kept colour profile is parsed by the platform in the app process.
- Each decode holds up to two more copies of the image's bytes while it builds the
  picture. To measure a colour profile it inflates at most one chunk of at most
  263,168 bytes into an 8 KiB scratch buffer, stopping within 8 KiB past the cap.

**How tested.**

- `BoundedImageDecodeTest` pins, on the JVM: both ceilings' values, the first's parity
  with the parser default, the refusal of JPEG, WebP, GIF, HEIF, truncated and empty
  bytes from the bytes alone, that the decode returns a refusal only the bytes can
  give, that a PNG ending inside a chunk is `Malformed` from the bytes, and the second
  check's refusal of every reported type but `image/png`.
  `passes-core`'s `PngHeaderTest` pins the header read. `passes-image-decode`'s
  `BoundedBitmapDecodeTest` pins the division arithmetic.
- `passes-core`'s `PngPictureTest` pins, on the JVM: which chunks are kept and that the
  kept ones come back byte for byte, both ends of each kind's length range, that a kind
  is decided by its first chunk even when that one is left out, that nothing but image
  data is kept once it has begun, the end at the first IEND, the profile cap at
  262,144 bytes and one byte over, the profile chunk cap at 263,168 bytes and one byte
  over, a profile that cannot be read, has bytes after its stream or declares another
  length than it has, and the refusal of bytes that end inside a chunk or declare a
  chunk longer than the file.
- `BoundedImageDecodeInstrumentedTest` runs the platform decoder (CI on API 28, 31, 34
  and 36): an image over the bounds comes back inside them and reports nothing, an
  image inside them keeps its size, a 4096 x 4096 image decodes to 1365 x 1365, an
  image one column over the ceiling is refused at bounds below, at and above the
  default, real JPEG, WebP and GIF files are refused as not PNG, and an interlaced
  PNG decodes at its ceiling and is refused one column over it. Three tests cover the
  picture chunks. A PNG carrying 32 MB of inflated text decodes for under 4 MB; the
  platform's own decode of it must cost over 16 MB on API 34 and up, and the test
  skips on a lower level where it does not. A Display P3 PNG with metadata decodes to
  the pixels and colour space the platform gives the same file; the fixture must
  decode as Display P3 on API 34 and up. A PNG with a gain map decodes without one;
  the platform's own decode must read one on API 36 and up, and the test skips on API
  34 where it does not. PNGs encoded as the document importers encode them, at 600 x
  800, 600 x 450 and 2048 x 1536, decode inside tile bounds.
- `PassImageCacheTest` pins, under Robolectric: a cached image is not decoded again and
  the same bytes at other bounds are another entry; without a cache every load
  decodes; two loads of one image at once share one result; a decode that started is
  kept when its caller leaves, dropped when the cache is cleared during it, and never
  run when cancelled while waiting; at most two decodes run at once; pixels, image
  bytes and the per-entry overhead count against the budget; a cached image draws in
  the first composition; a refusal is reported on each entry, with a cache or without
  one, and by each of two rows showing the same cached refusal. `ByteBoundedLruTest` pins the
  eviction order, the budget arithmetic, the first value winning and a value from
  before a clear being dropped. Two tests in `BoundedImageDecodeInstrumentedTest` run
  on the platform decoder: a decoded bitmap counts at least a byte a pixel against the
  budget, whatever config the platform chose, and an image decoded once draws from the
  cache in the first frame it is shown again.
- `passes-image-decode`'s `BoundedBitmapDecodeInstrumentedTest` pins the shared
  mechanism: a box larger than the source never enlarges it, a box never rescues an
  image the header gate refused, decoding a 3839 x 3839 image into a 1920 x 1920
  box peaks below half the memory of the full-size bitmap, and refusing an interlaced
  image costs under 1 MB where decoding it costs its full size.
- `PublicApiSurfaceTest` pins `ImageRenderBounds.Default` to 1920 x 1920 and rejects
  non-positive dimensions at construction. It pins `PassImageCache` to a budget-only
  constructor and `clear()` with no companion methods, its 32 MiB default, and its
  refusal of a non-positive budget. `ComposableSurfaceLockTest` pins `BoundedImage`
  to seven parameters and one declared overload.
- Coil's default decoder is not used for pass images; only the
  `ImageDecoder.setOnHeaderDecodedListener` path is.

## Out of scope for `passes-ui`

The trust claim "encrypted at rest" is `passes-storage`'s problem; this module does
not re-state it. The trust claim "PKCS#7 signature verified against Apple's WWDR
root" is `passes-core`'s; this module displays the result.

This module's contribution to the audit trail is exactly the six surfaces above.
