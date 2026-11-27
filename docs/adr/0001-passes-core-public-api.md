# ADR 0001: passes-core public API

- Status: Accepted
- Decision context: `decision-wlt-0tn-q1` (lenient signature policy), `decision-wlt-0tn-q3` (full localization, secure deletion), `decision-wlt-0tn-q5` (library shape), in [`docs/brainstorm-decisions.md`](../brainstorm-decisions.md)

## Context

`passes-core` is the pure-Kotlin/JVM module that performs all PKPASS parsing, signature verification, and modeling for Walt's pass handling. It must:

- Run identically on Android (Walt's wallet) and on a JVM CI host (audit tooling, fuzzers), so it cannot import `android.*` or `java.util.Locale`.
- Surface trust-relevant information without forcing the consumer to inspect exception messages, since the UI must distinguish *tampered* from *malformed* from *unsupported*.
- Make it structurally impossible for a consumer to log pass content through the telemetry hook, so the trust claim "pass content never appears in logs or telemetry" survives careless integration.
- Apply lenient signature defaults (accept unsigned and self-signed archives, surface their status) per the q1 decision, while keeping a strict mode reachable for tests and audit tooling.

## Decisions

### D1. Sealed-interface result type, not `Result<Pass, Throwable>`

`PassParser.parse` returns `ParseResult`, a sealed interface with four arms: `Success`, `Tampered`, `Malformed`, `Unsupported`. Each failure arm carries a typed reason (e.g. `MalformedReason.ResourceLimitExceeded(ResourceLimit.JsonDepth)`).

Rationale: CLAUDE.md's rule of "Result<T> over exceptions" was written against typical I/O failure spaces. The PKPASS failure space is rich enough that a `Throwable` would erase the partition the UI needs. Tampered and malformed must render differently (a tampered pass is a security event; a malformed one is a corrupt download), and both must be distinguishable from "your build of Walt doesn't know this pass style yet." Sealed interfaces preserve that partition at the type level.

### D2. `SignatureStatus` is the *successful-parse* trust band, not a failure type

Cryptographic *failures* during signature verification produce `ParseResult.Tampered`, not a `SignatureStatus.Invalid` arm. `SignatureStatus` only describes what level of provenance a successfully parsed archive carried: `Unsigned`, `SelfSigned`, `AppleVerified`, `CertChainIncomplete`.

Rationale: collapsing "tampered" and "weak signature" into one type would make it easy for a consumer UI to render them identically and forfeit the q1 lenient policy's purpose. The q1 policy is "accept and warn"; that requires a distinct type for "accepted but warn-worthy."

### D3. `TelemetryGuard` enforces PII safety structurally

`ParseSucceededEvent` and `ParseFailedEvent` contain only enums, counts, and durations. There is no `String` parameter anywhere in the `TelemetryGuard` interface. A consumer cannot call `guard.onParseSucceeded(serialNumber = pass.serialNumber, ...)` because the method signature refuses to accept it.

Rationale: this is the load-bearing implementation of the README trust claim. Documentation-only PII rules drift; type signatures don't. Reviewers should treat any future addition of a `String` (or `Pass`, or `PassField`) parameter to a `TelemetryGuard` method as a security-policy change.

The flattened `SignatureStatusKind` and `ParseFailureKind` enums exist so this telemetry contract can travel through metric backends that prefer string dimensions, without expanding the interface to take arbitrary strings.

### D4. No Android, no `java.util.Locale`, no `java.time` on the public surface

`passes-core` depends only on the Kotlin stdlib and `kotlinx.serialization`. Time is `PassInstant(epochMillis: Long)`; locales are `PassLocale(tag: String)` carrying a BCP-47 tag verbatim. Color is `ColorValue(rgb: Int)`.

Rationale: lets the same module compile against minSdk-21 Android (without core library desugaring of `java.time`), against a JVM CI fuzzer, and against future KMP targets without per-target shims. Consumers that already depend on Android or kotlinx-datetime do the conversion at the module boundary.

`java.time` is used internally on the JVM path (`OffsetDateTime` in the pass.json decoder, `Instant` / `LocalDateTime` / `ZoneId` in the encoder); the repo-wide minSdk is 28, where it needs no desugaring. The public surface carries `PassInstant`, never `java.time` types; a non-JVM target would need `expect`/`actual` for the two `java.time` call sites.

### D5. `ParserConfig` defaults are lenient on trust, restrictive on resources

Trust toggles default to accept-and-surface (`acceptUnsignedArchives = true`, `acceptSelfSignedCertificates = true`). Resource limits default to values that fit any legitimate pass observed in the FOSS field survey but cut off zip-bomb / JSON-bomb shapes far below process-OOM (10 MB archive, 256 entries, 16-deep JSON, 16M-pixel images, 32 MiB total inflated bytes).

Rationale: q1 establishes the trust posture; the threat model establishes the resource posture. They live in the same config object because a future "strict" mode (audit tooling) wants to flip both kinds of toggle together, and `ParserConfig.Strict` is a single named alternative.

### D6. `explicitApi()` and `public` everywhere

The Kotlin compiler is configured with `explicitApi()`. Every API element carries an explicit `public` modifier so that accidental visibility loss surfaces as a build failure rather than as a slow-burn breakage in a downstream consumer.

Rationale: walt-android imports this module directly. A removed `public` modifier becomes a downstream compile error rather than a silent API change.

## Consequences

- `passes-storage` and `passes-ui` depend on `Pass`, `ParseResult` and `SignatureStatus`.
- Adding a parameter to `TelemetryGuard` needs a security-policy review. That review gate is the point.
- KMP targets are not enabled, but nothing in the API surface forbids them.

## Open questions

- `BarcodeFormat` includes `Code128`; revisit if it complicates renderer surface area.

## Addendum: bundle reader and nested archives

D1 through D6 stand; D5 gains a nested-archive amplification row and D7 freezes the bundle surface.

### Amendment to D5: nested archives are a separate amplification class

A `.pkpasses` bundle, and any consumer superset of it, is a ZIP of ZIPs. D5's limits bound one archive and nothing bounds the sum across inner archives: a bundle of a few hundred inner entries each just under the per-archive cap inflates to gigabytes while every one is individually legitimate. Symlinks are not a vector at either layer: `java.util.zip` exposes no symlink attribute and neither reader touches the file system, so a symlink-shaped entry is plain bytes.

`PassBundleReader` reads the outer layer once and applies the `BundleConfig` caps before any inner archive is opened:

| Cap | Meaning | Default |
|---|---|---|
| `maxArchiveBytes` | outer compressed bytes, pre-checked against the declared size, then bounded while streaming | 256 MiB |
| `maxEntries` | entries in the bundle | 200 |
| `maxEntryBytes` | inflated bytes per entry (`ParserConfig.DEFAULT_MAX_ARCHIVE_BYTES`) | 10 MiB |
| `maxCumulativeBytes` | inflated bytes summed over every file entry | 256 MiB |

Both inflation caps are checked after every chunk, so a bomb stops mid-entry before a buffer materializes. Entries the allowlist declines and payloads behind a directory (`/`) name are drained through the same caps: counted, never handed out. Zip-slip names and duplicate names reject the bundle. Every input-driven failure is a typed `BundleRejection` arm (`NotAZipArchive`, `SourceUnreadable`, `UnsafeEntryName`, `DuplicateEntryName`, `LimitExceeded(BundleLimit)`), and a `BundleReadResult` carries counts only, so an entry name, which is attacker-controlled text, never reaches a result or telemetry.

The reader does not open, parse or validate an inner archive. Each accepted entry is delivered as bytes and the caller runs `PassParser` on it, so D5's per-archive limits and the D2 signature path apply to every inner pass unchanged.

Consumers: under the default config a skipped entry larger than `maxEntryBytes` rejects the whole bundle, since draining it costs what accepting it would. A consumer superset that carries documents next to passes must raise `maxEntryBytes` deliberately, and one that raises `ParserConfig.maxArchiveBytes` must raise `BundleConfig.maxEntryBytes` with it.

The stream chain lives in `internal/ArchiveStreams.kt`, shared by `extractSafely` and the bundle reader. A payload behind a directory-entry name pays `maxEntryBytes`. An `IOException` from the caller's stream during the magic sniff folds into `MalformedReason.NotAZipArchive` (the frozen `MalformedReason` has no `SourceUnreadable` arm).

### D7. `PassBundleReader` is the frozen outer-layer surface

Public in `is.walt.passes.core` and frozen the way D1 through D6 freeze the parser surface:

- `PassBundleReader` (fun interface; `read(PassSource, BundleEntrySink): BundleReadResult`). Companion: `MIME_TYPE` (`application/vnd.apple.pkpasses`), `FILE_EXTENSION` (`pkpasses`), `PASS_ENTRY_EXTENSION` (`pkpass`), `create(BundleConfig = BundleConfig())`.
- `BundleEntrySink` (fun interface) returning `BundleVisit` (`Continue`, `Stop`).
- `BundleEntry` (sealed): `Accepted(name, ordinal, bytes)` and `Skipped(name, ordinal)`. Plain classes, not data classes, so `toString` never prints the name.
- `PassBundleSniff` (`Bundle`, `NotBundle`, `Undetermined`; `RECOMMENDED_HEADER_BYTES` = 285) with `sniffPassBundle(header)`. A dispatch hint that judges the first local file header's name by `BundleEntryAllowlist.PkpassOnly`; never a trust decision.
- `BundleConfig` (data class) with the `DEFAULT_MAX_*` constants above.
- `BundleEntryAllowlist` (fun interface; sees the name only, never bytes) and `BundleEntryAllowlist.PkpassOnly`: root-level `<non-empty stem>.pkpass`, ASCII-only case fold.
- `BundleLimit` (`ArchiveSize`, `EntryCount`, `EntrySize`, `CumulativeSize`) and `BundleLimit.limitFrom(BundleConfig)`.
- `BundleReadResult` (sealed): `Completed`, `Stopped`, `Rejected(reason)`, each carrying `accepted` and `skipped` counts and nothing else.
- `BundleRejection` (sealed) and `BundleFailureReason` (eight values, one per arm and per limit) with `BundleRejection.toFailureReason()`.

Rationale, in the D3 spirit: no `String` travels in any result or failure type, so the "pass content never appears in logs or telemetry" claim extends to bundle entry names without a new `TelemetryGuard` method. No bundle event is added to `TelemetryGuard`. Adding a name, path or bytes field to `BundleReadResult` or `BundleRejection` is a security-policy change, not an API addition.

### D8. `PassEncoder` writes an unsigned archive; it never writes a `signature`

`PassEncoder.encode(pass, config)` turns a parsed `Pass` back into a `.pkpass` containing `pass.json`, `manifest.json`, the top-level role images, and one `<tag>.lproj/pass.strings` per locale. It returns `PassEncodeResult`, a sealed interface: `Success(bytes)`, `LimitExceeded(ResourceLimit)`, `InvalidLocaleTag` (payload-free, so no pass-derived text reaches `toString`; only a hand-built `Pass` can trip it). It exists for rows stored before ADR 0002 D3 retained archive bytes (`pass_archives`; at-rest consequences in ADR 0002 D9); a pass whose original bytes exist is exported byte-exact and never re-encoded.

Rationale: Walt's export container requires every pass entry to be a `.pkpass`, and a JSON projection of the model would be a second, Walt-only pass shape with its own parser, which the parallel-implementation rule forbids. The encoder is lossy by construction (it emits only what `Pass` keeps; `passTypeIdentifier`, `teamIdentifier`, `webServiceURL`, `authenticationToken`, `nfc`, extra barcodes and localized images are absent; lone surrogates in any string field become `?`) and the parser requires none of the dropped keys. `manifest.json` is written because the parser rejects an archive without one and rejects entries it does not list; it is the SHA-1 integrity index, not provenance (D2). No `signature` is ever written, so re-import is `SignatureStatus.Unsigned` under the default policy and `Tampered(SignatureCryptoFailure)` under `Strict`: nothing the kernel produces can be mistaken for issuer-signed. Output is checked against the same `ParserConfig` the parser enforces and is byte-deterministic for an equal `Pass` on the same device (fixed entry order, sorted keys, fixed local-calendar timestamp; deflate output can differ across zlib versions) so a consumer can hash it. Control characters are written as `\Uxxxx`, so output never holds a NUL byte the UTF-16 sniff could misread.

## Addendum: single-archive inflated-size cap (amendment to D5)

Per-entry (`maxEntryBytes`) and count (`maxEntries`) caps alone leave their product, about 1 GiB at defaults, as the ceiling on retained bytes: a small archive of many highly compressible entries could exhaust the host heap before manifest or signature checks ran. `ParserConfig.maxInflatedBytes` (default 32 MiB) bounds the sum of every entry's decompressed bytes, directory payloads included, checked while inflating. A breach is `MalformedReason.ResourceLimitExceeded(ResourceLimit.InflatedSize)`, flattened to `ParseFailureReason.InflatedSizeLimit`. `PassEncoder` honours the same cap.

The default is about three times the compressed cap, since pass members are mostly PNGs that barely compress. A consumer that raises `maxArchiveBytes` past `maxInflatedBytes` must raise `maxInflatedBytes` with it. A consumer with an exhaustive `when` over `ResourceLimit` or `ParseFailureReason` gains one arm.

## Addendum: a bad `.strings` file drops its locale, not the pass

A `<locale>.lproj/pass.strings` that fails to decode or parse is left out of `Pass.locales`; the pass still parses, and the renderer falls back as for any missing locale. Integrity is untouched: manifest and signature checks run over every archive entry before any `.strings` file is parsed, so a tampered file is still `Tampered(FileHashMismatch)` whatever its content. Resource limits still fail the whole pass: `maxLocaleCount` is checked before parsing, and a value over `maxJsonStringBytes` is `ResourceLimitExceeded(JsonStringSize)`. `MalformedReason.InvalidStrings` and `ParseFailureReason.InvalidStrings` stay on the surface so consumers' exhaustive `when`s compile, but the default parser no longer returns them.

Amendment to D3: `ParseSucceededEvent` has `droppedLocaleCount: Int = 0`, a count like `localeCount` that carries no locale tag or file content.

Grammar: the `.strings` parser reads a subset of Apple's old-style property-list strings grammar wider than `"key" = "value";`: `'`-quoted and unquoted tokens (`[A-Za-z0-9_$/:.-]`), the `"key";` shorthand, an optional enclosing `{ }`, ASCII-range octal escapes, `\a \b \f \v \'`, a backslash before a line break, UTF-32 BOMs, and BOM-less UTF-16 recognised by its NUL-byte pattern. The value cap also applies to a shorthand key that becomes its value and to unquoted values.

Deliberate divergences, where Apple's parser accepts input this one rejects (the locale is dropped): unknown escapes such as `\x`, `\U` with fewer than four hex digits, octal `\200` to `\377`, an unterminated block comment, trailing text after the last entry, XML and binary-plist `.strings` files, and BOM-less files that are not UTF-8 or UTF-16 (no single-byte fallback, which would bring back the silent mis-rendering the strict decoder exists to prevent). Also rejected: lone surrogates, unterminated strings and non-string values.

## Addendum: an image that is not a PNG is left out, not the pass

A top-level `*.png` entry is kept in `Pass.images` only when it starts with the PNG signature and an IHDR chunk declaring non-zero sides. Any other bytes under an image name (another container, a truncated file, an empty entry) are left out; the pass still parses and the renderer falls back as for any missing image. Every image kept therefore has a readable header for the pixel cap. Integrity is untouched: manifest and signature checks run over every archive entry first. The resource limit still fails the whole pass: a PNG over `maxImagePixelCount` is `ResourceLimitExceeded(ImagePixelCount)`.

Amendment to D3: `ParseSucceededEvent` has `droppedImageCount: Int = 0`, a count like `imageCount` that carries no entry name or content.

`PngHeader` is public: `PngHeader.read(bytes)` returns the declared width, height and interlace flag, or null, and decodes nothing. It is the parser's test, exposed so `passes-ui` applies the same one before a pass image reaches a decoder. The parser is not the control for what gets decoded, since rows stored before this rule and `ImageBytes` a consumer builds never pass through it; `BoundedImage` is (`passes-ui/TRUST_CLAIMS.md` section 6).

`PngPicture` is public: `PngPicture.of(bytes)` returns a copy of a PNG holding only the chunks that carry its picture, or null for bytes that are not a PNG or end inside a chunk. It reads chunk lengths and types, inflates at most one colour profile into a scratch buffer to measure it, and decodes no pixel. `passes-ui` gives the platform decoder that copy (ADR 0003, "Amendment to D5: `BoundedImage` decodes the picture chunks only"). The parser does not apply it: `Pass.images` holds each entry's bytes as the archive carried them.

`PassEncoder` writes whatever images a `Pass` holds. One that is not a PNG carries no pixel cap there and is left out again when the archive is parsed.

## Tests

`PassBundleReaderTest`, `PassBundleReaderSourceTest` and `PassBundleSurfaceTest` (D5 nested caps, D7); `SafeArchiveExtractorTest` and `PassParserTest` (inflated cap, locale drop, image drop); `PngHeaderTest` (the header read); `PngPictureTest` (the chunks kept and left out); `PassEncoderTest` (D8); `StringsParserTest` (grammar).
