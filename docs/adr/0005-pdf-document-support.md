# ADR 0005: PDF document support

- Status: Accepted. The document model generalizes to PDF, image and barcoded-image arms (sections I, C below).
- Decision context: PKPASS-only import leaves a user-visible gap (event, transit and airline tickets often exist only as PDFs or screenshots), and the Google Wallet pathway is structurally unreachable to a third-party wallet.

## Context

PDFs are an order of magnitude worse than PKPASS as a parser-side attack surface. The format permits embedded JavaScript, `Launch`/`SubmitForm`/`URI`/`GoToR` actions, AcroForm and XFA active content, embedded files, attached digital signatures, network fetches at render time, decoder-CVE-heavy subformats (JBIG2, JPEG2000, CCITT), object-graph attacks, encrypted documents and render bombs.

This ADR fixes the security posture, the renderer architecture and the deliberate non-features that stop a contributor re-introducing the surface this design strips out. Documents are a sibling concept to PKPASS passes. The image arm (I) and the composite arm (C) reuse every control below unchanged unless stated.

Module names: `passes-document-core` (pure JVM model, config, reject taxonomy, telemetry guard), `passes-pdf` (isolated renderer service, binder, importer), `passes-isolation` (memfd and isolated-worker mechanism), `passes-document-ui` (`DocumentView`, `FullScreenDocumentView`), `passes-document` (PDF-or-image importer), `passes-storage` (`documents` and `document_thumbnails` tables in the same SQLCipher database).

## Decisions

### D1. Sibling concept; documents are not Passes

`Document` is a sealed type alongside `Pass` and shares no superclass with it. A document has no `PassType`, no `SignatureStatus`, no structured fields and no expiration. It carries opaque bytes, a thumbnail, kind-specific dimensions (`pageCount` for a PDF), an original filename, an import timestamp and `Provenance.UserProvided` (single-arm, by design). Documents live in the same SQLCipher database as passes: same key, same backup exclusion, same irreversible delete.

Rationale: the trust contract differs structurally. Mixing PDFs into the `Pass` model would put a "Verified" pill API path within reach of code that should never offer it. Sibling separation moves that risk from "policed at every call site forever" to "physically impossible."

#### D1.C Color carries no trust meaning; documents take a class tint

Color is a per-class default the user can reassign, so it cannot encode provenance. A document cannot present as verified because:

1. No document surface, at list or detail scale, renders a signature affordance or verified indicator (D5: PDF signatures are not verified and no signed indicator is surfaced), so there is no verified-vs-unverified ladder for a color to imitate.
2. Provenance is stated by the host, in text: the class label in its list (D1.L) and the "Pass type" row on the detail surface (D5.T). Not by colour or chrome.
3. A user-assigned color on a PDF is a preference, not a violation.

`DocumentView(faceTint = ...)` lets the consumer tint the detail surface instead of reimplementing it. The tint reaches the frame only: the rasterised page and decoded image render identically tinted or not, and identically in light and dark, and the tint cannot change the render request. No `Document` arm carries a color field and none may be added; the color an item carries is consumer state. Choosing or varying a document's color by signature status, verification outcome, signing identity or a claimed issuer identity anywhere in the kernel is an amendment to this row, not a PR. The mirror row for scannable cards is "colour carries no trust meaning" in `SCANNABLE_CARD_THREAT_MODEL.md`.

#### D1.L Documents in the consumer's list

The kernel draws no document list item. A consumer renders documents in a list of its own making, interleaved with the other classes or in a section of their own, provided:

1. **The sibling data-model contract holds.** The merge is render-time only. A row is built from `DocumentRow` / `PdfDocument` (or the image and composite arms), never by widening it into a `Pass` or a shared supertype with one.
2. **The row carries no signature or verified affordance**, and none can be themed onto it. The list is where a document sits closest to a pkpass.
3. **Detail-surface provenance holds**: the host "Pass type" row on the detail surface, which is the only way into the full-screen view (D5.T). A consumer that drops the row is amending D5.T, not filing a PR.
4. **The row names its class and draws no page or image.** The class label is host-written (List-surface presentation), visible without interaction, and never truncated or displaced by the display label; the row's accessible description names the class. Dropping either part amends this ADR and the consumer list concession in `SCANNABLE_CARD_THREAT_MODEL.md`, which holds the full condition list for every class.

### D2. System renderer only: `android.graphics.pdf.PdfRenderer`

