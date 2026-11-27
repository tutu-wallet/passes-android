# ScannableCard generator: threat model

For the ScannableCard feature, the repository's trust claim ("every
security-and-privacy-critical behavior Walt makes about pass handling lives in code
you can read") decomposes into a per-threat enumeration plus six load-bearing
controls. One of them, C2, and the list concessions under it are held by the consumer:
the kernel documents them and cannot enforce them. Each threat lists what it is, the mitigating control, and for accepted
risks the rationale for accepting the residual exposure. This document records why
each piece of the feature has to exist.

The structural posture is **new artifact class, not new subtype of `Pass`**. PKPASS
handling guarantees that every `Pass` carries a signature status computed against the
issuer's chain. A ScannableCard has no issuer and no signature; its bytes were typed by
the user. Inheriting `Pass`'s shape would mean inheriting its trust language and would
degrade the verified-PKPASS signal. ScannableCard is therefore a sibling at
every kernel layer (data model, storage table, UI surface).

## Vocabulary

STRIDE labels (Spoofing, Tampering, Repudiation, Information disclosure, Denial of
service, Elevation of privilege) classify threats where they help. The PDF threat
model (`docs/PDF_THREAT_MODEL.md`) has the same shape; this surface skews toward
spoofing and elevation (user-typed payload that another device may auto-act on) rather
than parser corruption.

## Six load-bearing controls

All mitigations reduce to six controls, referenced by label. Five are structural in the
kernel; C2 is the consumer's.

**C1. Distinct artifact class end-to-end.** `ScannableCard` is not a `Pass`, does not
implement a `Pass` interface, shares no sealed parent with `Pass`, and is not
co-iterated with `Pass` in any kernel API. The data model (`passes-core`), storage
table (`passes-storage`, a `scannable_cards` table, not a column on `passes`) and UI
surface (`passes-ui`, `ScannableCardView` and `ScannableCardScreen`, apart from the pass
composables) are sibling structures. The kernel ships no list item or lane composable
for any class: a list is the consumer's and stands only under the consumer list
concession below. Proposing a unifying `DisplayableArtifact` interface or a kernel list
surface amends this document.

**C2. Provenance in the host's words.** The kernel draws no provenance signal on a
scannable card. Its detail surface, `ScannableCardScreen`, shows the code, the label
and the payload and nothing that reads as verification: no badge, no signature status,
no issuer. The signal is the consumer's: a "Pass type" row naming the artifact class on
the item's detail surface (the "Pass type" row section below) and the class label on
its list (consumer list concession). The kernel cannot verify either at runtime, so the
consumer pins both with its own tests.

The C1 / C2 concessions below are recorded in the order they bear on the list and
detail surfaces. A *list item* is one entry in a consumer's list, in whatever shape the
consumer draws it. A *class label* is the host-written name of an item's artifact class
("PDF", "QR CODE"), distinct from the item's user- or file-supplied label (`label`,
`displayLabel`). A *pass tile* is the one bounded, clipped region of a pkpass list item
that may show an image and a colour from the pass file. The *pass mark* is the
host-drawn mark of condition 3 below.

### C1 / C2 concession: the consumer's list

The kernel draws no list. A consumer presents the artifact classes in a list of its own
making, built from its own item composables, whether it interleaves the classes or
keeps each in its own section. A kernel list surface amends this document. The
consumer's list is permitted strictly when all five hold:

1. No list item of any class carries a signature or verified affordance. The trust
   ladder (signature badge, trust captions) lives exclusively on detail surfaces, so
   there is no host-drawn verified visual for a user-created or document item to
   imitate.
2. Every user-created and document item (scannable cards, documents, composites)
   carries its class label. The label is visible on the item without interaction, is
   never truncated, displaced or covered by user, file or issuer text, and the item's
   accessible description names the class. Its position is otherwise the consumer's
   layout choice.
3. Every pkpass item carries the pass mark, and no item of another class draws it
   anywhere, as a mark, a class glyph or otherwise.
   - Its form is the host's: one glyph or word, the same on every pkpass item whatever
     the pass file or the user supplies.
   - It depicts the class, never approval: no tick, shield, seal or other sign of
     endorsement, and nothing that varies with signature status (condition 1).
   - It is visible without interaction, at 3:1 contrast or better against whatever
     surface it sits on, in every theme.
   - It is drawn in host colours on the host's surface, separated from any pass tile
     by host surface on every side, at a position that holds only host-drawn content
     on every item of every class. No user, file or issuer text displaces or covers
     it.
   - It may be silent to assistive technology. The item's accessible description names
     its `PassType` in host words the host uses for no other class.
4. No list item draws artifact imagery (issuer artwork, document page, image or code),
   with one exception: a pkpass item's pass tile may show one image from the pass file,
   on the pass file's `backgroundColor` or on the item's class or user colour. Those
   pixels and the file's colour are the issuer's and can imitate any mark the host
   draws, so the exception is bound:
   - The image, and the colour when it is the file's, are taken from the pass file
     alone and are the same for every signature status. Showing or withholding them by
     verification outcome would make them a verified affordance (condition 1).
   - The pass tile has a host-fixed size and position, the same on every pkpass item.
     No pixel or colour from the pass file, or derived from it, reaches outside it.
   - The pass mark, the item's text (issuer text included) and its controls sit on the
     host's surface in host colours. Anything else the host draws on a pass tile is
     decoration: no class, identity or state, expiry included, is carried there alone.
   - The host renders no barcode from the pass in the list, at any size, on the pass
     tile or off it.
   - The image decodes in the app process through the kernel's `BoundedImage`, at
     `ImageRenderBounds.Default` or tighter, which caps the dimensions of the bitmap it
     returns and refuses an image over the kernel's source ceiling. That is the path
     the kernel provides for pass images, not the isolated process that images a user
     imports decode in (C5; ADR 0005 D3 and section I).

   Every other pixel source stays out of the list: a document page or image, a
   user-chosen image on any item, and a code outside the list-face code render
   concession below. Supplied text is covered by the residual risk below.
