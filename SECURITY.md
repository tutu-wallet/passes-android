# Security policy

## Reporting a vulnerability

If you think you have found a security issue in walt-passes-android, please email **cole@walt.is** instead of opening a public GitHub issue.

Helpful things to include:

- A description of the issue and its impact.
- Steps to reproduce, ideally with a minimal `.pkpass` or code sample.
- Affected version (commit hash or release tag).
- Whether you would like to be credited if a fix ships.

## Scope

walt-passes-android is the open-source pass-handling kernel that ships inside the [Walt](https://walt.is) Android wallet. The most useful reports target one of:

- The PKPASS parser (`passes-core`): ZIP, JSON, image, signature, manifest verification, `.strings` parsing, locale handling, and the `.pkpasses` bundle reader.
- The document import surface (`passes-document-core`, `passes-pdf`, `passes-image`, `passes-document`): see `docs/PDF_THREAT_MODEL.md`.
- The ScannableCard generator (user-typed barcode / QR cards): see `docs/SCANNABLE_CARD_THREAT_MODEL.md`.
- Pass data storage (`passes-storage`): encryption-at-rest, key management, deletion, backup exclusion.
- Security-relevant UI flows (`passes-ui`): URL confirmation sheet, HTML subset renderer, bounded image rendering.

Out of scope:

- Issues in the closed-source Walt Android app outside `core/data-passes` and `feature/passes`.
- Issues that require a rooted, unlocked-bootloader, or otherwise compromised device.
- Issues in third-party PKPASS files (issuer-side mistakes).

## Security-relevant design decisions

Intentional choices a reviewer should know up front. If the code does something different, that is a bug worth reporting.

- **Signature policy is lenient with provenance display.** PKCS#7 signatures and `manifest.json` hashes are verified mathematically; tampered passes are rejected. The Apple WWDR certificate chain is validated for *display*, not for gating: self-signed and non-Apple-issuer passes are allowed to import, and the UI surfaces the verification result. The load-bearing security control for malicious passes is the parser hardening below, not the signature check - a valid signature does not imply the pass content is safe to render or store. Lives in `passes-core`.
- **Parser is hardened against malicious input.** ZIP extraction is in-memory with bounded archive size, entry count, and per-entry size; entry names are canonicalized and zip-slip is rejected; the archive's allowed file types are limited to `.json`, `.png`, `.strings`, plus the root-level `signature` file. JSON parsing is strict (no polymorphic open types, no reflection, recursion-depth bound). A pass image is kept only when it starts with a PNG signature and header, and its declared pixel count is capped from that header; an entry under an image name that is anything else is left out of the parsed pass. `passes-core` decodes no image. Lives in `passes-core`.
- **Pass images decode as PNG only, in the app process.** `BoundedImage` reads the PNG signature and header from the bytes before the platform decoder is given them, so no other image codec parses pass bytes, whatever the bytes were stored under. The platform decoder is then given only the chunks that carry the picture (header, palette, transparency, colour space, image data): text, metadata, gain map and unknown chunks never reach it. A PNG declaring more than 4096 x 4096 pixels of area is refused, an interlaced one above 1920 x 1920, and a refused image is never decoded. The decode runs in the app process, not in an isolated one, at most two at a time. A host may keep decode results in a `PassImageCache`, which holds image bytes and pixels in process memory only. A document's list tile takes the same path: it draws the PNG thumbnail the kernel encoded at import (at most 600 x 800, or up to 2048 px a side for an image or composite stored by an earlier kernel) and never the document's original bytes, which decode only in an isolated process. `passes-ui/TRUST_CLAIMS.md` section 6 states the limits and the residual risk. Lives in `passes-ui`.
- **Bundles of archives are capped at the outer layer.** A `.pkpasses` file is a ZIP of ZIPs, and the per-archive limits above bound only one archive. `PassBundleReader` applies `BundleConfig` caps (entry count, per-entry and cumulative decompressed bytes, outer compressed bytes) before any inner `.pkpass` is opened, then delivers each inner archive as bytes for the caller to run through `PassParser`, so the single-archive hardening and signature check run unchanged on every inner pass. Entry names never reach a result or telemetry. See ADR 0001 D5 amendment and D7.
- **`nfc` fields in a pass are parsed-and-ignored.** Importing a `.pkpass` cannot cause Walt to register a payment AID. The field is read for structural validity only; no code path exists to act on it. Deliberate, to avoid HCE conflict with Walt's payment feature.
- **`webServiceURL` is parsed-and-ignored.** Passes never trigger network calls from this library. No OTA pass updates, no push provisioning, no telemetry to issuers. `passes-core` and `passes-storage` make no network calls at all. Since schema v8 the original archive, including `webServiceURL` and `authenticationToken`, is retained at rest for byte-exact export, under the same encryption and backup exclusion as the parsed row; only `PassRepository.loadArchiveBytes` reads it (ADR 0002 D9).
- **Back-field URLs go through a visible confirmation sheet.** Tapping a link or actionable value in a pass's back fields shows the full destination (URL, phone number, email) before any external app is opened. URL scheme allowlist: `http`, `https`, `mailto`, `tel`. External `http`/`https` URLs open in Custom Tabs. Lives in `passes-ui`.
- **No WebView, anywhere.** HTML in back fields is rendered through an explicit subset (`<b>`, `<i>`, `<br>`, `<a>`), never via a WebView. No JavaScript, no CSS, no external image loading.
- **Pass data is excluded from Android Auto Backup.** The passes database is configured out of cloud backup and device-to-device transfer. Lives in `passes-storage` manifest rules.
- **Deletion is irreversible.** No trash bin, no undo. Stored data, including what the database caches, is wiped at delete time. Pass images and document thumbnails the app keeps decoded in a `PassImageCache` are the app's to clear; see the app-held controls below.
- **Storage is encrypted with a hardware-backed key.** SQLCipher database; the key is wrapped by Android Keystore, with StrongBox preferred when available.
- **No pass content in logs or telemetry.** The `TelemetryGuard` interface in `passes-core` accepts only enum events and bounded numeric/categorical fields, never user-content strings. Enforced by API shape, not reviewer discipline.
- **walt-android consumes this code directly.** The closed-source Walt app does not parallel-implement any trust-claim-bearing logic. What you audit here is what ships on the device, apart from the app-held controls in the next item.
- **Some controls are the app's, documented here but not enforceable here.** The kernel draws no provenance signal for a user-created card or a document on any surface, full screen included; the signal is the host's "Pass type" row, and the app must open a card's or document's detail surface only from its list or straight after creation or import, and the full-screen document view only from that detail surface (`docs/SCANNABLE_CARD_THREAT_MODEL.md`, C2; ADR 0005 D5.T). Every path that saves a card, a wallet-file restore and a format change included, must hold a code C4 flags until the user confirms it, one listing confirmation for a restore qualifying (C4 in the same threat model). The list that shows passes, cards and documents is the host's (the consumer list concession in the same threat model). A document's display label is bidi-isolated by the host that draws it (`docs/PDF_THREAT_MODEL.md`, threat 13). A host that keeps a `PassImageCache` clears it after the composition that drops a deleted pass's or document's row, since the cache holds that item's image in memory until then (`passes-ui/TRUST_CLAIMS.md` section 6). The kernel cannot check these at runtime; each section names the test the app pins it with, or is expected to. Other obligations on the app are stated in the ADR or threat model that sets them.

## Supported versions

walt-passes-android is pre-alpha. Until a v1.0 release, only the latest commit on `main` is supported.
