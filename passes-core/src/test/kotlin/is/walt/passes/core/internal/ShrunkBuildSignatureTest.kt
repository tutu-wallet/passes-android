package `is`.walt.passes.core.internal

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

/**
 * Signature verification inside the output of `:passes-core:shrinkProbe`: the kernel, its
 * dependencies and a probe, shrunk and renamed by R8 under the kernel's embedded rules alone.
 * Runs in `:passes-core:shrunkTest`, which builds that output. Fixtures are made out here and
 * cross as bytes, so nothing in this class keeps kernel code alive in the shrunk copy.
 */
class ShrunkBuildSignatureTest {
    @Test
    fun kernelClassesAreRenamedInTheShrunkOutput() {
        // Guards the harness: an unrenamed copy would not exercise lookups by class name.
        assertThrows(ClassNotFoundException::class.java) {
            shrunk.loadClass("is.walt.passes.core.internal.DefaultPassParser")
        }
    }

    @Test
    fun appleSignedFixtureReachesTheBundledAnchor() {
        val fixture = loadAppleSignedFixture()

        assertThat(verifyDetached(fixture.signature, fixture.manifest)).isEqualTo("Ok(AppleVerified)")
    }

    @Test
    fun derSignedPassImports() {
        val pkpass = SyntheticPkpass.signedSelfSigned(PASS_JSON)

        assertThat(parse(pkpass)).isEqualTo("Success(SelfSigned)")
    }

    @Test
    fun wireOrderSignedPassImports() {
        val key = newRsaKeyPair()
        val leaf = selfSignedCertificate(key, "CN=Wire Order Signer")
        val pkpass =
            SyntheticPkpass.signedBy(PASS_JSON) { manifest ->
                cmsDetachedSignatureWithUnsortedSignedAttrs(manifest, leaf, key.private, listOf(leaf))
            }

        assertThat(parse(pkpass)).isEqualTo("Success(SelfSigned)")
    }

    @Test
    fun wireOrderPassSignedByAnotherKeyIsAMismatch() {
        // Reaches the fallback's own signature check. A fallback that could not run there
        // would report SignatureCryptoFailure.
        val leaf = selfSignedCertificate(newRsaKeyPair(), "CN=Named Signer")
        val otherKey = newRsaKeyPair()
        val pkpass =
            SyntheticPkpass.signedBy(PASS_JSON) { manifest ->
                cmsDetachedSignatureWithUnsortedSignedAttrs(manifest, leaf, otherKey.private, listOf(leaf))
            }

        assertThat(parse(pkpass)).isEqualTo("Tampered(ManifestSignatureMismatch)")
    }

    private fun parse(pkpass: ByteArray): String = probe("parse", pkpass)

    private fun verifyDetached(
        signature: ByteArray,
        manifest: ByteArray,
    ): String = probe("verifyDetached", signature, manifest)

    private fun probe(
        method: String,
        vararg arguments: ByteArray,
    ): String {
        val parameterTypes = Array(arguments.size) { ByteArray::class.java }
        return shrunk.loadClass(PROBE_CLASS).getMethod(method, *parameterTypes).invoke(null, *arguments) as String
    }

    companion object {
        private const val SHRUNK_CLASSES_PROPERTY = "passes.shrunkClasses"
        private const val PROBE_CLASS = "is.walt.passes.core.shrunkprobe.ShrunkProbe"
        private val PASS_JSON = SyntheticPkpass.minimalPassJson("eventTicket")

        private lateinit var shrunk: ClassLoader

        @BeforeClass
        @JvmStatic
        fun loadShrunkClasses() {
            val path =
                checkNotNull(System.getProperty(SHRUNK_CLASSES_PROPERTY)) {
                    "Run this class through :passes-core:shrunkTest, which sets $SHRUNK_CLASSES_PROPERTY."
                }
            // The platform loader as parent keeps the unshrunk test classpath out of reach.
            shrunk = URLClassLoader(arrayOf(File(path).toURI().toURL()), ClassLoader.getPlatformClassLoader())
        }
    }
}