5. Detail-surface provenance holds. A user-created or document item opens a detail
   surface carrying the host's "Pass type" row (below; ADR 0005 D5.T for documents). A
   pkpass item opens the pkpass detail surface, which states its signature status in
   words, not behind a foldout, tap or other
   disclosure.

The *C1 list-level distinction* is conditions 1 to 4, and the rest of this document
cites it in that sense. It carries almost all of the load: neither the list nor a
scannable card's detail surface shows a provenance caption, only the host's type row,
which may sit collapsed, and bound (b) of the "Pass type" row requires the list-level
distinction in return.

**Why the pass mark is required.** A pass tile gives the issuer a region of the list
it paints freely, beside text it already writes. Without the mark, a pkpass item is
told apart only by what it lacks, and issuer content can supply that: a class word in
its text, a class glyph or a document page in its pass tile. The mark is the one part
of a pkpass item nothing in the pass file can draw, move or remove, and it holds in
both directions:

- A pass posing as a code or a document still carries the mark, whatever its text
  prints and its pass tile depicts.
- A user-created or document item cannot carry it. The host draws it on pkpass items
  only, the item draws no imagery beyond a code under the list-face code render
  concession, and supplied text never reaches the mark's position. An image document
  that is a photograph of a pass shows its class label and no mark (Threat 1).

What supplied content cannot reach is the mark's position, not its shape. A title can
carry a glyph or emoji that resembles the mark, and a pass tile can depict a copy of
it; neither sits at the mark's position, and a copy inside a pass tile sits on an item
that carries the real one.

**Accepted residual risk.** The class label and the pass mark each name a class;
neither proves what an item's content claims. A pkpass item shows issuer text and, in
its pass tile, issuer pixels on an issuer-chosen colour. Between them they can print a
class word, host-looking metadata or verification wording, and depict a seal, a tick, a
code, or another organisation's logo and colour. A document's file name or a user's
label can print the same words in a title. None of it is the host's voice, and it is
bounded:

- Posing as a user-created or document class gains little past the list. The item
  keeps the pass mark, by condition 5 it opens the pkpass detail surface, and the host
  renders no code for it in the list.
- Posing as verified, or as a known issuer, through issuer text or pass tile artwork is
  bounded by conditions 1 and 5. The host draws no verified mark at list scale for
  artwork to copy, a signed pass and an unsigned one render the same item, and the
  pkpass detail surface states signature status in host words that issuer text and
  artwork cannot suppress, restyle or fold away. The list does not tell an unsigned
  pass in a known brand's logo and colour from the signed original; only the detail
  surface does.
- An issuer colour equal to the list's surface hides the pass tile's edge, so its
  artwork can read as drawn on the host's surface. What bounds it is the pass tile's
  fixed size and position: the artwork cannot leave that frame, and host surface
  separates the pass mark and the item's text from it.
- Artwork can depict a scannable code. A user can then present an unsigned pass's code
  straight from the list without meeting the in-words signature status, which is the
  reach the list-face code render concession guards for user-created codes. Accepted:
  the host cannot tell a depicted code from any other picture, the picture is the
  issuer's on the list as on the detail surface, and what a scanner does with it is
  the scanner's call (Threats 2 and 9).
- Posing as verified through a file name or user label is bounded only by the class
  label, which names a class that is never verified. The provenance row on the detail
  surface may sit collapsed, so a user who never expands it meets a
  user-created or document item's provenance only as its class label, whatever its
  title or content claims.
- The spoken path reserves no position, so it is weaker than the visual one. An
  accessible description reads supplied text beside the host's class words: a user's
  label or a document's file name can speak the host's words for a `PassType`, and
  issuer text those for a class. What holds is that the host's own words are always
  among those spoken, the class for a user-created or document item (condition 2) and
  the `PassType` for a pkpass item (condition 3). Two items of different classes can
  still be made to speak alike.

**Decode exposure.** A list that draws pass tiles decodes one image per pkpass item
whenever those items are displayed, where a detail surface decodes only the pass the
user opened. The decode is in-process and bounded per image (condition 4), so the list
adds reach to an exposure pass images already carry and no new decode path.

Dropping or weakening any of the five conditions amends this document. The kernel
cannot verify them at runtime, so the obligation is the consumer's. Its wallet-list
tests are expected to pin that:

- one pass file, carrying an image and a background colour, renders the same item at
  every signature status;
- every user-created and document item shows its class label and speaks its class,
  and supplied text of any length or glyph height neither moves nor covers the label;
- every pkpass item shows the pass mark with host surface between it and any pass
  tile and speaks its `PassType`, and supplied text of any length or glyph height
  neither moves nor covers the mark;
- no item of another class draws the mark, and the mark's position holds only
  host-drawn content on every class;
- no item draws artifact imagery outside a pass tile or a list-face code render, no
  pass colour paints outside a pass tile, pass images decode only through
  `BoundedImage`, and no pass barcode renders.

### C1 / C2 concession: list-face code render

A scannable card's (and a composite artifact's extracted) actual code may render on a
list item through the kernel's `CompactCodeView` (`(payload, format)` only). This goes
past condition 4 of the consumer list concession: a code usable straight from the list
means the user may never open the detail surface. It is permitted strictly when all
three hold:

1. The kernel render stays mechanism-only. No label, eyebrow, caption or signature
   affordance can be composed inside `CompactCodeView`; pinned by
   `ComposableSurfaceLockTest.compactCodeViewHasExactlyFourUserVisibleParameters`
   (`payload`, `format`, `modifier`, `contentDescription`) and its no-overload lock.