No third-party PDF library is bundled. PDFium ships in the MediaProvider Mainline module on Android 14+ (API 34) and updates via Play System Updates, decoupling PDFium CVE response from the app's release cadence (the API floor is G.1). The API is structurally narrow: open a `ParcelFileDescriptor`, render page N to a `Bitmap`. No JavaScript path, no `URI` / `Launch` / `SubmitForm` processing, no network, and the PDF cannot paint outside the bitmap provided.

Why the renderer is safe to feed hostile PDFs, from AOSP/PDFium source analysis (on-device confirmation across a device matrix is not recorded):

- The framework binding calls only `FPDF_LoadCustomDocument`, `FPDF_GetPageCount`, `FPDF_LoadPage`, `FPDF_GetPageWidth/Height`, `FPDF_RenderPageBitmap` and `FPDF_ClosePage`. The PDFium JavaScript engine is never initialized (no V8 isolate in the renderer process), so `/JavaScript` actions cannot fire.
- Action dispatch (`FPDFAction_*`) is never invoked, and the binding has no `Context`, so a `/Launch` or `/URI` action has no way to fire an Intent. External resources (`/RemoteGoTo`, `/F`, `/URL`) are never resolved on the render path, and ICC profiles are read from inline streams.
- An encrypted document makes `PdfRenderer` throw `SecurityException` (`FPDF_ERR_PASSWORD`); the binding has no UI surface, so a hostile PDF cannot cause the OS to show a password prompt (D6).
- The isolated service declares no `<uses-permission>`, runs in the `isolated_app` SELinux domain (denied TCP/UDP sockets by base policy) and needs only the transferred fd, a heap `Bitmap` and the world-readable `libpdfium.so`, so even a future implicit-egress PDFium change hits a kernel block.
- After a renderer crash the binder proxy invalidates and calls surface `RemoteException`; the client re-binds to restart the isolated process.

Evidence (source paths read, reproducible at cs.android.com; none confirmed on a device):

- `frameworks/base/core/jni/android_graphics_pdf_PdfRenderer.cpp` (and the Mainline copy under `packages/providers/MediaProvider/pdf/`): the C calls listed above are the whole native surface. The binding does not call `FPDF_InitLibraryWithConfig` with a non-null `FPDF_LIBRARY_CONFIG.m_pIsolate`, the entry point that attaches V8, so the JavaScript engine (`fpdfsdk/fxjs/`) is never initialized.
- `fpdfsdk/fpdf_view.cpp` (`FPDF_RenderPageBitmap`): the render call graph rasterizes content streams and does not reach action dispatch (`FPDFAction_*`, `FPDFLink_GetAction`) or external-resource resolution (`core/fpdfdoc/cpdf_filespec.cpp`, `core/fpdfapi/parser/cpdf_security_handler.cpp`).
- `system/sepolicy/private/isolated_app.te`: the `isolated_app` domain is denied `node:tcp_socket` and `node:udp_socket`; base policy denies a dyntransition into it, so `android:isolatedProcess="true"` is the only way in. OEM SELinux customizations could weaken this and are unchecked.
- `frameworks/base/core/java/android/os/Process.java`: isolated UIDs are 90000-99999 (`FIRST_ISOLATED_UID` to `LAST_ISOLATED_UID`).
- `libpdfium.so` is world-readable: under `/system/lib*`, or on API 34+ inside the `com.android.mediaprovider` apex (`com.google.android.mediaprovider` on GMS builds; the APK package name differs from the apex name).

The release gate "empirical device matrix before the renderer ships" was never met; no row of that matrix is recorded.

Rejected:

- PdfBox-Android: larger in-process code surface, parses content not needed (forms, annotations, signatures) which the no-extraction rule wants closed, and the app would own CVE response.
- MuPDF: AGPL/commercial licence conflicts with the open-source-for-trust positioning.

### D3. Renderer runs in an isolated process

A `Service` with `android:isolatedProcess="true"` hosts the renderer. The wallet process binds it, sends a `ParcelFileDescriptor` to the PDF bytes, receives a `Bitmap` over shared memory and unbinds. The service has no app-data access, no inherited network permission and OS-level crash containment.

This is the load-bearing control. A PDFium memory-safety bug in the main process would share an address space with the SQLCipher key, the Keystore session and decoded bytes of other passes. The same exploit in the isolated process compromises an empty sandbox that returns to the OS on crash; the wallet sees a `RemoteException`, surfaces "could not render" and keeps running.

