# passes-ui trust-claim-bearing UI flows

The trust claim that this repository carries is "every security-and-privacy-critical
behavior Walt makes about pass handling lives in code you can read." For `passes-ui`,
that decomposes into six surfaces. Each lists what the user sees, the trust claim, and
how tests pin it.

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
fires regardless of the boolean, and the status is still derived from `passes-core`.
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
- No test in this repo asserts that the scrim and pill render.

## 6. Bounded image rendering

**What the user sees.** Pass images (logo, icon, strip, background, thumbnail,
footer, plus their @2x and @3x variants) are decoded and displayed at the size the
layout calls for. A maliciously oversized image is silently replaced with a
placeholder.

**Trust claim.** A hostile pass archive cannot OOM-crash the host process via a
multi-gigabyte bitmap allocation. The decode pipeline applies `ImageRenderBounds`
at decode-header time, so the decoder never allocates a backing bitmap larger than
the configured ceiling. The host's process stays alive even when handed a 50000 x
50000 PNG.

**How tested.**

- `PublicApiSurfaceTest` pins `ImageRenderBounds.Default` to 1920 x 1920 / 4 MP and
  rejects non-positive dimensions at construction.
- `TrustClaimSurfaceTest` drives the bounded decoder with tight bounds and asserts an
  `ExceedsWidth`, `ExceedsHeight` or `ExceedsArea` rejection. That Robolectric test
  runs against a stub decoder; the real no-full-allocation check is
  `passes-image-decode`'s `BoundedBitmapDecodeInstrumentedTest.rejectsOverCapImageByGateWithoutFullAllocation`
  (CI on API 28, 31, 34 and 36).
- Coil's default decoder is not used for pass images; only the
  `ImageDecoder.setOnHeaderDecodedListener` path is.

## Out of scope for `passes-ui`

The trust claim "encrypted at rest" is `passes-storage`'s problem; this module does
not re-state it. The trust claim "PKCS#7 signature verified against Apple's WWDR
root" is `passes-core`'s; this module displays the result.

This module's contribution to the audit trail is exactly the six surfaces above.