2. The item that shows the code meets every other condition of the consumer list
   concession, with its class label ("QR CODE" / "CODE 128" / "IMAGE, QR CODE") in view
   whenever the code is. Only user-created codes render at list scale; the host renders
   no pkpass barcode in the list, whatever the pass's signature status.
3. Detail-surface provenance holds: the host's "Pass type" row below.

**Accepted residual risk.** A user who scans straight from the list never meets the
"Pass type" row for that use. Bounded because the artifact is the user's own
creation, the list-level class distinction is in view at scan time, and Walt is a
display device, not an issuer: the POS / recipient is the authority on whether the code
is credit-worthy (Threat 9). Rendering a list-face code without the condition-2 class
label, or pkpass barcodes at list scale, amends this row. Issuer artwork that depicts a
code is not a render; condition 4 of the consumer list concession governs it.

### C1 / C2 concession: colour carries no trust meaning, for any class

The colour of an item's face or pass tile has one of two sources, and neither depends
on signature status:

- **Class colour.** A consumer may colour any item by artifact class and let the user
  reassign it.
- **Issuer colour.** A pkpass item's pass tile may instead take the pass file's
  `backgroundColor` (condition 4 of the consumer list concession), as `PassFront`
  takes `Pass.colors` on the kernel pass surface (ADR 0003 D4). It is content the pass
  file declares, read from the file alone and the same for a signed pass and an
  unsigned one. On a list item it stays inside the pass tile, so a pass file cannot
  recolour the pass mark into its background or restyle host text (the attack ADR 0003
  D4 names).

**The verified-band confusion class is closed on these premises.** It does not rest on
"a user-created artifact cannot wear the chrome a signed pass wears," since no item
colour is exclusive to a signed pass.

1. **No item colour presents as verified.** No list item of any class carries a
   signature affordance (condition 1 of the consumer list concession). On a detail
   surface the only status-keyed colour is the `PassFront` pill's, which renders on a
   pkpass alone and comes from the status (ADR 0003 D2), never from an item colour. It
   stays on the pill.
2. **Signature status is stated in words, on the detail surface only.** "Verified -
   signed by issuer" / "Unverified - unsigned by issuer" / "User provided pass, issuer
   cannot be verified". Text, not chrome, so a colour choice cannot imitate it.
3. **No item colour attests a provenance.** A class colour is a default the user can
   override, so a recoloured PDF is a preference, not a spoof. An issuer colour may
   suggest an issuer, but any pass file can declare it, so an unsigned pass can wear
   the colour a signed one uses. Nothing may be inferred from either.

**Kernel surface.** `ScannableCardScreen(faceTint = ...)` and `DocumentView(faceTint = ...)`
let the consumer tint the card face. Both are presentation-only and bound by four rules:

- The tint reaches the card face only. The panel behind a code stays `SCAN_CODE_PANEL`
  (literally white in both themes) and the rasterised page / decoded image render
  identically tinted or not, so real content stays theme-independent and scannable (C5
  and Threat 14 posture untouched).
- Neither parameter can suppress content: the code and the payload readback on a card,
  the page or image on a document.
- Ink on a tinted face is derived from the tint's luminance (`inkOn`), pinned at
  >= 4.5:1, so an opaque tint cannot make the label or the payload readback illegible.
  A translucent tint composites over host paint the kernel cannot see, and the ink is
  derived from its nominal value: passing an opaque colour is the consumer's job.
- The kernel stores no colour for a scannable card or a document: neither model nor
  storage table carries a colour field. Which colour such an item carries is consumer
  state, so the kernel never learns why a colour was chosen and has nothing to leak in
  Threats 7 / 8.

Both surfaces decide what counts as a tint with one predicate, `passes-ui-core`'s
`faceIsTinted` (`isSpecified && alpha > 0f`), so a fully transparent tint falls back to
the documented untinted default on either arm rather than painting nothing. It is pinned
by `FaceTintTest`; the scannable arm also pins that its face and ink resolve through it
(`fullyTransparentTintFallsBackToTheDefaultFace`, via the injectable `facePaint`). The
document arm's frame is a `Modifier.background` whose branch no unit-level assertion
observes (its test checks only that the surface composes intact). The predicate decides
legibility, not provenance.

**Bound of this row.** Granted: a consumer-chosen face tint on the two detail surfaces,
the consumer's freedom to colour its own list items by class or user choice, and the
pass file's `backgroundColor` on that pass's pass tile. Not granted (each amends this
document): an item colour, face tint or pass tile colour chosen or varied by signature
status, verification outcome, signing identity or an identity the pass file claims, in
the kernel or on a consumer's list; a colour from a pass file, or derived from one,
outside `PassFront` and that pass's pass tile; a colour field on the scannable card or
document models or their storage tables; a `faceTint` reaching the code panel or page
render. The document-side mirror is ADR 0005 D1.C.

### C2: the host "Pass type" row (detail surface)

The provenance signal for a scannable card is a single "Pass type" row inside the
consumer's own details section (values *Image / Scanned / Pkpass / PDF / "Image,
Scanned"*). The kernel's `ScannableCardScreen` renders no trust caption; the host
carries the claim with its own type label.

This is weaker than a caption that states provenance in words, in two ways:

1. **Neutral type label.** "Pass type: Scanned" names the artifact class rather than
   stating "you made this and this app did not verify it".
2. **Collapsible, not-always-visible placement.** The row may sit in a
   collapsed-by-default foldout; a user who never expands it does not see the provenance
   signal on the detail surface.

