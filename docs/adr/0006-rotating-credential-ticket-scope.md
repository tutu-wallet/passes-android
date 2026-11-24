# ADR 0006: rotating-credential ticket scope

- Status: Accepted
- Decision context: `decision-wlt-0tn-q1` (PKPASS file import as primary path) and `decision-wlt-0tn-q3` (`webServiceURL` polling and dynamic updates are out), in [`docs/brainstorm-decisions.md`](../brainstorm-decisions.md)

## Context

Ticketmaster SafeTix and analogous rotating-credential ticket flows are the dominant distribution path for major-venue tickets. The Ticketmaster app shows a rotating QR (or PDF417) with an "Add to Google Wallet" button; no PDF or PKPASS export is offered for SafeTix events, and Apple Wallet's iOS path uses a companion-app pattern that does not apply on Android. Research on 2026-05-07 established:

- **Mechanism.** The barcode carries a bearer token plus two RFC 6238 SHA-1 TOTPs (15 s step) computed locally from an event secret and a per-holder secret. The save-to-Wallet JWT carries an object reference, not the secrets (Google's documented best practice: insert the object server-side with `object:insert`, send only its ID). Secrets are re-issued shortly before each event and bound to a device. Public writeup: conduition.io, "Reverse Engineering Ticketmaster's Rotating Barcodes" (July 2024). Google's schema: the `RotatingBarcode` REST reference.
- **`FLAG_SECURE`.** The Ticketmaster ticket Activity sets it, blocking screen capture, screenshots, recents thumbnails and accessibility-service bitmap retrieval at the OS level.
- **Save flow.** Google Wallet saves through an explicit `PayClient` GMS intent or an auto-verified Google App Link; a third-party wallet can intercept neither and Google Wallet offers no export API.
- **PKPASS has no rotation field.** The barcode `message` is a static string in the signed manifest. `webServiceURL` pushes are capped at roughly 20 per pass per day on Apple Wallet and 3 on Google Wallet, against about 5,760 per day for a 15-second rotation. Apple's companion-app entitlement lets an installed app update a pass it owns; it is not a rotation primitive (the rotating QR in Apple Wallet is drawn by the issuer's app).
- **Smart Tap NFC** needs merchant-provisioned ECDH keys held by Google; no third-party wallet can participate without onboarding the venue does not own.
- **Reverse-engineered renderers** (TicketGimp, Amosa, Secure.Tickets) extract secrets with DevTools against Ticketmaster's authenticated session, violating its Terms of Use and plausibly DMCA Section 1201, and are the subject of active litigation.
- **No third-party wallet has solved this.** Pass2U and WalletPasses implement Apple's poll-then-replace flow but cannot rotate barcodes; PassAndroid, FossWallet and Catima hold static codes only.
- **Standards horizon.** W3C VC 2.0 (Recommendation 2025-05-15), OpenID4VCI and the EUDI Wallet (member-state mandate 2026-09; a Lufthansa + Amadeus pilot in 2025) show ticketing under verifiable credentials is feasible, but no major ticketing platform has announced participation, and no regulatory lever (DMA, eIDAS 2.0, GDPR Article 20, US v. Live Nation) compels Ticketmaster to release ticket payloads to third-party wallets.

This ADR preserves the trust claim by refusing to ship rotating-credential rendering the kernel cannot honestly back, and lists the excluded paths so a contributor does not re-introduce them.

## Decisions

### D1. Rotating-credential tickets are out of scope

This ADR adds no import path. Rotating-credential tickets, whatever the issuer, are out of scope. The kernel does not register a Smart Tap HCE service, does not poll `webServiceURL` at any cadence, does not implement reverse-engineered SafeTix rendering, and does not intercept Save-to-Google-Wallet intents or decode their JWTs for secrets the JWTs do not carry. Each closed path fails technically (`FLAG_SECURE`, explicit GMS intents, Smart Tap merchant binding, APNs rate caps) or fails the trust-claim posture.

### D2. Import surfaces detect and explain rather than fail silently

The consumer's ingestion layer (`passes-core` has no Android intent surface) should recognise a rotating-credential artifact and show a typed message explaining why the app cannot hold it, with a deep link to the issuing wallet (Google Wallet on Android), instead of a generic "import failed" or a static fallback that would not work at the gate. Detection signals:

- a shared or pasted URL matching `pay.google.com/gp/v/save/*`;
- a URL matching `tickets.ticketmaster.*`, `am.ticketmaster.*`, `ticketmaster.dk/*` or other Ticketmaster ticket paths in supported markets;
- a `.pkpass` whose `passTypeIdentifier` matches a known rotating-credential issuer (forward compatibility; no current matches).

The list is conservative: a false negative degrades to the existing "could not import" path, a false positive misleads the user. The detected JWT is treated as opaque beyond confirming the URL pattern, even though its payload is signed but not encrypted. Silent failure is incompatible with transparency-for-trust: a user cannot otherwise tell "the app is broken" from "the app deliberately does not hold this kind of ticket."

### D3. A wallet-native PKPASS rotating-barcode extension is the long-term seam, deferred

Apple Wallet ignores unknown PKPASS keys and `userInfo` is the spec-blessed app-specific slot. A cooperating issuer could ship a PKPASS with a static fallback `barcodes[]` entry plus a Walt-namespaced `userInfo.waltRotatingBarcode` block shaped after Google's `RotatingBarcode`. The renderer would compute the TOTP locally; the signed manifest stays static because the secret is signed, not the rendered code. That sidesteps `webServiceURL` rate caps, APNs semantics and the companion-app entitlement. Incumbents will not adopt it; smaller ticketing platforms, festivals, season tickets and self-issuers may, and an audit-friendly public extension spec is the format this repository exists to ship.