#### F.1 FD transport is a `memfd_create`-backed `ParcelFileDescriptor`

Decision: the main process allocates a memfd, populates it with `write(2)` of the decrypted PDF bytes (no writable `mmap` is ever taken in the main process) and seals it before adopting the fd into a `ParcelFileDescriptor` for the renderer. The seal set is `F_SEAL_SEAL | F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK`:

- `F_SEAL_WRITE` makes the buffer read-only across future mappings, so a PDFium write-via-mmap primitive in the renderer cannot mutate bytes the main process still holds.
- `F_SEAL_GROW` / `F_SEAL_SHRINK` pin the size.
- `F_SEAL_SEAL` stops later code weakening the others.

`write(2)` rather than mmap-write-munmap because `F_ADD_SEALS` does not invalidate writable mappings that already exist, only new ones; with `write(2)` no writable mapping exists in either process. No plaintext PDF reaches the filesystem at any point. Consumers read the fd with `pread`/`read`: before Linux 6.7 a write-sealed memfd refuses even a read-only shared mapping.

Rejected transports: `MemoryFile` (fd only via hidden API, closed on API 28+); `SharedMemory.fileDescriptor` (ashmem fd does not satisfy PDFium's `lseek` + `pread64`); `File.createTempFile` (plaintext on disk, defeating SQLCipher's at-rest guarantee); `ParcelFileDescriptor.fromSocket` (not seekable). The transport choice does not widen the binder surface D4 locks down.

Implementation: `MemfdPfdFactory` in `passes-isolation` (`PfdFactory.fromBytes`), used by `DefaultPdfImporter` and by all three `DocumentImporter` branches (PDF, image, barcode). It creates the memfd with `MFD_CLOEXEC | MFD_ALLOW_SEALING`, writes, adds the seal set, and checks it with `F_GET_SEALS` (masked, since newer kernels may add `F_SEAL_EXEC`) before the dup. The seal values come from the Linux ABI through the public `Os.fcntlInt`, as `OsConstants` lacks them. A sealing failure folds into each arm's existing rejection; there is no unsealed or file-backed fallback.

API floor: `Os.memfd_create` and `Os.fcntlInt` need API 30, so `DocumentImporter` rejects below 30 before any memfd work (PDFs as `UnsupportedAndroidVersion`, images as `DecoderUnavailable`). `DefaultPdfImporter` keeps its own API 34 gate (G.1). The kernel's minSdk is 28, so on Android 9 and 10 no document imports.

### D4. No extraction

The PDF is opaque from import to deletion. The kernel does not extract text, metadata (`/Title`, `/Author`, XMP), form-field values, annotations, links or attached files. The only operation on a PDF is `PdfRenderer.openPage(N).render(bitmap, ...)`.

Every extraction codepath is an attack surface (`Launch`/`URI`/`SubmitForm` actions hide in annotations and bookmarks; XFA is arbitrary XML execution; embedded files are arbitrary payloads). Refusing them collapses the surface to what the rasterizer touches, which the system renderer is hardened against and the isolated process contains.

The renderer binder (`PdfRendererBinder`) exposes exactly two methods, `probe` and `render`. Adding an extraction method (`getText`, `getMetadata`, `getAnnotations`) is a security-policy change requiring an ADR amendment.

`passes-document-ui` exposes a Compose facade, `rememberPdfThumbnail(...) -> PdfThumbnailState` (with `PdfThumbnailCache`), so a consumer surface can show an asynchronously rendered page without touching binder primitives. It preserves D3 (it consumes a caller-supplied `PdfRendererBinder` and binds nothing itself) and D4 (its body only calls `render(...)`, and `PdfThumbnailState` exposes a single `ImageBitmap` plus an aspect float, with no metadata accessor). `PdfThumbnailSurfaceTest` reflection-locks the arm list, the per-arm fields and the absence of any `getText` / `getMetadata` / `getAnnotations` / `getAttachments` accessor. Adding a state arm or a non-trivial `PdfThumbnailCache` accessor is a security-policy change like adding a binder method.

### D5. PDF digital signatures are not verified

The kernel does not parse `/AcroForm/SigFlags`, validate PAdES/CMS signatures or surface a "signed" indicator. PDF signature semantics are inconsistent in the wild (whole vs partial, certification vs approval, MDP transforms) and there is no equivalent of Apple's WWDR root for tickets. A "verified" signal the kernel cannot honestly back would dilute the PKPASS signature trust signal. Accepting every PDF as `Provenance.UserProvided` is the honest position.