**Why this is bounded.** The load-bearing mitigation for Threat 1 is C1 + C2 combined,
and C1 holds in the direction it must: every user-created and document
item names its class in the host's voice and cannot shed it, none can carry the pass
mark (condition 3 of the consumer list concession), and no list item carries a verified
affordance (condition 1), so passing for a pkpass gains nothing at list scale. The
detail surface is entered from the list, or straight after the user creates or imports
the item (bound (c)), on a card they created and can delete. The row, even collapsed, is a labelled, consistent
location for provenance. Walt remains a display device (Threat 9). The residual risk is
that a user who relies solely on the detail surface, never expands the foldout and
ignores the list-level distinction meets no provenance signal; judged acceptable for a
user viewing their own card.

**Bound of this row.** A host that shows a card's code at scan size, through
`ScannableCardScreen` or `ScannableCardView` outside a list item, (a) renders a "Pass
type" row naming the artifact class on the item's detail surface, and reaches any
other showing of the code (a sheet over that surface, say) only through it, (b)
preserves the C1 list-level distinction, and (c) enters the detail surface only from
its list or straight after the user creates or imports the item. A deep link, shortcut,
widget or exported intent that opens a detail or scan surface directly skips the list
and amends this document. The kernel cannot verify any of this at runtime, so the
obligation is the consumer's, pinned consumer-side by a test that the details section
renders a "Pass type" row and by one that no such direct entry exists. Dropping the
row or the C1 list-level distinction amends this document.

**C3. Input hygiene at the create boundary.** `passes-core` validates
`ScannableCardCreateInput` for per-format length caps (Code128 ~80 chars, EAN-13 / UPC-A
fixed-length with checksum, QR per-version cap with a conservative ~2000-char ceiling,
PDF417 ~800, Aztec ~1500), per-format charset rules (EAN-13 / UPC-A numeric only, Code39
limited alphanumeric, Code128 the printable ASCII subset; QR, PDF417 and Aztec are
byte-capable and admit any character), and Unicode Cf (Format) / Cc (Control) codepoint
rejection in both `payload` and `label`. The Cf/Cc rejection runs before the per-format
rules, so it covers the byte-capable formats too. It is stricter than `FieldLinkScanner`
(which keeps Cf/Cc out of every link it surfaces but admits LF, tab and CRLF in the
surrounding field text). A document's display label has no such rejection
(`docs/PDF_THREAT_MODEL.md`, threat 13).

The PDF417 and Aztec caps hold at the pinned error-correction levels (PDF417 level 3,
Aztec 33%). For single-byte payloads PDF417 holds 1,766 characters against the 800 the
validator allows, and Aztec 3,000 against 1,500. Re-derive both if either pin rises.

**The caps do not guarantee encodability.** They count characters while writer capacity
is consumed in bytes, so a 700-character CJK payload clears the validator and cannot be
rendered. No exact predicate exists at the validator (each writer picks a compaction mode
per run, so capacity swings with composition), and a byte ceiling tight enough to be safe
would reject ordinary accented text inside the character cap. Per-width measurements live
on the caps in `ScannableFormatConstraints`. What is in place is attribution, not
prevention: the encoder lifts both writers' over-capacity errors to
`EncoderFailureReason.PayloadTooDense` (typed and actionable), and the repository
trial-encodes before persisting, so an oversized multibyte payload is refused at
create/update time instead of saving and rendering as an accessible-but-empty
placeholder.

**Charset pins.** The writers default to ISO-8859-1 while the validator admits any
visible character on a byte-capable format, so an unpinned payload was unrenderable
(`PDF417Writer` threw) or silently transliterated (Aztec, QR). The pins are
`PDF417_AUTO_ECI` (unconditional; it adds a header only past ISO-8859-1,
`ZxingBarcodeEncoder.kt:91`) and `CHARACTER_SET`, applied for Aztec and QR
conditionally by `ScannableFormatConstraints.needsUtf8Eci`: Aztec takes a UTF-8 ECI
header only past ISO-8859-1 (the spec default); QR takes it past ASCII, because QR
decoders guess the charset of unannounced bytes (ZXing reads Latin-1 `ééé` back as
Shift_JIS). An unconditional pin was tried and fails: scanners without ECI
support (supermarket tills among them) reject symbols that carry the header. ASCII QR
payloads keep one more byte of v40-M byte-mode capacity (2,331 against 2,330 for
non-ASCII). Stored Aztec cards that fit ISO-8859-1, and byte-mode ASCII QR cards,
re-render as different symbols with the same text.

**C4. Create-time URI-scheme preview for the byte-capable symbologies.** When the user
creates a ScannableCard in a format that can carry an actionable payload, `passes-core`'s
URI-scheme classifier inspects the payload against a conservative allowlist (`http`,
`https`, `tel`, `sms`, `mailto`, `geo`, `wifi`, `bitcoin`, `ethereum`, `magnet`,
`market`, `intent`). A match, or a "looks URI-shaped but unrecognized scheme" fallback,
raises the confirmation dialog before the row is persisted; the user must explicitly
confirm "yes, this code is meant to encode an actionable URI." Pattern source:
`B3UrlConfirmSheet` in `passes-ui` (`SecuritySheets.kt`).

The gate covers PDF417 and Aztec as well as QR: `QrPayloadKind.requiresCreateConfirmation(format)`
consults `ScannableFormat.canCarryActionablePayload()`, an exhaustive `when` in
`passes-core`. A future roster member is a compile error at one kernel site rather than
a gate that quietly stops covering a format, and the kernel owns the predicate for which
symbologies need confirming. The consumer carries no parallel predicate: both create-time
call sites let `requiresCreateConfirmation(format)` decide alone. The sheet's copy says
"this code", not "this QR", so a dialog about an Aztec boarding pass does not describe
something other than what the user is saving.

**C5. Untrusted-byte barcode decoding is confined.** The kernel uses `com.google.zxing:core`
(Apache 2.0, pure JVM) to encode a bit matrix from a user-typed payload and format, and
to decode bytes the user did not type (composite image + extracted barcode artifact, and
live scan). Barcode decoding of untrusted bytes is confined to a sandbox or to a still-codec-free
path:

