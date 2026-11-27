package `is`.walt.passes.core.shrunkprobe

import `is`.walt.passes.core.ParseResult
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.core.PassParser
import `is`.walt.passes.core.PassSource
import `is`.walt.passes.core.internal.SignatureVerifyResult
import `is`.walt.passes.core.internal.verifySignature

/**
 * Stands in for a consuming app in the R8 run of `:passes-core:shrinkProbe`: the only kept
 * entry point, so the kernel is shrunk and renamed exactly as far as its own rules allow.
 * Verdicts are spelled out as literals because class names do not survive renaming.
 */
public object ShrunkProbe {
    /** Parses a whole pkpass through the public parser with the default config. */
    @JvmStatic
    public fun parse(pkpass: ByteArray): String =
        when (val result = PassParser.create().parse(PassSource.Bytes(pkpass))) {
            is ParseResult.Success -> "Success(${result.signatureStatus})"
            is ParseResult.Tampered -> "Tampered(${result.reason})"
            is ParseResult.Malformed -> "Malformed"
            is ParseResult.Unsupported -> "Unsupported"
        }

    /** Verifies a detached signature against the bundled Apple anchors. */
    @JvmStatic
    public fun verifyDetached(
        signature: ByteArray,
        manifest: ByteArray,
    ): String =
        when (val result = verifySignature(signature, manifest, ParserConfig())) {
            is SignatureVerifyResult.Ok -> "Ok(${result.status})"
            is SignatureVerifyResult.Failed -> "Failed(${result.reason})"
        }
}
