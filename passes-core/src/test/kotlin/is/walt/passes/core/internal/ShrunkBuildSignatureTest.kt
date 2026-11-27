package `is`.walt.passes.core.internal

import com.google.common.truth.Truth.assertThat
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.net.URLClassLoader
import java.security.Security

/**
 * Signature verification inside the R8 output of `:passes-core:r8ShrunkProbe`, on a desktop JVM.
 * Fixtures are built here and cross as bytes, so this class keeps no kernel code alive there.
 */
class ShrunkBuildSignatureTest {
    @Test
    fun onlyTheKeptKernelClassesKeepTheirNames() {
        // Guards the harness: a run that stopped renaming, or a keep rule that pins kernel
        // class names, shows up here.
        val kernelRoot = shrunkClasses.resolve("is/walt/passes/core")
        val named =
            kernelRoot.walkTopDown()
                .filter { it.extension == "class" }
                .map { it.relativeTo(kernelRoot).invariantSeparatorsPath }
                .toList()

        assertThat(named).containsExactly("internal/AppleTrustAnchors.class", "shrunkprobe/ShrunkProbe.class")
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
    fun ecdsaSignedPassImports() {
        val key = newEcKeyPair()
        val leaf = selfSignedCertificate(key, "CN=EC Signer")
        val pkpass =
            SyntheticPkpass.signedBy(PASS_JSON) { manifest ->
                cmsDetachedSignature(manifest, leaf, key.private, listOf(leaf))
            }

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
        // The fixture helpers register the unshrunk BouncyCastle as "BC" for the whole JVM;
        // take it out so a lookup by name inside the shrunk copy could not borrow it.
        Security.removeProvider("BC")
        val parameterTypes = Array(arguments.size) { ByteArray::class.java }
        return shrunk.loadClass(PROBE_CLASS).getMethod(method, *parameterTypes).invoke(null, *arguments) as String
    }

    companion object {
        private const val SHRUNK_CLASSES_PROPERTY = "passes.shrunkClasses"
        private const val PROBE_CLASS = "is.walt.passes.core.shrunkprobe.ShrunkProbe"
        private val PASS_JSON = SyntheticPkpass.minimalPassJson("eventTicket")

        private lateinit var shrunkClasses: File
        private lateinit var shrunk: URLClassLoader

        @BeforeClass
        @JvmStatic
        fun loadShrunkClasses() {
            val path =
                checkNotNull(System.getProperty(SHRUNK_CLASSES_PROPERTY)) {
                    "Run this class through :passes-core:shrunkTest, which sets $SHRUNK_CLASSES_PROPERTY."
                }
            shrunkClasses = File(path)
            // The platform loader as parent keeps the unshrunk test classpath out of reach.
            shrunk = URLClassLoader(arrayOf(shrunkClasses.toURI().toURL()), ClassLoader.getPlatformClassLoader())
        }

        @AfterClass
        @JvmStatic
        fun closeShrunkClasses() {
            if (::shrunk.isInitialized) shrunk.close()
        }
    }
}
