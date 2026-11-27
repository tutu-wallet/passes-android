# ADR 0007: user-label side-channel override and trust-caption rule for pkpass

- Status: Accepted
- Decision context: extends ADR 0002 D3 (the `passes` schema) and ADR 0003 (passes-ui theming). Trust-claim posture inherits from the repo charter: every trust-claim-bearing behavior lives in this repository.

## Context

The host wallet offers a rename affordance: a user who imported a boarding pass can label it "Mom's flight home". Documents (`updateDocumentLabel`) and scannable cards (`updateScannableCard`) have no signed identity, so renaming them is harmless. A pkpass is alone in carrying a PKCS#7-attested issuer identity that renaming must not silently hide.

A pkpass's visible identity on the card front and in list items is the signed `pass.json` `organizationName` (after `pass.strings` substitution; `PassFront`). That string binds the user's mental model of who issued the pass to the cryptographically verified issuer. A rename that overwrites the displayed title decouples displayed identity from signed identity: the screen says one thing, the attested issuer is another, and nothing on screen says which is which. This ADR fixes the data model, repository API, surface contract and in-repo enforcement that keep the signed identity on every surface presenting the override.

## Decisions

### D1. `user_label` is a side-channel column on `passes`, never on the signed payload

A nullable `user_label TEXT` column on `passes` (`NULL` = no override, the default). It is stored beside the row, not inside `pass_json`, which must stay the parser's exact reconstruction of the signed body: putting the label inside would break re-verification against the original PKCS#7 signature. A separate column makes the override visibly not-signed at the storage layer. No index: the column is not a query key. Length cap `MAX_USER_LABEL_CHARS = 100` (`PassUserLabelBounds`), mirroring `DocumentBounds.MAX_LABEL_CHARS`, enforced at the repository boundary.

### D2. `PassRepository.updatePassUserLabel(id, label)` replaces or clears

- `label = null` clears the override.
- A non-null label whose `.trim()` is empty is also a clear: defense in depth against a "rename to all whitespace" bug that would render an invisible primary identity. The storage layer cannot reach the all-spaces state.
- A trimmed length over `MAX_USER_LABEL_CHARS` is rejected with `StorageError.PassRejected(PassUpdateRejectedKind.LabelTooLong)`. The cap is checked before the row is loaded, so an over-long label against an unknown `id` is `PassRejected`, not `IntegrityViolation` (the same precedence as `updateDocumentLabel`).
- No matching row (label within cap) returns `StorageError.IntegrityViolation`.
- Leading and trailing whitespace is trimmed; internal whitespace is preserved (collapsing it is a UI concern).

### D3. `updated_at_epoch_ms` is not bumped by label changes

`updated_at_epoch_ms` tracks "the canonical pass payload changed" (the upsert re-import path). A label change touches no payload, image, locale or signed field. This matches `updateDocumentLabel` (preserves `imported_at_epoch_ms`) and `updateScannableCard` (preserves `created_at_epoch_ms`). The `passes` flow sorts by `created_at_epoch_ms DESC`, so a bump would not change ordering anyway; a "recency of user attention" sort would be a new column, not a re-purposing of `updated_at`.

### D4. `StoredPass` and `PassSummary` surface `userLabel` as a separate field

Both carry `userLabel: String?`; the kernel never blends it into `Pass` or `organizationName`, so `Pass` carries only the parser's view of the signed payload and the override never reaches `passes-core`. A consumer that ignores `userLabel` and renders `pass.organizationName` stays trust-correct; the override is opt-in at the consumer layer.

### D5. The signed `organizationName` must remain visible when `userLabel` is set

> When a pkpass's `StoredPass.userLabel` is non-null, every UI surface that presents that pass's primary visible identity must also present the signed `organizationName` on the same surface, not behind a tap-to-reveal, in a typographic treatment at least as legible as a caption or eyebrow under the user label.

```
┌──────────────────────────────────────┐
│ Mom's flight home          [Verified]│   <- user_label (primary)
│ Delta Air Lines                      │   <- signed organizationName (eyebrow)
│ ... rest of pass body / fields ...   │
└──────────────────────────────────────┘
```

The anchor is `organizationName`, not `description`. `organizationName` is the spec's issuer-identity string; `description` is an accessibility summary. The trust bond a rename can decouple is "this pass identifies itself as issued by X". `description` remains available for accessibility surfaces but is not the trust caption.

Equality edge case: when `userLabel.trim()` equals the displayed `organizationName` (after `pass.strings` substitution) under case-insensitive ASCII comparison, the surface suppresses the eyebrow to avoid two identical lines. Storage still records what the user typed, and the rule is satisfied because the primary identity is already the signed identity.

### D6. A kernel-owned Composable enforces the rule

`passes-ui` exposes `PassIdentityBlock(pass, userLabel, modifier, locale, primaryColor, eyebrowColor)` as the canonical renderer of D5:

- `userLabel` null: the displayed `organizationName` alone.
- `userLabel` non-null and different from it: the label as the primary line and the displayed `organizationName` as an eyebrow sub-line, both on the same surface.
- `userLabel.trim()` equal to the displayed `organizationName` (D5 edge case): the displayed `organizationName` alone.

`PassFront` routes its header eyebrow through it. `PassImportConfirm` does not: at import no `userLabel` exists, so the surface shows signed identity by construction.

A consumer's pkpass list item and any other pkpass chrome must call `PassIdentityBlock` rather than render `StoredPass.userLabel` directly. Surfacing `userLabel` as bare primary identity is a trust-claim violation, the same shape as a verifier-name swap.

Enforcement: `PublicApiSurfaceTest` in `passes-ui` asserts `PassIdentityBlock` exists and is public, so consumer list code compiles against a tested symbol. `TrustClaimSurfaceTest` exercises each render case and asserts the signed `organizationName` is on screen in every non-suppressed case. Consumer-side enforcement is code review; this ADR is what the reviewer cites.

### D7. Telemetry: a label-mutation event with no PII

`StorageTelemetryGuard.onUserLabelUpdated(type: PassType, hadPriorLabel: Boolean, clearing: Boolean)` carries only the pass type, whether an override already existed and whether the write is a clear. No `String`, `Pass` or `PassField` parameter (ADR 0002 D7/D8 discipline): the label text never reaches telemetry, and adding it or any pass field is a security-policy change. No `passes-ui` event for "override is displayed": the rule is enforced by the Composable's structure, not a runtime event.

## Consequences

- The schema migration is a single `ALTER TABLE passes ADD COLUMN user_label TEXT` (no data is transformed); `SchemaMigrationTest` checks the column exists and defaults to `NULL`.
- The consumer renders renames through `PassIdentityBlock` and carries no parallel trust-claim logic.
- This ADR is the canonical answer to "why can't we let the user just type a new title?"

## Open questions

- Whether `description` should drive a TalkBack `contentDescription` on `PassIdentityBlock`; the trust rule does not depend on it.