- **Static image bytes decode in a permission-stripped isolated process.** Gallery picks,
  file-picker images and system-camera snaps all go to `BarcodeImageDecoder`'s
  `isolatedProcess` service, where the still-image codec (libwebp / Skia / Quram, the
  historical RCE class) runs with no Keystore, no network, no storage. Only
  `BarcodeDecodeResult` (`{payload, format}`) crosses the binder back; raw bytes never
  decode in the app process.
- **Live-camera frames decode in-process but carry no still-image codec.** `decodeYPlane`
  (`passes-barcode-core`) reads a first-party `YUV_420_888` luminance plane from CameraX
  `ImageAnalysis`: already-decoded sensor pixels, never a file format, so the RCE class
  that justifies isolating the static path is structurally absent. Decode is pure-JVM
  ZXing. This is the one place untrusted bytes reach a barcode decoder in-process.

The decoder core (`MultiFormatReader`) is in the dependency closure, so Threat 6's
upgrade cadence covers decoder advisories on these two paths. The decoded payload is
never a usable code until the consumer's confirmation surface (C4 gate / image-keep
confirm) clears it, so a misread cannot silently become a scannable artifact (Threat 14).
Encoder bugs remain a concern (Threat 6), but encoder input is the user's own keystrokes
after C3 validation.

**C6. No secret material in the artifact.** The `ScannableCard` data model carries `id`,
`payload`, `format`, `label` and `createdAt` and nothing else. There is no `secret`,
`hmacKey`, `totpSeed`, `counter` or other field that would let the artifact rotate,
derive or sign anything. 2FA / OATH / TOTP support, if ever added, is a separate artifact
class with its own threat model. Enforced by what is not in the schema, not by runtime
validation.

## Per-threat enumeration

### 1. Visual conflation of unverified ScannableCard with verified PKPASS - Spoofing

**What.** The most consequential failure is the trust-UX failure on the verified pass the
card sits next to. If the user cannot tell at a glance which list item is a cryptographically
verified PKPASS and which is "a barcode I typed in last week," Walt's signature-status
ladder (`AppleVerified` / `SelfSigned` / `CertChainIncomplete` / `NoSignature`) loses
meaning. A user who reads all items as "trusted because they appear in Walt" is one
phishing-PKPASS away from a credential leak, even though that PKPASS would be correctly
tagged `NoSignature`.

**Mitigation.** C1 (distinct class end-to-end) and C2 (provenance in the host's words).
The kernel draws no list and no provenance caption. At list scale the distinction is
the consumer list concession, where pass tiles admit issuer artwork and colour; on the
detail surface it is the host's "Pass type" row, which may sit collapsed. The load
sits almost entirely on C1's list-level distinction, which is accepted deliberately.

**Status.** Mitigated structurally in the data model, and by the consumer on its
surfaces. The C1 list-level distinction carries the load: a class
label on every user-created and document item, the pass mark on every pkpass item, no
verified affordance on any item of any class, and artifact imagery nowhere but a pass
tile or a list-face code render. Colour distinguishes nothing on its own and is not
relied on to, and the class label and the pass mark name a class without proving what
an item claims (residual risk of the consumer list concession). This is the
load-bearing concern of the feature; every control traces back to this row.

### 2. Hostile URI payload encoded into a QR that another device auto-acts on - Spoofing / Elevation of privilege

**What.** The user creates a "QR card" intending a library card number but pastes
(deliberately, by accident or under social-engineering pressure) a URI-shaped string:
`https://attacker/`, `wifi:S:foo;T:WPA;P:bar;;`, `bitcoin:1abc?amount=...`,
`intent://attacker#Intent;...`. Another person's device (a friend's phone, a kiosk) scans
the QR and may auto-open the URL, auto-join the Wi-Fi, auto-launch the intent or
auto-prompt a payment. Walt becomes a delivery channel for URIs the recipient device
trusts because the QR is in someone's wallet.

**Mitigation.** C4: the create-time classifier raises a confirmation dialog naming the
scheme and the rendered payload (wrapped in FSI/PDI bidi isolates). The user must accept
"this code is meant to encode an actionable URI" before the row is persisted.
Unrecognized-but-URI-shaped strings (`unknown-scheme://x`) take the fallback warning path
rather than silent acceptance.

**Status.** Mitigated, with the residual that a user who deliberately clicks through the
preview is choosing to ship the URI. The mitigation is informed consent, not refusal.

### 3. Bidi / control-character spoofing in display label - Spoofing

**What.** `ScannableCardScreen` renders the user-supplied `label` on its card face, and
a consumer renders it beside its own chrome. A `label` containing U+202E
(Right-to-Left Override) or other Cf/Cc codepoints could rearrange visible glyphs to
spoof Walt UI text, e.g. a label that visually reads "AppleVerified".

**Mitigation.** C3: `passes-core` validation rejects any `label` containing a Cf or Cc
codepoint, returning `InvalidLabel(BidiOrControlChar)` from `ScannableCardCreateResult`.
The UI additionally wraps the validated label in FSI (U+2068) / PDI (U+2069) isolates as
defense-in-depth, mirroring `B3UrlConfirmSheet`.

**Status.** Mitigated, with defense-in-depth at the UI layer.

### 4. Bidi / control-character spoofing in payload preview - Spoofing

**What.** The create-time URI preview (C4) renders the raw `payload`, and the detail
surface (`ScannableCardScreen`) renders it as a human-readable readback below the code
panel (fallback for when a point-of-sale scanner cannot read the code). The same
bidi/control-character attack applies to both.

