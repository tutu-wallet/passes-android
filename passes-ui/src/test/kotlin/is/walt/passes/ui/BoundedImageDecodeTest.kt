package `is`.walt.passes.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ParserConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** The refusals of the display decode. The fit into the bounds is `passes-image-decode`'s. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BoundedImageDecodeTest {

    @Test
    fun sourceCeilingIs4096SquareAndMatchesTheParserDefault() {
        assertThat(MAX_SOURCE_PIXELS).isEqualTo(16_777_216L)
        assertThat(MAX_SOURCE_PIXELS).isEqualTo(ParserConfig.DEFAULT_MAX_IMAGE_PIXEL_COUNT.toLong())
    }

    @Test
    fun interlacedCeilingIsTheAreaOfTheDefaultBounds() {
        val default = ImageRenderBounds.Default
        assertThat(MAX_INTERLACED_SOURCE_PIXELS).isEqualTo(3_686_400L)
        assertThat(MAX_INTERLACED_SOURCE_PIXELS).isEqualTo(default.maxWidthPx.toLong() * default.maxHeightPx)
    }

    @Test
    fun bytesThatAreNotAPngAreRefusedFromTheBytes() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(64)
        val webp = "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        val heif = ByteArray(4) + "ftypheic".toByteArray(Charsets.US_ASCII) + ByteArray(64)

        for (bytes in listOf(jpeg, gif, webp, heif, ByteArray(64), ByteArray(0), pngHeader(8, 8).copyOf(16))) {
            assertThat(pngRejection(bytes)).isEqualTo(ImageDecodeRejection.NotPng)
        }
    }

    @Test
    fun pngAtTheCeilingIsAcceptedFromTheBytes() {
        assertThat(pngRejection(pngHeader(width = 4096, height = 4096))).isNull()
        assertThat(pngRejection(pngHeader(width = 16_777_216, height = 1))).isNull()
    }

    @Test
    fun pngOverTheCeilingIsRefusedFromTheBytes() {
        assertThat(pngRejection(pngHeader(width = 4097, height = 4096))).isEqualTo(ImageDecodeRejection.ExceedsArea)
        // Sides are unsigned 32-bit: -1 reads as 4294967295.
        assertThat(pngRejection(pngHeader(width = -1, height = -1))).isEqualTo(ImageDecodeRejection.ExceedsArea)
    }

    @Test
    fun interlacedPngIsHeldToTheLowerCeiling() {
        assertThat(pngRejection(pngHeader(width = 1920, height = 1920, interlaced = true))).isNull()
        assertThat(pngRejection(pngHeader(width = 1921, height = 1920, interlaced = true)))
            .isEqualTo(ImageDecodeRejection.ExceedsArea)
        assertThat(pngRejection(pngHeader(width = 1921, height = 1920))).isNull()
    }

    @Test
    fun theDecodeReturnsTheRefusalReadFromTheBytes() {
        // Under the main ceiling, and the platform decoder cannot see interlacing: only the byte check refuses this.
        val interlaced = pngHeader(width = 1921, height = 1920, interlaced = true)

        val (bitmap, rejection) = decodeBoundedBitmap(interlaced, ImageRenderBounds.Default)

        assertThat(bitmap).isNull()
        assertThat(rejection).isEqualTo(ImageDecodeRejection.ExceedsArea)
    }

    @Test
    fun platformGateAcceptsOnlyAPngAtOrUnderTheCeiling() {
        assertThat(sourceRejection(mimeType = "image/png", width = 4096, height = 4096)).isNull()
        assertThat(sourceRejection(mimeType = "image/png", width = 16_777_216, height = 1)).isNull()
    }

    @Test
    fun platformGateRefusesEveryOtherContainer() {
        val others = listOf("image/jpeg", "image/webp", "image/gif", "image/heif", "image/avif", "IMAGE/PNG", "", null)
        for (mimeType in others) {
            assertThat(sourceRejection(mimeType, width = 8, height = 8)).isEqualTo(ImageDecodeRejection.NotPng)
        }
    }

    @Test
    fun platformGateRefusesASourceOverTheCeiling() {
        assertThat(sourceRejection(mimeType = "image/png", width = 4097, height = 4096))
            .isEqualTo(ImageDecodeRejection.ExceedsArea)
        assertThat(sourceRejection(mimeType = "image/png", width = Int.MAX_VALUE, height = Int.MAX_VALUE))
            .isEqualTo(ImageDecodeRejection.ExceedsArea)
    }

    // A PNG signature and IHDR chunk with nothing after them: all the byte-level check reads.
    private fun pngHeader(width: Int, height: Int, interlaced: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
                writeInt(13)
                write("IHDR".toByteArray(Charsets.US_ASCII))
                writeInt(width)
                writeInt(height)
                write(byteArrayOf(8, 0, 0, 0, if (interlaced) 1 else 0))
            }
        }.toByteArray()
}
