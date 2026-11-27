package `is`.walt.passes.document.ui

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Method

/**
 * Pins the parameter shape of the trust-claim-bearing document composables (ADR 0005 D5 / D8).
 * Uses Java reflection: Kotlin reflection does not see top-level `@Composable` functions cleanly.
 */
class DocumentSurfaceLockTest {

    @Test
    fun documentViewHasExactlyTenUserVisibleParameters() {
        // (doc, pdfFile, renderer, imageFile, imageDecoder, modifier, telemetry, onOpenFullScreen,
        // fullScreenAffordance, faceTint). One that hid the page or image, or added share-out, breaches D5/D8.
        assertUserVisibleParamCount("DocumentViewKt", "DocumentView", expected = 10)
    }

    @Test
    fun documentViewConsumesImageDecodeBinderInterfaceNotConcreteClient() {
        // The image arm's contract takes the binder interface so test fakes inject cleanly,
        // exactly as the PDF arm takes PdfRendererBinder. A regression to the concrete
        // ImageDecodeClient would make every hosting test bind a real decode service.
        val method = findComposable("DocumentViewKt", "DocumentView")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("DocumentView must accept the ImageDecodeBinder interface")
            .that(typeNames)
            .contains("ImageDecodeBinder")
        assertWithMessage("DocumentView must NOT bind to the concrete ImageDecodeClient")
            .that(typeNames)
            .doesNotContain("ImageDecodeClient")
    }

    @Test
    fun documentViewConsumesPdfRendererBinderInterfaceNotConcreteClient() {
        // The DocumentView contract takes the binder interface so test fakes inject
        // cleanly. A regression to the concrete `PdfRendererClient` would make every
        // hosting test bind a real service. Lock the type by simple name.
        val method = findComposable("DocumentViewKt", "DocumentView")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("DocumentView must accept the PdfRendererBinder interface")
            .that(typeNames)
            .contains("PdfRendererBinder")
        assertWithMessage("DocumentView must NOT bind to the concrete PdfRendererClient")
            .that(typeNames)
            .doesNotContain("PdfRendererClient")
    }

    @Test
    fun fullScreenDocumentViewHasExactlyTenUserVisibleParameters() {
        // (doc, pdfFile, renderer, imageFile, imageDecoder, onClose, modifier, telemetry,
        // initialPage, closeButton). One that added share-out or extraction breaches D4/D8.
        assertUserVisibleParamCount(
            "FullScreenDocumentViewKt",
            "FullScreenDocumentView",
            expected = 10,
        )
    }

    @Test
    fun fullScreenDocumentViewConsumesImageDecodeBinderInterfaceNotConcreteClient() {
        // Same contract as DocumentView's image arm: the full-screen surface takes the
        // binder interface so test fakes inject cleanly.
        val method = findComposable("FullScreenDocumentViewKt", "FullScreenDocumentView")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("FullScreenDocumentView must accept the ImageDecodeBinder interface")
            .that(typeNames)
            .contains("ImageDecodeBinder")
        assertWithMessage("FullScreenDocumentView must NOT bind to the concrete ImageDecodeClient")
            .that(typeNames)
            .doesNotContain("ImageDecodeClient")
    }

    @Test
    fun fullScreenDocumentViewConsumesPdfRendererBinderInterfaceNotConcreteClient() {
        // Same contract as DocumentView: the full-screen surface takes the binder
        // interface so test fakes inject cleanly.
        val method = findComposable("FullScreenDocumentViewKt", "FullScreenDocumentView")
        val typeNames = method.parameterTypes.map { it.simpleName }
        assertWithMessage("FullScreenDocumentView must accept the PdfRendererBinder interface")
            .that(typeNames)
            .contains("PdfRendererBinder")
        assertWithMessage("FullScreenDocumentView must NOT bind to the concrete PdfRendererClient")
            .that(typeNames)
            .doesNotContain("PdfRendererClient")
    }

    @Test
    fun documentSurfacesHaveNoOverloads() {
        // An overload could add a parameter that the count locks miss.
        listOf(
            "DocumentViewKt" to "DocumentView",
            "FullScreenDocumentViewKt" to "FullScreenDocumentView",
        ).forEach { (file, name) ->
            val klass = Class.forName("is.walt.passes.document.ui.$file")
            val matches = klass.declaredComposableOverloads(name)
            assertWithMessage("$name should have exactly one declared overload")
                .that(matches.size)
                .isEqualTo(1)
        }
    }

    // -- helpers -------------------------------------------------------------------

    private fun findComposable(fileClassSimpleName: String, methodName: String): Method {
        val klass = Class.forName("is.walt.passes.document.ui.$fileClassSimpleName")
        return klass.declaredComposableOverloads(methodName)
            .maxByOrNull { it.parameterCount }
            ?: error("Composable $fileClassSimpleName.$methodName not found")
    }

    /**
     * Source-declared overloads of [methodName]. The `-<hash>` suffix is Kotlin's
     * value-class name mangling (`DocumentView` takes a `Color`); the synthetic filter
     * drops the `$default` bridge that mangling would otherwise smuggle in under that
     * same prefix, with an inflated parameter count.
     */
    private fun Class<*>.declaredComposableOverloads(methodName: String): List<Method> =
        methods.filter {
            (it.name == methodName || it.name.startsWith("$methodName-")) && !it.isSynthetic
        }

    private fun userVisibleParameterCount(method: Method): Int {
        // Compose appends `Composer $composer`, then `int $changed` / `$default` masks.
        // Everything before the Composer is user-visible, including a trailing Int param.
        val composerIndex = method.parameterTypes
            .indexOfFirst { it.name == "androidx.compose.runtime.Composer" }
        check(composerIndex >= 0) { "No Composer parameter on $method" }
        return composerIndex
    }

    private fun assertUserVisibleParamCount(
        fileClassSimpleName: String,
        methodName: String,
        expected: Int,
    ) {
        val method = findComposable(fileClassSimpleName, methodName)
        val actual = userVisibleParameterCount(method)
        assertWithMessage(
            "$methodName user-visible parameter count drifted; review ADR 0005 D5/D8 " +
                "before changing this number. Full method signature: $method",
        )
            .that(actual)
            .isEqualTo(expected)
    }
}