**Mitigation.** C3: `passes-core` rejects payloads containing Cf/Cc codepoints before any
preview or readback is rendered, so neither the dialog nor the detail-surface readback
receives a payload that could spoof surrounding chrome. The "looks URI-shaped but
unrecognized scheme" path goes through the same validator. The UI additionally wraps the
readback in FSI / PDI isolates, as in Threat 3.

**Status.** Mitigated.

### 5. Payload-size denial of service - Denial of service

**What.** QR supports up to 7089 numeric characters per code (version 40) and the matrix
grows quadratically. A maximum-capacity payload (typed or pasted from a hostile source)
causes a slow encode and an oversized on-screen matrix. The encoder is fast (<50 ms
typical) but degrades visibly at the upper end.

**Mitigation.** C3: per-format payload caps in `passes-core`, returned as
`InvalidPayload(TooLong)` before the encoder runs: Code128 80 chars, EAN-13 / UPC-A fixed
by format, Code39 80, QR ~2000, PDF417 800, Aztec 1500. Every ceiling sits well below its
format maximum but well above any realistic library / loyalty / URL payload (for the 2D
formats the sizing case is a boarding pass, whose IATA BCBP string runs ~60 characters per
leg).

**Status.** Mitigated by hard caps, enforced in `passes-core` (first line) and
`passes-storage` (second line, a row-level constraint so a future encoder-bypass call
cannot land an oversized blob).

### 6. ZXing CVE exposure - Tampering / Denial of service

**What.** ZXing has public CVEs across its history, mostly decoder-side and some
encoder-side. The kernel depends on its encoder (`MultiFormatWriter` / `QRCodeWriter` /
`Code128Writer`) and on its decoder on the composite and live-scan paths, so a CVE in
either becomes a Walt exposure window. The reachable decoder set covers seven
symbologies, including `AztecReader` and `PDF417Reader` with their own detector and
error-correction code.

**Mitigation.** The dependency is pinned to a specific Maven version in
`gradle/libs.versions.toml`. Upgrade policy: monitor ZXing's GitHub security advisories
monthly and upgrade within 30 days of a published advisory that affects encoder code or
decoder code reachable from `decodeYPlane` / `BarcodeImageDecoder`. The blast radius of a
static-path decoder bug is contained to the isolated process (C5), which lowers severity
but does not remove the upgrade obligation.

**Status.** Mitigated by confinement (C5) and a stated cadence.

### 7. Cross-artifact exfiltration via storage compromise - Information disclosure

**What.** A kernel bug granting out-of-process read access to the SQLCipher database
would also expose ScannableCard rows. The threat is inherited from the PKPASS / PDF
storage posture, not new to this feature.

**Mitigation.** ScannableCard rows live in the same SQLCipher database as PKPASS and PDF
rows, with the same Keystore-sourced key, the same Auto Backup exclusion and the same
irreversible-delete contract (see ADR 0002). The new table inherits these by living in the
same database.

**Status.** Mitigated by inheritance from the existing storage posture.

### 8. Auto Backup leakage of payload to Google - Information disclosure

**What.** Android Auto Backup, if not excluded, uploads the SQLCipher database file to the
user's Google account by default. ScannableCard payloads (library numbers, loyalty IDs,
occasionally something more sensitive) would land in a third-party cloud the user did not
opt into for this data.

**Mitigation.** The `passes-storage` Auto Backup exclusion (XML `<exclude/>` rule covering
the encrypted database file) already applies; the `scannable_cards` table sits inside the
same DB file. A regression test must keep the exclusion covering the extended schema; this
is a storage acceptance criterion, not a new control.

**Status.** Mitigated by inheritance, with a regression-test acceptance criterion on the
storage layer.

### 9. Cashier / POS accepts a forged loyalty number - Out of scope / Accepted-by-architecture

**What.** A user could type any merchant's loyalty number into a ScannableCard and present
it at checkout; the POS may credit points to an account the user does not own.

**Mitigation.** None applicable from Walt. **Walt is a display device, not an issuer or an
authentication authority.** The POS is the authoritative trust boundary for "is this
loyalty account credit-worthy"; a POS that accepts an account number without an
additional auth signal (PIN, app login, ID check) has a POS threat model problem. A
"server-side validation" hook calling merchant APIs would add attack surface (key custody,
request-replay, merchant-side phishing-of-Walt) without addressing the underlying problem:
such POS designs accept unauthenticated references whether displayed from Walt, Google
Wallet, the merchant's app or a printed plastic card.

**Status.** Out of mission. Documented so contributors do not propose it without amending
this row.

### 10. Color picker as injection vector - Out of scope (consumer-side)

**What.** Hostile inputs to a color picker have historically been a vector in browser CSS /
SVG parsers (named-color injection, `var(--...)` escapes). A naive "type a hex code"
picker could allow inputs that round-trip as something unexpected.

**Mitigation.** The kernel has no colour field on a scannable card or a document and
parses no colour a user enters. A consumer-side picker exists: the consumer persists
per-item overrides itself and passes `ScannableCardScreen(faceTint)` /
`DocumentView(faceTint)` an already-constructed Compose `Color`. For those two classes
the kernel parses no colour string, stores no colour and validates none; a `Color` is an
opaque packed value with no parse step, and nothing the kernel does with it (paint the
face, derive ink from luminance) branches on its value beyond contrast. Picker input
hygiene is the consumer's; a hostile value can at worst produce an ugly card, since ink
contrast is derived and pinned. The colours a pass file declares are a separate input:
`passes-core` parses them with the rest of `pass.json`.

**Status.** No parsing or storage surface for user-chosen colour at the kernel; picker
and persistence are consumer-side.

### 11. Future TOTP / HMAC-OATH secret leakage - Explicit non-feature

**What.** Barcode wallets attract "let me also store my 2FA codes" requests. A TOTP /
HMAC-OATH shared secret is a long-lived credential whose compromise is materially worse
than a loyalty-number leak; storing it beside plaintext loyalty payloads under the same
data class would let a single bug expose both.