Not implemented and not committed as a deliverable. If pursued it needs its own threat model: TOTP secret at rest in SQLCipher, secret-touching code paths in `passes-core`, a secret-handling telemetry guard, and PKCS#7 manifest verification behaviour when a rotating block is present in `userInfo`. Refusing the closed paths in D1 otherwise leaves no answer for issuers who want to issue rotating tickets directly; a documented, deferred shape is that answer.

### D4. OpenID4VCI / EUDI-holder alignment is the standards-track direction, deferred

The EU Digital Identity Wallet under eIDAS 2.0, with W3C VC 2.0 and OpenID4VCI, is the standards path with regulatory weight. When a ticketing issuer emits ticket-shaped Electronic Attestations of Attributes, a wallet-as-EUDI-holder lets the kernel accept them without issuer-specific parser code. Tracking waits for a concrete issuer signal or relying-party demand after the 2026-09 mandate; the horizon is one to two years. Structural alignment is the posture, not the build: building holder primitives ahead of issuer demand is premature, and declaring no posture leaves a gap in the trust narrative.

### D5. Explicitly excluded approaches

Re-introducing any of these is a security-policy and trust-claim change requiring an ADR amendment.

| Excluded approach | Reason |
|---|---|
| Reverse-engineered SafeTix rendering | Violates Ticketmaster's ToU, plausibly DMCA Section 1201, subject of active patent litigation. Fails the audit standard that every trust-claim-bearing behavior lives in this repository, because the secrets come from another company's authenticated user session. |
| Save-to-Google-Wallet JWT interception or decoding for rotation secrets | The JWT does not carry the secret; interception is not viable (explicit GMS intent). |
| `FLAG_SECURE` bypass via accessibility, MediaProjection or overlay tricks | OS-level block by design; circumvention is detection-evading by nature and incompatible with the trust claim even if it worked. |
| Smart Tap HCE impersonation at venue gates | Cryptographically infeasible without Google merchant onboarding; would need key extraction or session spoofing. |
| `webServiceURL` polling at TOTP cadence | Two orders of magnitude over Apple's and Google's caps and bandwidth-prohibitive (a re-signed pass per rotation). Already excluded by `decision-wlt-0tn-q3`. |
| Background screen capture of the Ticketmaster app or any other wallet | Blocked by `FLAG_SECURE`; excluded for the same trust-posture reasons as the bypass row. |

Enforcement is this ADR plus code review against the catalogued shapes; no symbol-name test exists, since one would over-specify.

## Consequences

- Rotating-credential handling adds no acquisition path.
- PRs that add `webServiceURL` handling, JWT decoding for rotation secrets, accessibility-service use touching third-party wallet windows, or Smart Tap HCE registration need an ADR amendment; the D5 exclusions are the test.
- Documenting what the kernel does not do, and why, is part of the transparency-for-trust commitment; this ADR is the canonical statement for rotating-credential tickets.

## Open questions

- The explain-and-link UX and issuer match strings (D2) are consumer-side work, not yet done.
- The extension spec and its threat model (D3) are not written.
- The EUDI-holder posture (D4) has no work item until an issuer signal or the 2026-09 mandate produces demand.
- The research is dated 2026-05-07. The standards and antitrust horizons (US v. Live Nation remedy, EUDI member-state delivery, any new EU Commission proceeding) are time-sensitive; revisit when one moves materially.

## Sources

Primary sources behind the research (dated 2026-05-07):

- Ticketmaster SafeTix reverse engineering: https://conduition.io/coding/ticketmaster/ and https://www.schneier.com/blog/archives/2024/07/reverse-engineering-ticketmasters-barcode-system.html
- Ticketmaster SafeTix partner documentation: https://developer.ticketmaster.com/products-and-docs/apis/partner/safetix/
- Google Wallet `RotatingBarcode` reference: https://developers.google.com/wallet/reference/rest/v1/RotatingBarcode
- Google Wallet rotating-barcode practices and JWT save flow: https://developers.google.com/wallet/tickets/events/resources/rotating-barcodes and https://developers.google.com/wallet/tickets/events/use-cases/jwt
- Google Wallet Smart Tap: https://developers.google.com/wallet/smart-tap/introduction/overview
- Google `pass-converter`: https://github.com/google-wallet/pass-converter
- Apple PassKit updating passes (`webServiceURL`): https://developer.apple.com/library/archive/documentation/UserExperience/Conceptual/PassKit_PG/Updating.html
- Android `FLAG_SECURE`: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_SECURE
- W3C Verifiable Credentials 2.0: https://www.w3.org/press-releases/2025/verifiable-credentials-2-0/
- EUDI wallet architecture and reference framework: https://eu-digital-identity-wallet.github.io/eudi-doc-architecture-and-reference-framework/2.7.3/technical-specifications/
- Regulation (EU) 2024/1183 (eIDAS 2): https://www.european-digital-identity-regulation.com/Article_3_(Regulation_EU_2024_1183).html
- Digital Markets Act interoperability: https://digital-markets-act.ec.europa.eu/questions-and-answers/interoperability_en
- US DOJ complaint against Live Nation-Ticketmaster: https://www.justice.gov/archives/opa/pr/justice-department-sues-live-nation-ticketmaster-monopolizing-markets-across-live-concert
- GDPR Article 20 (portability): https://gdpr-info.eu/art-20-gdpr/
