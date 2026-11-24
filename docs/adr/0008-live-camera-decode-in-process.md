# ADR 0008: live-camera barcode/QR decode runs in-process, not in the isolated sandbox

- Status: Accepted
- Decision context: extends ADR 0005 D3 (the isolated process is a mechanism for containing a native codec surface, not a blanket requirement) and the `passes-barcode` / `passes-barcode-core` split. The static-image decode path stays isolated; this ADR scopes why the live path does not.

## Context

Barcode/QR decode from a static image file runs in the `:barcodeDecoder` isolated-process sandbox (`passes-barcode`, `BarcodeImageDecoder`). The sandbox exists because that path hands attacker-controlled compressed containers (`.webp` / `.png` / `.jpg` / `.gif`) to a native still-image codec (libwebp, libjpeg-turbo, libpng, Skia) before ZXing sees pixels. That step is a live remote-code-execution surface (libwebp CVE-2023-4863 on CISA KEV; Skia/PNG CVE-2019-1987; Samsung Quram DNG CVE-2025-21042 used to deploy LANDFALL spyware). Isolation means a use-after-free there compromises an empty permissionless sandbox, never the main process that holds the SQLCipher key, the Keystore session and decoded bytes of other passes: the ADR 0005 D3 argument applied to the image codec.

Live camera scanning (point the camera at a code, import it) shares the ZXing decode core, so the question is whether it must also run in the sandbox. A security analysis (25 claims adversarially verified, 22 confirmed) says no.

## Decisions

### D1. Live decode runs in-process; the codec-RCE vector is structurally absent

CameraX `ImageAnalysis` emits already-decoded raw sensor pixels (`YUV_420_888` by default, `RGBA_8888` opt-in). ZXing's `PlanarYUVLuminanceSource` consumes only the planar Y-luminance `byte[]` with crop, window and rotate array operations and performs no JPEG / PNG / WebP / GIF / Skia container parsing (pure `System.arraycopy` and indexing, no codec imports). No native still-image codec is on the raw-YUV path: the worst vector that justified isolating the static path is not mitigated on the live path, it is never invoked.

What the live path adds is `com.google.zxing:core`, Apache-2.0 and 100% JVM, which brings only pure-JVM logic-bug and DoS risk (GC pressure on continuous scan), never native memory-safety risk. The documented ZXing memory-corruption CVEs (CVE-2021-28021 / -42715 / -42716) are in the C++ `zxing-cpp` / `stb_image.h` static-container path, not the JVM YUV class. The live decode's risk is identical in kind to the static path's pure decode layer, which additionally carries the codec surface; isolating a native-free computation buys nothing.

### D2. The `passes-isolation` transport cannot stream frames

`passes-isolation` is a one-shot `fromBytes(ByteArray)` materialized over a synchronous, blocking `transact()` (ADR 0005 D3, F.1), built to ferry one self-contained document in and return one result. It cannot stream roughly 30 fps camera frames. The sandbox is unnecessary (no codec to contain) and unsuitable (cannot stream) for live decode; forcing the live path through it would degrade the feature without containment.

### D3. The static-image path stays isolated

Any path that parses an attacker-controlled compressed container still routes through the isolated `BarcodeImageDecoder`. A "scan from a saved photo" affordance must use that isolated still-image decode and must not add an in-process codec call: `ImageCapture` yields a compressed JPEG, and decoding any saved photo routes bytes back through a still-image codec, reintroducing the surface D1 avoids. In-process placement is a property of the raw-YUV `ImageAnalysis` path, not of "barcode decode" generally.

### D4. The kernel decode core stays pure-JVM and declares no CAMERA permission

Both paths call one implementation: `decodeLuminance(LuminanceSource): BarcodeDecodeResult` in `passes-barcode-core`, with the symbology allowlist (`ROSTER_BY_ZXING_FORMAT`, `DECODE_HINTS`). The static path feeds it an `RGBLuminanceSource` from a decoded `Bitmap`; the live path feeds it a `PlanarYUVLuminanceSource` through `decodeYPlane(...)` (Y-plane `byte[]` plus width, height, rowStride, pixelStride, reverseHorizontal). The kernel takes no CameraX dependency and declares no `android.permission.CAMERA`. The consumer owns the `ImageAnalysis` pipeline, preview UI, the runtime permission request, lifecycle, foreground-only discipline, the confirmation sheet, and routing the payload through `QrPayloadClassifier` and `ScannableCardInputValidator`.

## Consequences

- Live scanning is a new in-process seam; the `BarcodeImageDecoder` facade, the memfd transport and the sandbox do not extend to live frames.
- The new cost of live scanning is the consumer-declared `CAMERA` permission, a privacy and capability consideration and a product decision, not an RCE vector. Card material (DPAN / LUK) stays Keystore-protected and is unreachable from the camera / ZXing path by any memory-corruption route. Platform controls bound the permission (Android 11+ one-time grants, Android 12+ privacy indicator), but "the OS structurally bars background camera for a compromised app" was refuted in the analysis and must not be relied on; foreground-only use is a self-imposed discipline.
- Net: live scanning does not meaningfully enlarge the exploitable surface and is safer than the static path on the catastrophic-RCE axis, because it omits the native codec. This ADR is the canonical answer to "why isn't the camera decode sandboxed like the image decode?"

## Load-bearing constraints

1. **Raw-YUV `ImageAnalysis` only.** Never `ImageCapture` / JPEG on the in-process path (D3). Violating this silently reintroduces the native codec surface in the unsandboxed process.
2. **Faithful payload return, no auto-act.** The decode layer emits only `{payload, ScannableFormat}` (`BarcodeDecodeResult`). A malicious decoded URL or crafted string is the same threat for static and live capture and is mitigated downstream by classification, validation and explicit user confirmation, never in the capture layer.
3. **Foreground-only camera use** (self-imposed).
4. **Pure-JVM ZXing, not ML Kit.** Even bundled ML Kit is closed source and fails the auditability posture of the degoogled audience.

## Open questions

- Resolved: OEM `ImageAnalysis` format: the "no codec" claim holds across the target device classes (Pixel/GrapheneOS `FULL`/`LEVEL_3`, degoogled `LIMITED`+, stock OEM, `LEGACY` tail). `ImageAnalysis` delivers raw `YUV_420_888`; the still-image codec is a separate camera2 output (`ImageFormat.JPEG`/HEIC) consumed only by `ImageCapture`, which the live path does not bind. The only cross-HAL variability is plane geometry (`rowStride` padding, `pixelStride` interleave, ZXing #1387), which `decodeYPlane` already absorbs. A `LEGACY`-only one-pixel luminance shift in CameraX's RGB-to-YUV conversion is a quality artifact within binarizer tolerance. Consumer constraint: keep the default `YUV_420_888` and feed `getPlanes()[0]`; never feed an `RGBA_8888` plane into `decodeYPlane`. On-device per-model confirmation is the consumer's.
- The payload classification and user-confirmation contract (URL allowlisting, scheme limits, B3-style sheet) is the consumer's responsibility and applies equally to static and live capture.