**Mitigation.** C6: the `ScannableCard` data model has no field that can carry a secret.
2FA support, if it ever ships, is a separate artifact class with its own storage table, UI
surface and threat model (key rotation, screenshot blocking, biometric gating).

**Status.** Out of scope by structural refusal.

### 12. Encoder output cache poisoning - n/a

**What.** A renderer that caches encoded bit matrices keyed on `payload` could return a
stale or wrong matrix for a given input.

**Mitigation.** The encoder is deterministic and fast (<50 ms typical), so the kernel runs
it synchronously per render without a cache. Any future caching must key on
`(payload, format, version, errorCorrection)` and is a separate review.

**Status.** n/a today; a future cache needs its own review.

### 13. Walt-android consumer-side attack surface - Out of scope (called out)

**What.** The consumer's form (text entry, format dropdown, name field, color picker) is a
new attack surface: clipboard auto-paste of secrets, an accidental enter-key submit before
the URI preview renders, focus-stealing during the confirmation dialog, and the standard
consumer-side UI hygiene set.

**Mitigation.** Filed against the consumer's own issue tracker, not this document. The
consumer's obligations (where the URI dialog must intercept, what the field must reject
pre-submit, how clipboard-paste interacts with the preview) are handed off outside this
repository. This document records that the boundary exists.

**Status.** Out of scope for this document.

### 14. Composite image-decode: hostile image bytes / misread payload - Remote code execution / Tampering

**What.** The composite (image + extracted barcode) artifact introduces two ways untrusted
data enters the system that the type-it-yourself ScannableCard lacks:

1. **Hostile image bytes.** A user imports an image (gallery, file picker or system-camera
   snap) that the kernel must decode to find a barcode. A crafted image targeting a
   still-image codec bug (libwebp / Skia / Quram have a CVE history of heap overflows
   reachable from one malformed file) could achieve code execution at decode time.
2. **Misread payload presented as authoritative.** The decoder could misread a barcode
   (damaged scan, ambiguous symbology) and, if stored silently as a usable code, the user
   would later present a wrong loyalty / ticket number at a POS believing it correct.

**Mitigation.** Decode-surface confinement plus mandatory confirmation:

- **Static image bytes decode only in the permission-stripped isolated process**
  (`BarcodeImageDecoder`), so a codec RCE is contained to a sandbox with no Keystore /
  network / storage (C5). This covers all three still sources; the system-camera snap is
  first-party bytes written to an app-private cache file but takes the same isolated path,
  so a malicious gallery/file substitution gets identical treatment.
- **Live auto-detect** decodes in-process but only first-party sensor YUV (no still-image
  codec; C5) and produces a code-only artifact; no image is retained on that path.
- **The decoded payload is never a usable code until confirmed.** Actionable payloads (URI
  schemes) raise the C4 confirmation; the composite also passes a consumer-side image-keep
  confirm before persist. Decode is not the trust boundary; user confirmation is. A misread
  the user does not recognise is the residual risk, bounded as Threat 9 bounds a typo:
  Walt is a display device, the POS is the authority. The consumer's
  `confirmBarcode(payload, format)` gate is format-agnostic: it fires for every decoded
  symbology and preserves the decoded format verbatim.
- **Transient camera stills are swept** to at most one in the consumer's cache; the
  persisted image lives only in SQLCipher (Threats 7, 8 inherited).
- **The symbology allowlist bounds both sub-threats.** `DECODE_HINTS` pins
  `POSSIBLE_FORMATS` to exactly the `ScannableFormat` roster, so a hostile image can only
  reach the parsers for symbologies Walt renders and the reader has fewer candidate
  interpretations to confuse. Both risks scale with roster size, not ZXing's full format
  list.
- **The decode-scale ladder is bounded at both ends.** `decodeLuminance` tries several
  scales of one image (ZXing can refuse an image at the resolution the picker delivers and
  read it once area-averaged down). Every rung caps the longest side, including the
  largest, so decode cost stops scaling with a hostile image's area: the 50 MP canvas the
  header caps admit decodes at roughly 5 MP. A wall-clock budget (`DecodeLadder.budget`, a
  fraction of `decodeTimeoutMs`, checked against it at construction) drops remaining rungs
  on a slow device, so the failure mode under load is "no barcode found" rather than a
  killed process (`DecodeWatchdog` kills the sandbox on the same budget). The live path
  keeps one attempt per frame; its retry is the next frame.

**Roster growth is a deliberate re-weighting.** PDF417 and Aztec widen the reachable
parser surface by two symbologies and give the reader two more candidate interpretations
of an ambiguous region. Accepted because without them an imported boarding-pass screenshot
(the most-reported import) decodes to nothing at any scale. DataMatrix stays out, having
no reported need to pay for.

The image-codec RCE class is the kernel's to contain (isolated process); the manual-snap
rule (system camera, never CameraX `ImageCapture`) and the confirmation surfaces are the
consumer's, recorded consumer-side.

**Status.** Mitigated by isolation (static bytes), a no-codec path (live frames) and
mandatory confirmation.

## Inventory: PKPASS controls and ScannableCard equivalents

