package `is`.walt.passes.document.ui

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.document.BarcodedImageDocument
import `is`.walt.passes.document.BarcodedImageDocumentId
import `is`.walt.passes.document.Document
import `is`.walt.passes.document.ImageDocument
import `is`.walt.passes.document.ImageDocumentId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** The image and composite arms compose on both document surfaces, each with its way in or out. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DocumentArmCompositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val image = ImageDocument(
        id = ImageDocumentId("img"),
        displayLabel = "boarding.png",
        byteCount = 2048L,
        widthPx = 1080,
        heightPx = 2340,
        importedAtEpochMs = 0L,
    )

    // Synthetic, non-PII payload.
    private val composite = BarcodedImageDocument(
        id = BarcodedImageDocumentId("comp"),
        displayLabel = "boarding.png",
        byteCount = 2048L,
        widthPx = 1080,
        heightPx = 2340,
        barcodePayload = "TEST-PAYLOAD-000",
        barcodeFormat = ScannableFormat.Aztec,
        importedAtEpochMs = 0L,
    )

    @Test
    fun inlineImageArmComposesWithItsFullScreenEntry() = assertInlineViewOffersFullScreen(image)

    @Test
    fun inlineCompositeArmComposesWithItsFullScreenEntry() = assertInlineViewOffersFullScreen(composite)

    @Test
    fun fullScreenImageArmComposesWithItsCloseControl() = assertFullScreenViewCloses(image)

    @Test
    fun fullScreenCompositeArmComposesWithItsCloseControl() = assertFullScreenViewCloses(composite)

    private fun assertInlineViewOffersFullScreen(doc: Document) {
        withTempPfd { pfd ->
            composeRule.setContent {
                ThemedHost {
                    DocumentView(
                        doc = doc,
                        imageFile = pfd,
                        imageDecoder = RejectingImageDecoder,
                        onOpenFullScreen = {},
                    )
                }
            }
            composeRule.onNodeWithText(testDocumentSemantics.fullScreenBannerLabel).assertIsDisplayed()
        }
    }

    // The decoder rejects, so the surface composes with no raster: the close control must still work.
    private fun assertFullScreenViewCloses(doc: Document) {
        var closed = 0
        withTempPfd { pfd ->
            composeRule.setContent {
                ThemedHost {
                    FullScreenDocumentView(
                        doc = doc,
                        imageFile = pfd,
                        imageDecoder = RejectingImageDecoder,
                        onClose = { closed++ },
                    )
                }
            }
            composeRule.onNodeWithText(testDocumentSemantics.closeFullScreenLabel).performClick()
            composeRule.waitForIdle()
        }
        assertThat(closed).isEqualTo(1)
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
