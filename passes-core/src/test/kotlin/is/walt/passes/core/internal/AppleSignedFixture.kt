package `is`.walt.passes.core.internal

/**
 * Manifest and detached signature bytes from a real Apple-signed pkpass; see
 * `fixtures/apple-signed/README.md`. Named fields so the two arrays cannot be transposed.
 */
internal class AppleSignedFixture(
    val manifest: ByteArray,
    val signature: ByteArray,
)

internal fun loadAppleSignedFixture(): AppleSignedFixture {
    fun read(name: String): ByteArray =
        AppleSignedFixture::class.java.getResourceAsStream("fixtures/apple-signed/$name")?.use { it.readBytes() }
            ?: error("Fixture missing: fixtures/apple-signed/$name")
    return AppleSignedFixture(manifest = read("manifest.json"), signature = read("signature"))
}
