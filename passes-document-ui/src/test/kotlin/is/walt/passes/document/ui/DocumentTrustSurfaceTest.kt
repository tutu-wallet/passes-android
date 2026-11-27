package `is`.walt.passes.document.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.document.BarcodedImageDocument
import `is`.walt.passes.document.BarcodedImageDocumentId
import `is`.walt.passes.document.Document
import `is`.walt.passes.document.ImageDocument
import `is`.walt.passes.document.ImageDocumentId
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.document.PdfDocumentId
import `is`.walt.passes.ui.core.ArgbColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Smoke tests for the trust-claim-bearing document surfaces: caption wording and where it renders. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentTrustSurfaceTest {

    @get:Rule
    val composeRule = createComposeRule()

    // A distinct, non-default icon tint so the icon+text caption is exercised off the
    // captionForeground default.
    private val semantics = testDocumentSemantics.copy(captionIconTint = ArgbColor(0xFFFFAA00.toInt()))

    @Test
    fun documentTrustCaptionRendersTheVerbatimTrustText() {
        // The non-suppressible caption is the trust contract for documents (ADR 0005
        // D5: PDFs are never signature-verified). The visible string IS the audit
        // surface; locking it here means a contributor cannot soften the wording
        // without updating the test.
        composeRule.setContent {
            ThemedHost(semantics) {
                DocumentTrustCaption()
            }
        }
        composeRule.onNodeWithText(
            "User-provided document. This app has not verified the source.",
        ).assertIsDisplayed()
    }

    @Test
    fun documentTrustCaptionTextConstantMatchesDisplayed() {
        // Belt-and-suspenders to the previous test: the displayed string MUST be the
        // exact value of the internal constant. This catches a refactor that splits
        // the caption into multiple Text composables (visible-text assertion would
        // pass; constant-equality breaks).
        assertThat(TRUST_CAPTION_TEXT)
            .isEqualTo("User-provided document. This app has not verified the source.")
    }

    @Test
    fun fullScreenPdfDocumentRendersTheNonSuppressibleTrustCaption() {
        // The renderer rejects every page; the caption must display all the same.
        val doc = PdfDocument(
            id = PdfDocumentId("pdf-fs"),
            displayLabel = "ticket.pdf",
            byteCount = 1024L,
            pageCount = 2,
            importedAtEpochMs = 0L,
        )
        withTempPfd { pfd ->
            composeRule.setContent {
                ThemedHost(semantics) {
                    FullScreenDocumentView(
                        doc = doc,
                        pdfFile = pfd,
                        renderer = RecordingRenderer(pageCount = 2),
                        onClose = {},
                    )
                }
            }
            composeRule.onNodeWithText(
                "User-provided document. This app has not verified the source.",
            ).assertIsDisplayed()
        }
    }

    @Test
    fun fullScreenImageDocumentRendersTheNonSuppressibleTrustCaption() {
        // wpass-pl7.4: the full-screen surface generalized past PDF-only. The image arm
        // composes the same docked caption as the PDF arm — structurally outside the zoom
        // transform — and it shows regardless of decode outcome (a rejecting fake keeps
        // the zoom region empty; the caption must still display).
        val doc = ImageDocument(
            id = ImageDocumentId("img-fs"),
            displayLabel = "boarding.png",
            byteCount = 2048L,
            widthPx = 1080,
            heightPx = 2340,
            importedAtEpochMs = 0L,
        )
        withTempPfd { pfd ->
            composeRule.setContent {
                ThemedHost(semantics) {
                    FullScreenDocumentView(
                        doc = doc,
                        imageFile = pfd,
                        imageDecoder = RejectingImageDecoder,
                        onClose = {},
                    )
                }
            }
            composeRule.onNodeWithText(
                "User-provided document. This app has not verified the source.",
            ).assertIsDisplayed()
        }
    }

    @Test
    fun fullScreenBarcodedImageDocumentRendersTheNonSuppressibleTrustCaption() {
        // wpass-pl7.4: the composite arm routes its retained photo through the same
        // full-screen image surface (wpass-8lu routing), so the docked caption anchors
        // it identically. Payload is a synthetic non-PII fixture (epic constraint 7).
        val doc = BarcodedImageDocument(
            id = BarcodedImageDocumentId("comp-fs"),
            displayLabel = "boarding.png",
            byteCount = 2048L,
            widthPx = 1080,
            heightPx = 2340,
            barcodePayload = "TEST-PAYLOAD-000",
            barcodeFormat = ScannableFormat.Aztec,
            importedAtEpochMs = 0L,
        )
        withTempPfd { pfd ->
            composeRule.setContent {
                ThemedHost(semantics) {
                    FullScreenDocumentView(
                        doc = doc,
                        imageFile = pfd,
                        imageDecoder = RejectingImageDecoder,
                        onClose = {},
                    )
                }
            }
            composeRule.onNodeWithText(
                "User-provided document. This app has not verified the source.",
            ).assertIsDisplayed()
        }
    }

    @Test
    fun inlineImageArmComposesWithItsFullScreenEntry() {
        assertInlineViewOffersFullScreen(
            ImageDocument(
                id = ImageDocumentId("img-inline"),
                displayLabel = "boarding.png",
                byteCount = 2048L,
                widthPx = 1080,
                heightPx = 2340,
                importedAtEpochMs = 0L,
            ),
        )
    }

    @Test
    fun inlineCompositeArmComposesWithItsFullScreenEntry() {
        assertInlineViewOffersFullScreen(
            BarcodedImageDocument(
                id = BarcodedImageDocumentId("comp-inline"),
                displayLabel = "boarding.png",
                byteCount = 2048L,
                widthPx = 1080,
                heightPx = 2340,
                barcodePayload = "TEST-PAYLOAD-000",
                barcodeFormat = ScannableFormat.Aztec,
                importedAtEpochMs = 0L,
            ),
        )
    }

    private fun assertInlineViewOffersFullScreen(doc: Document) {
        withTempPfd { pfd ->
            composeRule.setContent {
                ThemedHost(semantics) {
                    DocumentView(
                        doc = doc,
                        imageFile = pfd,
                        imageDecoder = RejectingImageDecoder,
                        onOpenFullScreen = {},
                    )
                }
            }
            composeRule.onNodeWithText(semantics.fullScreenBannerLabel).assertIsDisplayed()
        }
    }

    private fun withTempPfd(block: (ParcelFileDescriptor) -> Unit) {
        val file = File.createTempFile("walt-image-doc", ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            block(pfd)
        } finally {
            pfd.close()
            file.delete()
        }
    }
}