The kernel draws no provenance signal on any document surface, inline or full screen; provenance is the host's (D5.T). The kernel's part is structural: a sibling model and table (D1), no signature or verified affordance on any surface, isolated render and decode (D3) and no share-out (D8). A kernel-drawn provenance caption on a document surface amends D5.T.

#### D5.N The actor in kernel copy is "this app"

The kernel does not know what a host calls itself; a trust claim attributed to a name the user sees nowhere else in the app reads as someone else's claim. So every kernel sentence that could name the host says "this app": the PKPASS import-confirm trust caption (`PassImportConfirm`), the import rejection sheet (`PassImportRejectionSheet`), the unknown-scheme barcode confirmation (`barcode_create_confirm_unknown`) and the `B3EmphasisStyle.DomainHero` URL eyebrow ("LEAVING THIS APP").

"This app" relies on the host's window: a host renders kernel trust surfaces in its own opaque window, so the app in front is the app the sentence means. A translucent or dialog-themed window over another app would let it read as that app's claim.

Rejected: a host-supplied name (a host-controlled string inside sentences the kernel pins verbatim, and copy tests could no longer assert a whole sentence); the passive voice ("The source has not been verified."), which drops the actor.

Prose and identifiers in this repository that call the consumer Walt are not copy.

#### D5.T The provenance signal is the host's "Pass type" row

Neither `DocumentView` nor `FullScreenDocumentView` states provenance. The host carries it with a "Pass type" row naming the artifact class in the details section of the document's detail surface. That is weaker than a sentence in words in two ways:

- **Neutral type label.** A "Pass type: PDF" row names the artifact class rather than stating in words that the document is user-provided and unverified. The consumer accepts that to keep one provenance/type row across artifact classes.
- **Collapsed-by-default placement.** The row may sit in a foldout. A user who never expands it does not see it, on the detail surface or on the full-screen view entered from there.

Why bounded: documents carry `Provenance.UserProvided` and no "verified" signal (D5), and neither the kernel nor the host draws a signature or verified affordance on a document surface. The list names the class without interaction (D1.L condition 4), the row is a labelled, consistent location, and the kernel remains a display device (D4). The residual risk is a user who ignores the list's class label, never expands the row and opens the full-screen view: they meet no provenance signal while looking at a document that can look exactly like a real ticket, or depict a verified affordance, this app's own pkpass signature status included, with nothing beside it to contradict it. A document's pages or image and its display label are chosen by whoever produced the file, including one restored from a wallet file someone else exported. Accepted: the app vouches for nothing in a document, and what a gate or scanner does with it is the scanner's call (`PDF_THREAT_MODEL.md` threat 12).

Bound: a host that shows a document (a) renders the "Pass type" row on its detail surface, (b) enters that detail surface only from its list or straight after import, and `FullScreenDocumentView` only from that detail surface, and (c) keeps the list's class label (D1.L). A deep link, shortcut, widget or exported intent that opens either surface directly skips the list and amends this decision. What the host draws in `closeButton`, `fullScreenAffordance` or its own chrome is its choice, carries no signature or verified affordance, and may add to the row, not replace it. The kernel cannot verify any of this at runtime, so the host pins it with its own tests: that the details section renders the row, and that no direct entry into either surface exists.

### D6. Encrypted PDFs are rejected at import

Password-protected PDFs are detected at the encryption probe in the renderer service and refused. There is no password prompt: a prompt driven by PDF-encryption metadata is a UI vector controlled by an untrusted file, and a user habituated to typing passwords into the app is one prompt-spoof from a credential leak. Users decrypt elsewhere first; that cost is deliberate.

### D7. Hard caps: 25 MB, 10 pages, 4 MP per rendered bitmap, 5 s render timeout

Import-time validation (`PdfImportConfig` defaults) rejects:

| Reject reason | Threshold |
|---|---|
| `OversizedAtImport` | size > 25 MB |
| `NotAPdf` | first 8 bytes not `%PDF-X.Y` with `X` 1 or 2 and `Y` any ASCII digit |
| `Encrypted` | renderer reports the encrypted flag |
| `TooManyPages` | pageCount > 10 |
| `RendererFailed` | page-0 smoke render fails or exceeds the 5 s timeout |

