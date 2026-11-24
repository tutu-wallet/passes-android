# Brainstorming-phase decisions

The original v1 scoping decisions, kept for the ids that code and ADRs cite (for example `decision-wlt-0tn-q4`). Where a later ADR in [`docs/adr/`](adr/) differs, the ADR wins; entries below that an ADR now covers are pointers.

## decision-wlt-0tn-q1: pass acquisition and signature policy

PKPASS file import is the primary acquisition path.

- **1a. Signature policy: lenient, with mathematical verification, provenance display and defense in depth.** Always verify the PKCS#7 signature mathematically and reject tampering (manifest hash mismatch). Validate the Apple WWDR certificate chain but do not gate import on it; surface the result as "Verified Apple issuer: X", "Self-signed" or "Cert chain incomplete". Self-signed and non-Apple-cert passes may be imported. Mechanics: ADR 0001 D2 and D5.
  - Why not strict WWDR gating: it needs WWDR root distribution and rotation maintenance, adds friction for self-issuers and makes the library harder for consumers to use. Lenient verification plus provenance display already exceeds every comparable FOSS pass app (survey below).
  - Parser hardening (size limits, zip-slip protection, schema-strict JSON, bounded image decoding, parse-but-ignore for `nfc` / `webServiceURL` / personalization) is the load-bearing control whatever the signature policy.
- **1b.** The host wallet registers an OS-level intent filter for `application/vnd.apple.pkpass` and `.pkpass`.
- **1c.** PKPASS NFC payload: parse-but-ignore. The AID is never registered, to avoid an HCE/AID conflict with payment.
- **1d.** Manual entry and QR-scan import were deferred past the first PKPASS-only release; scannable cards (`docs/SCANNABLE_CARD_THREAT_MODEL.md`), live camera decode (ADR 0008) and document import (ADR 0005) now exist.
- Blast radius if a parser bug gave code execution in the app process: card cryptographic keys stay non-exportable in Keystore/StrongBox; transaction-history exfiltration is the realistic worst case.

## decision-wlt-0tn-q2: naming

- **2a.** Feature umbrella: "Walt Passes".
- **2b.** The open-source repository carries a platform suffix (`-android`) to leave room for an iOS sibling.
- **2c.** UI subtype labels per PKPASS type: `boardingPass` = "Boarding pass", `eventTicket` = "Ticket", `coupon` = "Coupon", `storeCard` = "Store card", `generic` = "Pass".
- Maven group is `is.walt`; artifactId equals the Gradle project name (ADR 0004).

## decision-wlt-0tn-q3: v1 scope

**In:** all five PKPASS types via a generic field-driven renderer; front and back fields; barcode rendering (PDF417, QR, Aztec, Code128); colors, logo, strip, background and thumbnail images; full localization (all locales retained); expiry display with an "Expired" badge; encrypted local storage (SQLCipher + Keystore); `.pkpass` intent filter; back-field URLs, phone numbers and emails via Policy B3; secure deletion.

**Out:** NFC/transit, dynamic updates (`webServiceURL`; see ADR 0006), lock screen integration, notifications, pass sharing, multi-device sync, cloud backup, personalization, wearables, pass editing, search/filter, pass export. Manual entry and QR-scan import are no longer out; see 1d.

### decision-wlt-0tn-q3.2: back fields

Actionable back-field content (URLs, phone numbers, emails) follows Policy B3: the value is visible and a confirmation sheet appears before any action (ADR 0003 D5; `SECURITY.md`).

- Render a limited HTML subset only: `b`, `i`, `br`, `a`.
- URL scheme allowlist: `http`, `https`, `mailto`, `tel`.
- External URLs open in Custom Tabs.
- Long URLs are middle-truncated in the label and shown in full in the confirmation sheet.
- No JavaScript, no CSS, no external image loads, no auto-fetch, no link preview.

### decision-wlt-0tn-q3.3: localization

Full localization, all locales kept. At display time pick the device locale, then English, then the first available. Re-render on device locale change. All `.lproj` directories stay on disk. System fonts cover non-Latin scripts; Compose handles RTL. A `.strings` parser is required (ADR 0001 addendum).

### decision-wlt-0tn-q3.4: pass deletion

No undo and no trash bin; the passes database is excluded from Auto Backup; no PII in logs; cached decoded data is wiped on delete; a confirmation dialog matches the existing card-deletion pattern. No `VACUUM` after delete: SQLCipher, the non-exportable Keystore key, file-based encryption, the backup opt-out and sandboxing together make freed-page bytes unreadable, and flash wear-leveling means `VACUUM` could not guarantee bit-level erasure anyway. Implementation: ADR 0002 D5 and D6.

## decision-wlt-0tn-q4: repo location, license, distribution

- **4a.** A separate repository, not a monorepo subdirectory.
- **4b.** License: Apache 2.0. It is the norm for Android libraries (AndroidX, Hilt, OkHttp), allows reuse without imposing copyleft on consumers, and the privacy and security promises live in the code rather than the license. The GPLv3 precedent among FOSS pass apps applies to apps, not libraries.
- **4c.** Distribution: composite Gradle build now, GitHub Packages (preferred over Maven Central) later. See ADR 0004 and `docs/composite-build.md`.

## decision-wlt-0tn-q5: library shape

The kernel is the wallet's open pass-handling code; the wallet wraps it thinly (dependency wiring, themed screens, the manifest intent filter). The original three modules (`passes-core`, `passes-storage`, `passes-ui`) have grown; `docs/module-map.md` and `CLAUDE.md` hold the current layout.

**Decisive constraint:** the wallet uses these implementations directly and never reimplements code that bears a trust claim. The wallet is closed source, so trust through transparency requires every security and privacy claim to be auditable here. The README leads with transparency-for-trust, not library-for-reuse.

## FOSS PKPASS signature-policy field survey (2026-05-03)

Surveyed PassAndroid (Feb 2025), FossWallet (Apr 2026), Catima and Google's Pass Converter.

- None performs PKCS#7 signature verification, verifies manifest hashes, validates the WWDR chain or surfaces provenance in the UI. FossWallet has an unimplemented `TODO check signature before returning` in `persistence/loader/PassLoader.kt`.
- Hardening is thin: PassAndroid extracts with zip4j `extractAll()` in `UnzipPassController.kt` without validation; none sets size limits or image dimension caps; FossWallet has no zip-slip canonical-path check.

So even the lenient policy puts the kernel ahead of every comparable FOSS pass app, whereas strict WWDR gating would have no FOSS prior art for chain plumbing, root distribution or rotation.
