package `is`.walt.passes.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** The display decode against the platform `ImageDecoder`, which Robolectric only stubs. */
@RunWith(AndroidJUnit4::class)
class BoundedImageDecodeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourceWiderThanTheBoxIsDownsampledNotRejected() {
        val (bitmap, rejection) = decodeBoundedBitmap(pngOf(width = 2000, height = 100), ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(1000)
        assertThat(bitmap.height).isEqualTo(50)
    }

    @Test
    fun sourceInsideTheBoxKeepsItsSize() {
        val (bitmap, rejection) = decodeBoundedBitmap(pngOf(width = 360, height = 220), ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(360)
        assertThat(bitmap.height).isEqualTo(220)
    }

    @Test
    fun tileBoundsDecodeATileSizedBitmap() {
        val tile = ImageRenderBounds(maxWidthPx = 200, maxHeightPx = 300)

        val (bitmap, rejection) = decodeBoundedBitmap(pngOf(width = 1000, height = 500), tile)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(200)
        assertThat(bitmap.height).isEqualTo(100)
    }

    @Test
    fun extremeAspectKeepsAtLeastOnePixel() {
        val (bitmap, rejection) = decodeBoundedBitmap(pngOf(width = 4000, height = 1), ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(1333)
        assertThat(bitmap.height).isEqualTo(1)
    }

    @Test
    fun sourceAtTheCeilingDecodesIntoTheBox() {
        val (bitmap, rejection) = decodeBoundedBitmap(pngOf(width = 4096, height = 4096), ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(1365)
        assertThat(bitmap.height).isEqualTo(1365)
    }

    @Test
    fun sourceOverTheCeilingIsRejectedWhateverTheBounds() {
        val png = pngOf(width = 4097, height = 4096)
        val bounds =
            listOf(
                ImageRenderBounds(maxWidthPx = 200, maxHeightPx = 300),
                ImageRenderBounds.Default,
                ImageRenderBounds(maxWidthPx = 8192, maxHeightPx = 8192),
            )

        for (each in bounds) {
            val (bitmap, rejection) = decodeBoundedBitmap(png, each)

            assertThat(bitmap).isNull()
            assertThat(rejection).isEqualTo(ImageDecodeRejection.ExceedsArea)
        }
    }

    @Test
    fun undecodableBytesAreMalformed() {
        val garbage = ByteArray(256) { (it * 37 + 13).toByte() }

        val (bitmap, rejection) = decodeBoundedBitmap(garbage, ImageRenderBounds.Default)

        assertThat(bitmap).isNull()
        assertThat(rejection).isEqualTo(ImageDecodeRejection.Malformed)
    }

    @Test
    fun oversizedImageDrawsAndReportsNoRejection() {
        val rejections = CopyOnWriteArrayList<ImageDecodeRejection>()
        val png = pngOf(width = 2000, height = 100)
        composeRule.setContent { TestImage(png, rejections) }

        composeRule.waitUntil(DECODE_TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription(DESCRIPTION).fetchSemanticsNodes().isNotEmpty()
        }
        assertThat(rejections).isEmpty()
    }

    @Test
    fun refusedImageDrawsNothingAndReportsOnce() {
        val rejections = CopyOnWriteArrayList<ImageDecodeRejection>()
        val png = pngOf(width = 4097, height = 4096)
        composeRule.setContent { TestImage(png, rejections) }

        composeRule.waitUntil(DECODE_TIMEOUT_MS) { rejections.isNotEmpty() }
        composeRule.waitForIdle()
        assertThat(rejections).containsExactly(ImageDecodeRejection.ExceedsArea)
        composeRule.onAllNodesWithContentDescription(DESCRIPTION).fetchSemanticsNodes().let {
            assertThat(it).isEmpty()
        }
    }

    @Composable
    private fun TestImage(png: ByteArray, rejections: MutableList<ImageDecodeRejection>) {
        BoundedImage(
            bytes = ImageBytes(png),
            role = ImageRole.Logo,
            contentDescription = DESCRIPTION,
            telemetry =
                object : UiTelemetryGuard by NoopUiTelemetryGuard {
                    override fun onImageDecodeRejected(reason: ImageDecodeRejection) {
                        rejections += reason
                    }
                },
            modifier = Modifier.size(48.dp),
        )
    }

    // A valid all-black greyscale PNG, streamed so a 16 MP fixture costs the test a few kilobytes.
    private fun pngOf(width: Int, height: Int): ByteArray {
        val header =
            ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeInt(width)
                    writeInt(height)
                    write(byteArrayOf(8, 0, 0, 0, 0))
                }
            }.toByteArray()
        val pixels =
            ByteArrayOutputStream().also { out ->
                DeflaterOutputStream(out).use { deflater ->
                    val row = ByteArray(width + 1)
                    repeat(height) { deflater.write(row) }
                }
            }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            out.write(PNG_SIGNATURE)
            out.writeChunk("IHDR", header)
            out.writeChunk("IDAT", pixels)
            out.writeChunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        val typed = type.toByteArray(Charsets.US_ASCII) + data
        DataOutputStream(this).apply {
            writeInt(data.size)
            write(typed)
            writeInt(CRC32().apply { update(typed) }.value.toInt())
        }
    }

    private companion object {
        const val DESCRIPTION = "logo"
        const val DECODE_TIMEOUT_MS = 10_000L
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