| PKPASS control                                  | ScannableCard equivalent                                                                |
|-------------------------------------------------|-----------------------------------------------------------------------------------------|
| `ParserConfig.maxArchiveBytes` (10 MB)          | Per-format payload caps in `passes-core` (Code128 80, QR ~2000, etc.); enforced again at storage |
| Manifest hash + PKCS#7 signature                | Not applicable; provenance is "user-typed", structurally a sibling class                |
| `SignatureStatus` four-band badge               | Host "Pass type" row on the detail surface (C2); class label at list scale (consumer list concession) |
| `FieldLinkScanner` Cf/Cc rejection              | Stricter (LF and tab rejected too); applied to `payload` and `label` at the create boundary (C3) |
| `B3UrlConfirmSheet`                             | Create-time URI-scheme preview on the byte-capable formats (C4); same dialog pattern    |
| `ExpiredOverlayState`                           | Not applicable (no expiration metadata; user can delete)                                |
| `TelemetryGuard` PII discipline                 | `ScannableCardTelemetryGuard` mirrors it (counts / formats only, no payload bytes)      |
| Encrypted-at-rest (SQLCipher) + Auto Backup off | Same database, same XML rules apply automatically                                       |
| Irreversible delete with cache wipe             | Same `ON DELETE CASCADE` and unwind contract; no encoder cache to wipe                  |
| Apple WWDR root chain                           | n/a: no issuer chain exists for user-typed input                                        |
| (n/a)                                           | New: distinct artifact class end-to-end (C1)                                            |
| (n/a)                                           | New: confined ZXing decode (C5)                                                         |
| (n/a)                                           | New: no-secrets schema (C6)                                                             |

## Explicit non-features

Proposing any of these amends this document, not just a PR. They are not "deferred to
v2"; each is deliberately absent because it re-opens a threat row above.

- **No unifying `DisplayableArtifact` interface and no kernel list surface.** Re-opens
  row 1. A consumer's list stands under the consumer list concession.
- **No OCR payload entry.** Camera and image entry exist only as barcode decodes,
  through the confined paths in C5 and the confirmation gate in Threat 14; text
  recognition re-opens a parser surface this document does not cover.
- **No "Verified" or "Trusted" badge on any ScannableCard surface** under any combination
  of consumer theming. C2 forbids it.
- **No colour-derived trust signal anywhere in the kernel.** No item colour, face tint or
  pass tile colour may be picked or varied by signature status, verification outcome,
  signing identity or an identity the pass file claims; the `PassFront` pill is the one
  status-keyed colour. Neither the scannable card nor the document model or storage table
  may carry a colour field. Colour reaches the scannable card and document surfaces only
  as a consumer-supplied `faceTint`, a pass file's colours reach only `PassFront` and
  that pass's pass tile, and nothing may be inferred from either. Re-opens the colour row.
- **No server-side validation of payloads against merchant APIs.** Re-opens row 9 and adds
  a key-custody / network surface.
- **No TOTP / HMAC-OATH / rotating-secret support.** Re-opens row 11.
- **No unconfined barcode decoding of untrusted bytes.** Image bytes reach the ZXing
  decoder only inside the isolated `BarcodeImageDecoder` service; the only in-process
  barcode decode is `decodeYPlane` on raw camera Y-planes. That path is codec-free only
  while the consumer feeds `ImageAnalysis` YUV, never `ImageCapture` output (ADR 0008
  constraint 1). Re-opens rows 6 and 14.
- **No payload-bytes telemetry.** `ScannableCardTelemetryGuard` may surface format counts
  and create/delete event counts; payload contents and payload length distributions are PII
  and never leave the device.
- **No kernel-drawn provenance caption on a ScannableCard surface.** Provenance is the
  host's, on the terms of the "Pass type" row section; a kernel caption amends C2.

## How each control is tested

| Control | Pinned by |
|---------|-----------|
| C1      | `ScannableCardSurfaceTest.scannableCardDoesNotShareSupertypeWithPass`; storage `PublicApiSurfaceTest` `SCANNABLE_CARDS` assertion (separate table). Consumer list concession: expected consumer-side tests, listed at the end of that concession |
| C2      | Consumer-side: a test that the details section renders a "Pass type" row, a test that no deep link, shortcut, widget or exported intent opens a detail or scan surface directly, and the list tests of the consumer list concession. Kernel: `scannableCardScreenHasExactlyFourUserVisibleParameters` (the surface's parameter shape). Colour row: `ScannableCardTrustSurfaceTest.faceTintDoesNotSuppressLabelOrPayload` (and its dark-tint twin), `codePanelIsLiterallyWhiteNotAThemeTokenOrTheFaceTint`, `inkOnClearsWcagAaAgainstEveryTintIncludingTheWorstCase`, `DocumentFaceTintTest.faceTintLeavesThePageRenderRequestUnchanged`; the shared tint gate by `passes-ui-core`'s `FaceTintTest` |
| C3      | Length-cap, charset and Cf/Cc rejection unit tests; `theTwoDimensionalFormatsEncodeAtTheirValidatorCap`, `theTwoDimensionalFormatsRoundTripAtTheirValidatorCap`, `BarcodeEncoderRoundTripTest.nonAsciiPayloadsSurviveOnTheCharsetPinnedFormats`, `BarcodeEncoderCharsetTest`; `noFormatIsDecodeOnly` and `isCreatableAgreesWithWhatTheValidatorAccepts`; byte-capable charset rules in `ScannableFormatConstraintsTest` |
| C4      | `QrPayloadClassifierTest`, `BarcodeCreateConfirmSheetTest` |
| C5      | Encoder integration test; decode confinement by the isolated-decode tests (`BarcodeDecodeServiceInstrumentedTest`, `YPlaneFrameDecodeTest`) and, consumer-side, an instrumented composite-import test (no host-process decode of source bytes) and a guard test that no CameraX `ImageCapture` appears in the consumer's `src/main` |
| C6      | No test in this repo (no schema snapshot); closest is `ScannableCardSurfaceTest.scannableCardConstructorIsExercisedWithEveryShape` |

## Out of scope for this document

- The consumer-side form, picker and intent surface.
- The wallet's payment / HCE surface, unchanged by this feature.
- The PKPASS / PDF artifact classes, documented in `SECURITY.md` and
  `docs/PDF_THREAT_MODEL.md`.
- Encoder performance tuning, which is not a security control.
