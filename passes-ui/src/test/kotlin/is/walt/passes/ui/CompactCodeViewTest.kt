package `is`.walt.passes.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ScannableFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A valid code renders an image carrying the caller's description; an encoder rejection renders the shared
 * "Barcode failed to render" placeholder instead of throwing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CompactCodeViewTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validQrPayloadRendersImageWithCallerDescription() {
        composeRule.setContent {
            CompactCodeView(
                payload = "LOCKER-0042",
                format = ScannableFormat.Qr,
                modifier = Modifier.size(90.dp),
                contentDescription = "Locker code, QR code",
            )
        }
        composeRule.onNodeWithContentDescription("Locker code, QR code").assertIsDisplayed()
    }

    @Test
    fun validCode128PayloadRendersImageWithCallerDescription() {
        composeRule.setContent {
            CompactCodeView(
                payload = "21000456782",
                format = ScannableFormat.Code128,
                modifier = Modifier.size(width = 280.dp, height = 56.dp),
                contentDescription = "Library card, Code 128",
            )
        }
        composeRule.onNodeWithContentDescription("Library card, Code 128").assertIsDisplayed()
    }

    @Test
    fun backingIsLiterallyWhiteNotAThemeToken() {
        // The white backing is a blessed-path guarantee (dark-mode scannability); a
        // refactor to a theme surface token must fail here, not in the field. Pixel
        // capture is unavailable under this Robolectric setup, so the pin is the
        // internal constant the backing modifier routes through.
        assertThat(COMPACT_CODE_BACKING).isEqualTo(Color.White)
    }

    @Test
    fun encoderRejectionRendersFailurePlaceholderNotCrash() {
        composeRule.setContent {
            // EAN-13 requires 12-13 digits; "12" is rejected by the writer, exercising
            // the EncodeResult.Failure arm.
            CompactCodeView(
                payload = "12",
                format = ScannableFormat.Ean13,
                modifier = Modifier.size(width = 280.dp, height = 56.dp),
            )
        }
        composeRule.onNodeWithContentDescription("Barcode failed to render").assertIsDisplayed()
    }
}