The header check accepts any minor digit rather than a closed list, since the renderer is hardened for the whole 1.x/2.x lineage and a closed list would fail-stop on future minors for no security gain. Per-page rendered bitmaps are bounded at 4 MP (`PdfRendererService.MAX_PIXELS`), close to the 1920 x 1920 of `ImageRenderBounds.Default` for PKPASS images. The 10-page ceiling sits above legitimate ticket density (1-3 pages) and well below page-tree-bomb territory; on timeout the service kills its own process and the binder surfaces `RemoteException`. Storage re-checks `byteCount <= 25 MB` and `pageCount <= 10` on insert, so a caller bug cannot land an oversized blob. The 10-page ceiling will reject some legitimate documents (multi-leg itineraries, booklets with vouchers); raising it is an ADR amendment, not a code-only change.

### D8. No share-out

Documents have no share or export affordance. Bytes go one way: file picker in, no UI path back to a sharing intent. Every egress path is a potential exfiltration path if a future bug routes content through it, and the user can re-obtain the original from where they imported it. A bytecode scan (`DocumentPublicApiSurfaceTest` in `passes-document-ui`, `PublicApiSurfaceTest` in `passes-ui`) fails on any construction of `Intent.ACTION_SEND` / `ACTION_SEND_MULTIPLE` or an `application/pdf` share.

### G.1 PDF import is gated on `Build.VERSION.SDK_INT >= 34`

A Mainline-backed PDFium is reachable only on API 34+ via `android.graphics.pdf.PdfRenderer` (API 35 adds `PdfRendererPreV`, also Mainline-backed but with a higher call-site requirement and no extra reach). On API 30-33 `libpdfium.so` is the platform-bundled copy and updates only with full-OS patches. The single import entry point (`DefaultPdfImporter`) therefore rejects below API 34 with `DocumentRejectedKind.UnsupportedAndroidVersion` ("Android 14 or newer required") without invoking the renderer. Every PDF the app renders goes through a Mainline-updated PDFium on devices that receive MediaProvider Mainline updates; builds without Play System Updates update PDFium only with OS releases (residual risk). The repo-wide `minSdk` (28) is unchanged; the gate is a runtime check, not a manifest floor, and the PKPASS path is unaffected.

## Telemetry

`DocumentTelemetryGuard` and the document events on `StorageTelemetryGuard` accept only enums, counts and durations: no `String`, `CharSequence`, `ByteArray` or `Map`. This mirrors `TelemetryGuard`; adding a `String` parameter is a security-policy change. `DocumentTelemetryGuardSurfaceTest` enforces it by reflection. The composite import (C) reuses the image import event verbatim; a decoded barcode payload is never written to telemetry.

## Consequences

- PDFs are strongly contained but minimally featured: no search, no auto-expiration, no labels beyond filename, no sharing. A feature that wants any of those re-opens the threat model and amends this ADR.
- The isolated process adds roughly 200-400 ms on first render (cold start of the isolated service; Pixel 6 historical figure, not measured here); later pages in a session are cached in main memory.
- Devices below API 34 cannot import PDFs at all (G.1), and devices below API 30 cannot import images either (F.1 API floor).

## Zoom: full-screen surface only (Z)

Pinch-zoom and pan exist because the main reason to import a PDF is to scan an embedded barcode at a gate, and a fit-resolution page does not enlarge it enough. Inline `DocumentView` stays fixed at 1x (zoom inside a surface the host embeds in a vertical scroll needs a nested-gesture interlock at every embedding site, and a small slot still leaves the barcode too small).

### Z.2 The 4 MP per-bitmap cap (D7) stands

`PdfRendererService.MAX_PIXELS` is not raised for zoom. The cap is defense-in-depth against decompression-bomb memory abuse; raising it is its own security-policy change. Zoom-aware rendering (tiled or viewport re-render) stays within it: per-tile or per-bitmap at 4 MP or less.

### Z.3 The binder API surface does not widen

`PdfRendererBinder` keeps exactly `probe` and `render`. A zoom-aware path extends `render`'s parameter shape (sub-rect / viewport); it adds no third transaction, so D4's no-extraction guarantee holds.

### Z.5 No share-out path is introduced (D8 unchanged)

Zoom is a view-side affordance; no new path moves bytes off device or into a sharing intent.

### Z.7 Zoom lives in a full-screen detail surface only

Inline `DocumentView` has no zoom, pan or double-tap toggle: single-touch horizontal drag advances its pager, two-finger gestures are ignored, and pages draw at fit resolution into the host's slot. A "Tap for full screen" banner docked inside the inline view enters `FullScreenDocumentView`, which owns the gesture interlock:

- Two-finger pinch always drives zoom.
- Single-touch drag pans only when `scale > MIN_SCALE`; at fit it advances the in-surface pager (if more than one page) or does nothing.
- Translation is clamped so the page cannot be panned entirely off the slot.
- Zoom state is per page and resets on page change and on reopening the surface.
- Zoomed content is clipped to its slot, so a page never draws over host content beside it.

The banner is themed through `DocumentSemantics` / `DocumentTheme`; the consumer supplies the label and tokens, the kernel the structural slot. The full-screen surface takes the whole screen outside any host scroll container, so the host's integration is a navigation hop, not a gesture contract (Z.9).

### Z.9 Consumer integration

Because the inline surface consumes no single-touch drags at any zoom level, the host embeds `DocumentView` in a vertical scroll with no nested-scroll interlock. Launching the full-screen surface and back-navigation are the host's navigation concern.

## Documents generalize to PDF + image (I)

The same trust posture (user-supplied file, never signature-verified, provenance in the host's row, isolated process, no share-out) applies verbatim to PNG / JPEG / WebP, which users receive as screenshots and photos at least as often as PDFs. D1 through D8 extend to the image arm unchanged; only what differs is recorded.

### I1. `Document` is sealed: `PdfDocument | ImageDocument` (plus the composite arm, C1)

`PdfDocument` keeps `pageCount`; `ImageDocument` carries `widthPx` / `heightPx` instead (the kind-specific field lives on its arm, never on the supertype). `ImageDocument` shares no superclass with `Pass` and has no `SignatureStatus`. The dimensions are the bounded raster decoded inside the sandbox, never derived from an in-process decode of untrusted bytes, never upscaled beyond source. The model carries no container format; that is a persistence detail.

### I2. The image reject taxonomy is not merged into `DocumentRejectedKind`

PDFs fail as `Encrypted` or `TooManyPages`; images as `NotAnImage` or `DimensionsTooLarge` (a decompression bomb). One enum would force every consumer to branch on arms that cannot occur for its kind. The image taxonomy is `passes-image`'s `ImageDecodeRejectedKind` (`NotAnImage` / `OversizedAtImport` / `DimensionsTooLarge` / `DecodeFailed` / `DecoderUnavailable`), reused verbatim. `DocumentImportResult` (in `passes-document`) carries the PDF taxonomy on `PdfRejected`, the image taxonomy on `ImageRejected`, plus kind-agnostic arms (`Unrecognized`, `StorageHandoffFailed`).

### I3. `passes-document` owns the sniff-and-branch orchestration

`DocumentImporter` magic-byte-sniffs PDF vs image and branches to `passes-pdf`'s renderer service or `passes-image`'s decode sandbox. It sits above both backends, and a `passes-pdf` to `passes-image` edge is deliberately avoided; the only other module above both is `passes-document-ui` (display). It declares no service; all decode and render work stays in the isolated backends. The orchestration lives here so it is not reassembled in the host.

The importer reads the source once into a bounded buffer, sniffs, then materializes the bytes into a sealed memfd PFD (F.1) via `passes-isolation` for the winning backend, so a one-shot fd source is never read twice and the original bytes are what get persisted.

### I4. Storage: forward-only `documents` columns

The `documents` table has a `format` discriminator (`'pdf' / 'png' / 'jpeg' / 'webp'`, default `'pdf'` so existing rows migrate untouched) and nullable `width_px` / `height_px`. The `pdf_bytes` BLOB column holds the original bytes of either kind (renaming forces a table rewrite for no audit gain). `insertDocument` takes a sealed `DocumentInsert` (`Pdf(pageCount)`, `Image(format, widthPx, heightPx)`, `BarcodedImage`). The D7 size and label caps apply to both kinds; the page cap is PDF-only. Migrations are forward-only (ADR 0002).

### I5. The image arm reuses `DocumentView` and `FullScreenDocumentView`

`DocumentView` dispatches on the sealed `Document`: PDF is the swipeable pager; image is a single fixed-fit image decoded once in the sandbox. Neither arm draws a caption (D5.T). The dispatcher takes the kind-specific `imageFile` / `imageDecoder` pair alongside `pdfFile` / `renderer`; `passes-document-ui` depends on `passes-image` for this. `FullScreenDocumentView` serves all three arms; Z.7 applies to image and composite documents as to PDFs. D8 holds across all three arms.

## Composite artifact: PDF | image | barcoded-image (C)

A photographed or screenshotted membership card that carries a barcode wants both the picture (to recognise the card) and a crisp re-renderable native barcode (to scan). The image arm covers the barcode-less case and `ScannableCard` the barcode-only case; the composite is one artifact carrying an image and a barcode extracted from it. It is a strict superset of `ImageDocument`, reusing its isolation, storage and display substrate, and degrades to a plain image when no code is found.

### C1. `Document` gains a third arm: `BarcodedImageDocument`

It carries every `ImageDocument` field plus `barcodePayload: String` and `barcodeFormat: ScannableFormat`. It shares no superclass with `Pass` and has no `SignatureStatus` (a barcode extracted from a user image is not a verified credential). The arm exists only when a code was found and the consumer confirmed it; an image with no barcode stays an `ImageDocument`, never a composite with an empty payload. Adding an arm forces a deliberate, security-reviewed edit to `PublicApiSurfaceTest`'s arm-set lock, in the same audit register as adding a binder method. `passes-document-core` has a pure-JVM `api` edge to `passes-core` for `ScannableFormat`.

### C2. Barcode extraction runs in the isolated worker

The user-imported image is the hostile-input surface (the `CVE-2023-4863` libwebp / codec-RCE class D3 exists to contain). Decoding it and running a symbol reader over the pixels both happen in the permissionless sandbox, never the host process. The importer reuses `passes-barcode`'s isolated `BarcodeImageDecoder`: the bytes are decoded in-sandbox and only `{payload, ScannableFormat}` (`BarcodeDecodeResult`) crosses the binder, never a `Bitmap` or the source bytes. No `BitmapFactory` / `ImageDecoder` / ZXing call touches user-image bytes in the host process.

A composite import runs two isolated decodes of the same bytes, the image sandbox (display raster, thumbnail, dimensions) and the barcode sandbox (symbol), each on its own memfd PFD from the single bounded read. The source fd is still read once; holding compressed bytes in a host `ByteArray` is not a decode.

### C3. Confirm-before-persist; first code wins; graceful degradation

A misread barcode that only surfaces at a checkout terminal is a real failure mode, so `DocumentImporter.import` takes `confirmBarcode: (suspend (payload, format) -> Boolean)?`, invoked with the decoded value before anything is persisted so the consumer can gate on its `BarcodeCreateConfirmSheet`. `true` persists a composite; `false` degrades to a plain image.

- The hook is `null` by default, which makes the composite path opt-in: with no hook the isolated extraction does not run at all, and an incidental barcode in a photo the user only wanted to store is never silently turned into a composite. A consumer wanting composites without a confirm UX passes `{ _, _ -> true }`.
- No code found, extraction failure or declined confirmation all land on a plain `ImportedImage`; extraction failure never fails the import.
- With several codes, the first detected wins (ZXing's `MultiFormatReader` stops at the first match). Nothing beyond the barcode is extracted (D4: no EXIF / XMP / payload-derived label).
- Every non-composite outcome reaches the consumer as `DocumentPersist.Image.barcodeExtraction`: `NotAttempted`, `NoCodeFound`, `Failed(DecodeFailureReason)` (the decoder's own bucket, keeping the `DecodeTimedOut` / `DecoderUnavailable` split so a watchdog kill is not read as "no code found") or `Declined`. The outcome carries no payload and no image bytes: a BCBP payload holds passenger name and PNR, so `DocumentPersist.BarcodedImage` is the only place a payload crosses this seam, and only after the user confirmed. Per-reason copy is the consumer's half.

### C4. Storage: composite columns on the image row

Nullable `barcode_payload TEXT` / `barcode_format TEXT` columns on `documents`. A row is a composite iff both are non-null; `format` stays the image container format, so there is no new `format` value and no third reject enum. `barcode_format` stores the `ScannableFormat` name (matching `scannable_cards.format`); an unrecognised name (DB tampering only, this module is the sole writer) decodes to "no barcode" rather than throwing on a list query. The barcode lives on the same row as its image: one artifact, one wallet entry, never a host-side join. D7 size and label caps apply; the page cap does not.

### C5. The composite reuses `DocumentView` for its image half

`DocumentView` dispatches `BarcodedImageDocument` to the same `ImageDocumentView` over the same backend pair, so the dispatcher gains no parameter. The generated barcode and its format switcher are composed by the consumer from `passes-ui`'s `ScannableCardView` and `passes-core`'s `BarcodeEncoder.encode(payload, format)`, which re-encodes the stored payload across symbologies. This keeps the two UI towers (`passes-ui`, `passes-document-ui`) independent: the composite is the one place they combine, and that lives in the consumer, not in a kernel cross-tower edge.

## List-surface presentation

Document items in a consumer's own list (in whatever shape it draws them) take a class tint (D1.C), name the artifact class in a host-written class label ("PDF" / "IMAGE" / "IMAGE, QR CODE"), carry no verified affordance of any kind and no content preview (no page or image thumbnail). The label's position is the consumer's layout choice, provided it is visible without interaction and never truncated or displaced by the display label. Past the class label, the provenance signal is the host's "Pass type" row on the detail surface (D5.T). The composite list-face code posture is a C1/C2 concession recorded as "list-face code render" in `SCANNABLE_CARD_THREAT_MODEL.md`. Issuer color and imagery reach only a pkpass item's pass tile, never a document item, and no document item draws the pass mark (ADR 0003 D4 addendum; consumer list concession, conditions 3 and 4).

## Out of scope

- PDF metadata-driven labels, OCR or barcode extraction from PDFs: any of these re-opens the threat model.
- PDF digital signature display: the format's signature semantics do not justify the chrome.
- Sharing documents out of the app (D8).
- Inter-document linking, search or tagging; documents sort by import date.

## Tests

| Decision | Pinned by |
|---|---|
| D1, I1, C1 | `PublicApiSurfaceTest` in `passes-document-core` (arm set; no shared superclass with `Pass`) |
| D1.C | `DocumentFaceTintTest` (tint cannot change the render request; transparent tint falls back); `DocumentSurfaceLockTest.documentViewHasExactlyTenUserVisibleParameters` (`faceTint` is the one colour parameter) |
| D1.L | Consumer-side: the kernel cannot verify a host's list at runtime |
| D2 | No test (no classpath scan for `pdfbox-android` / `mupdf`) |
| D3 | `@Ignore`d stub, not run: malformed PDF crashes the renderer, the main process survives, rebind succeeds (`PdfRendererServiceInstrumentedTest`) |
| D4 | reflection: binder has exactly `probe` and `render`; `PdfThumbnailSurfaceTest` |
| D5, D5.N, D5.T | `DocumentSurfaceLockTest` (parameter shape of both surfaces), `TrustClaimSurfaceTest`, `BarcodeCreateConfirmSheetTest`, `CopyNamesNoHostTest` (in `passes-ui` and `passes-document-ui`: no string in main sources names Walt); consumer-side, a test that the details section renders a "Pass type" row and one that no direct entry into the detail or full-screen surface exists |
| D6 | `encryptedPdfRejectsAtProbe` is an `@Ignore`d stub, not run; `PdfImporterTest` checks the rejection mapping against a fake binder only |
| D7 | unit tests at each cap boundary; watchdog test covers timeout-then-kill |
| D8, Z.5 | bytecode scan for `Intent.ACTION_SEND` and `application/pdf` |
| F.1 | `MemfdPfdFactoryInstrumentedTest` (CI, API 31/34/36): seal set present; write, writable shared mmap, resize and re-seal fail with `EPERM`; a sealed fd renders through the platform `PdfRenderer` (API 34+). `DocumentImporterTest`: the API 30 floor and sealing failures fold into rejections |
| G.1 | unit: import below API 34 returns the rejection without invoking the renderer |
| Z.3 | reflection: binder still exactly two methods |
| Z.7 | `DocumentSurfaceLockTest` (full-screen shape lock); `ZoomableImageClipTest` (zoomed content stays in its slot); Compose tests: banner navigates, a pinch on the image arm does not re-decode; the inline-view-ignores-pinch test is `@Ignore`d, not run |
| I2, I3 | `DocumentImporterTest` (backend outcome to arm mapping; PDF magic routes to PDF backend, image to image backend, unrecognised touches neither; original bytes reach `persist`) |
| I4, C4 | `SchemaMigrationTest`, `DocumentRepositoryTest` |
| I5, C5 | `DocumentSurfaceLockTest` (dispatcher parameter lock; no new parameter for the composite); `DocumentArmCompositionTest` (image and composite arms compose on both surfaces) |
| C2, C3 | `DocumentImporterTest` (`imageWithBarcodeRoutesToCompositeAndPersistsPayloadAndFormat`, `confirmHookSeesTheDecodedPayloadBeforePersist`, `pdfWithEmbeddedBytesNeverRunsBarcodeExtraction`, degrade-to-image cases) |
