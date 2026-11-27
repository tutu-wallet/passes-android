package `is`.walt.passes.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.ImageDecoder
import android.os.Build
import android.os.Debug
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** The display decode against the platform `ImageDecoder`, which Robolectric only stubs. */
@RunWith(AndroidJUnit4::class)
class BoundedImageDecodeInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun sourceWiderThanTheBoxIsDownsampledNotRejected() {
        val (bitmap, rejection) = decoded(pngOf(width = 2000, height = 100), ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(1000)
        assertThat(bitmap.height).isEqualTo(50)
    }

    @Test
    fun sourceInsideTheBoxKeepsItsSize() {
        val (bitmap, rejection) = decoded(pngOf(width = 360, height = 220), ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(360)
        assertThat(bitmap.height).isEqualTo(220)
    }

    @Test
    fun tileBoundsDecodeATileSizedBitmap() {
        val tile = ImageRenderBounds(maxWidthPx = 200, maxHeightPx = 300)

        val (bitmap, rejection) = decoded(pngOf(width = 1000, height = 500), tile)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(200)
        assertThat(bitmap.height).isEqualTo(100)
    }

    @Test
    fun extremeAspectKeepsAtLeastOnePixel() {
        val (bitmap, rejection) = decoded(pngOf(width = 4000, height = 1), ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(1333)
        assertThat(bitmap.height).isEqualTo(1)
    }

    @Test
    fun sourceAtTheCeilingDecodesIntoTheBox() {
        val (bitmap, rejection) = decoded(pngOf(width = 4096, height = 4096), ImageRenderBounds.Default)

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
            val (bitmap, rejection) = decoded(png, each)

            assertThat(bitmap).isNull()
            assertThat(rejection).isEqualTo(ImageDecodeRejection.ExceedsArea)
        }
    }

    @Test
    fun otherContainersUnderAPngRoleAreRefused() {
        val containers =
            listOf(
                compressed(Bitmap.CompressFormat.JPEG),
                @Suppress("DEPRECATION") compressed(Bitmap.CompressFormat.WEBP),
                GIF_1X1,
                ByteArray(256) { (it * 37 + 13).toByte() },
            )

        val outcomes = containers.map { decoded(it, ImageRenderBounds.Default) }

        assertThat(outcomes.map { (bitmap, _) -> bitmap }).containsExactly(null, null, null, null)
        assertThat(outcomes.map { (_, rejection) -> rejection })
            .containsExactly(
                ImageDecodeRejection.NotPng,
                ImageDecodeRejection.NotPng,
                ImageDecodeRejection.NotPng,
                ImageDecodeRejection.NotPng,
            )
    }

    @Test
    fun pngCutOffAfterItsHeaderIsMalformed() {
        val png = pngOf(width = 64, height = 64)

        val (bitmap, rejection) = decoded(png.copyOf(PNG_HEADER_BYTES), ImageRenderBounds.Default)

        assertThat(bitmap).isNull()
        assertThat(rejection).isEqualTo(ImageDecodeRejection.Malformed)
    }

    @Test
    fun compressedTextInAPngCostsTheDecodeNothing() {
        // A keyword, its terminator, compression method 0, then text that is not cut short by a zero byte.
        val text = byteArrayOf('k'.code.toByte(), 0, 0) + deflated(ByteArray(INFLATED_TEXT_BYTES) { 'a'.code.toByte() })
        val chunks = List(4) { "zTXt" to text }
        val png = withChunks(pngOf(width = 1, height = 1), afterHeader = chunks, atTheEnd = chunks)
        val (platform, platformPeak) = peakNativeGrowth { platformDecode(png) }
        platform.recycle()
        // Without this cost on the platform's own decode, the test below would show nothing.
        expectFromApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, platformPeak > 4L * INFLATED_TEXT_BYTES)

        val (decoded, peak) = peakNativeGrowth { decoded(png, ImageRenderBounds.Default) }

        assertThat(decoded.second).isNull()
        assertThat(decoded.first!!.width).isEqualTo(1)
        assertThat(peak).isLessThan(INFLATED_TEXT_BYTES.toLong())
    }

    @Test
    fun aPngDecodesToTheSamePictureWithoutItsMetadata() {
        val metadata = listOf("tEXt" to ByteArray(64), "eXIf" to ByteArray(64))
        val png = withChunks(wideGamutPng(), afterHeader = metadata, atTheEnd = metadata)

        val (bitmap, rejection) = decoded(png, ImageRenderBounds.Default)

        val platform = platformDecode(png)
        // Below this level the fixture is not held to Display P3; the comparison below runs everywhere.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            assertThat(platform.colorSpace).isEqualTo(ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        }
        assertThat(rejection).isNull()
        assertThat(bitmap!!.colorSpace).isEqualTo(platform.colorSpace)
        val pixels = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        assertThat(pixels.sameAs(platform.copy(Bitmap.Config.ARGB_8888, false))).isTrue()
    }

    @Test
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun aGainMapInAPngIsNotRead() {
        val png =
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).let { base ->
                base.gainmap = Gainmap(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
                ByteArrayOutputStream().also { base.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            }
        // Without a gain map on the platform's own decode, the test below would show nothing.
        expectFromApi(GAIN_MAP_IN_PNG_API, platformDecode(png).hasGainmap())

        val (bitmap, rejection) = decoded(png, ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.hasGainmap()).isFalse()
    }

    @Test
    fun interlacedPngDecodesUpToItsLowerCeiling() {
        val (bitmap, rejection) =
            decoded(pngOf(width = 1920, height = 1920, interlaced = true), ImageRenderBounds.Default)

        assertThat(rejection).isNull()
        assertThat(bitmap!!.width).isEqualTo(1920)
        assertThat(bitmap.height).isEqualTo(1920)
    }

    @Test
    fun interlacedPngOverItsLowerCeilingIsRefused() {
        val (bitmap, rejection) =
            decoded(pngOf(width = 1921, height = 1920, interlaced = true), ImageRenderBounds.Default)

        assertThat(bitmap).isNull()
        assertThat(rejection).isEqualTo(ImageDecodeRejection.ExceedsArea)
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

    @Test
    fun otherContainerDrawsNothingAndReportsOnce() {
        val rejections = CopyOnWriteArrayList<ImageDecodeRejection>()
        composeRule.setContent { TestImage(compressed(Bitmap.CompressFormat.JPEG), rejections) }

        composeRule.waitUntil(DECODE_TIMEOUT_MS) { rejections.isNotEmpty() }
        composeRule.waitForIdle()
        assertThat(rejections).containsExactly(ImageDecodeRejection.NotPng)
        composeRule.onAllNodesWithContentDescription(DESCRIPTION).fetchSemanticsNodes().let {
            assertThat(it).isEmpty()
        }
    }

    /** [decodePassImage] as the bitmap or the refusal. */
    private fun decoded(png: ByteArray, bounds: ImageRenderBounds): Pair<Bitmap?, ImageDecodeRejection?> =
        when (val image = decodePassImage(png, bounds)) {
            is PassImage.Decoded -> image.bitmap to null
            is PassImage.Refused -> null to image.reason
        }

    @Test
    fun aStoredDocumentThumbnailDecodesAtTileBounds() {
        // Encoded as the document importers do. 2048 x 1536 is an image thumbnail stored before the 600 x 800 box.
        val tile = ImageRenderBounds(maxWidthPx = 390, maxHeightPx = 504)
        for ((width, height) in listOf(600 to 800, 600 to 450, 2048 to 1536)) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

            val (decoded, rejection) = decoded(png, tile)

            assertThat(rejection).isNull()
            assertThat(decoded!!.width).isAtMost(tile.maxWidthPx)
            assertThat(decoded.height).isAtMost(tile.maxHeightPx)
        }
    }

    @Test
    fun aDecodedBitmapCountsItsPixelsAgainstTheCacheBudget() {
        val png = pngOf(width = 100, height = 100)
        val image = decodePassImage(png, ImageRenderBounds.Default)
        // One byte short of a byte a pixel beside the image bytes, whatever config the platform chose.
        val cache = PassImageCache(maxBytes = 100 * 100 + png.size + PassImageCache.ENTRY_OVERHEAD_BYTES - 1)

        cache.lookup(ImageBytes(png), ImageRenderBounds.Default).keep(image)

        assertThat(image).isInstanceOf(PassImage.Decoded::class.java)
        assertThat(cache.lookup(ImageBytes(png), ImageRenderBounds.Default).cached()).isNull()
    }

    @Test
    fun aDecodedImageShownAgainFromTheCacheDrawsInTheFirstFrame() {
        val cache = PassImageCache()
        val png = pngOf(width = 64, height = 64)
        var shown by mutableStateOf(true)
        composeRule.setContent { if (shown) TestImage(png, CopyOnWriteArrayList(), cache) }
        composeRule.waitUntil(DECODE_TIMEOUT_MS) {
            composeRule.onAllNodesWithContentDescription(DESCRIPTION).fetchSemanticsNodes().isNotEmpty()
        }
        shown = false
        composeRule.waitForIdle()

        // With the clock held, only the composition that brings the image back can draw it.
        composeRule.mainClock.autoAdvance = false
        shown = true
        composeRule.mainClock.advanceTimeByFrame()

        assertThat(composeRule.onAllNodesWithContentDescription(DESCRIPTION).fetchSemanticsNodes()).hasSize(1)
    }

    @Composable
    private fun TestImage(
        png: ByteArray,
        rejections: MutableList<ImageDecodeRejection>,
        cache: PassImageCache? = null,
    ) {
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
            cache = cache,
        )
    }

    private fun compressed(format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(format, 90, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** A precondition asserted from [apiLevel] up; below it, the test skips where it does not hold. */
    private fun expectFromApi(apiLevel: Int, holds: Boolean) {
        if (Build.VERSION.SDK_INT >= apiLevel) assertThat(holds).isTrue() else assumeTrue(holds)
    }

    /** The platform's own decode of [png], with none of the kernel's checks. */
    private fun platformDecode(png: ByteArray): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(png)))

    // Display P3 with colour and alpha in it, so a lost profile or a changed pixel shows.
    private fun wideGamutPng(): ByteArray {
        val bitmap =
            Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888, true, ColorSpace.get(ColorSpace.Named.DISPLAY_P3))
        for (y in 0 until 16) {
            for (x in 0 until 16) bitmap.setPixel(x, y, Color.argb(255 - y * 8, x * 16, 255 - x * 16, y * 16))
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }

    /** [png] with [afterHeader] spliced in behind its IHDR chunk and [atTheEnd] ahead of its IEND chunk. */
    private fun withChunks(
        png: ByteArray,
        afterHeader: List<Pair<String, ByteArray>>,
        atTheEnd: List<Pair<String, ByteArray>>,
    ): ByteArray =
        ByteArrayOutputStream().also { out ->
            out.write(png, 0, PNG_HEADER_BYTES)
            for ((type, data) in afterHeader) out.writeChunk(type, data)
            out.write(png, PNG_HEADER_BYTES, png.size - PNG_HEADER_BYTES - EMPTY_CHUNK_BYTES)
            for ((type, data) in atTheEnd) out.writeChunk(type, data)
            out.write(png, png.size - EMPTY_CHUNK_BYTES, EMPTY_CHUNK_BYTES)
        }.toByteArray()

    private fun deflated(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> DeflaterOutputStream(out).use { it.write(bytes) } }.toByteArray()

    /** Runs [block] and returns its result with the peak native heap growth seen while it ran. */
    private fun <T> peakNativeGrowth(block: () -> T): Pair<T, Long> {
        val baseline = Debug.getNativeHeapAllocatedSize()
        val sampling = CountDownLatch(1)
        val done = AtomicBoolean(false)
        var peak = baseline
        val sampler =
            Thread {
                sampling.countDown()
                while (!done.get()) peak = maxOf(peak, Debug.getNativeHeapAllocatedSize())
            }
        sampler.start()
        sampling.await()
        val result =
            try {
                block()
            } finally {
                done.set(true)
                sampler.join()
            }
        return result to maxOf(peak, Debug.getNativeHeapAllocatedSize()) - baseline
    }

    // A valid all-black greyscale PNG, streamed so a 16 MP fixture costs the test a few kilobytes.
    private fun pngOf(width: Int, height: Int, interlaced: Boolean = false): ByteArray {
        val header =
            ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeInt(width)
                    writeInt(height)
                    write(byteArrayOf(8, 0, 0, 0, if (interlaced) 1 else 0))
                }
            }.toByteArray()
        val pixels =
            ByteArrayOutputStream().also { out ->
                DeflaterOutputStream(out).use { deflater ->
                    for ((rows, rowBytes) in scanlinesOf(width, height, interlaced)) {
                        val row = ByteArray(rowBytes)
                        repeat(rows) { deflater.write(row) }
                    }
                }
            }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            out.write(PNG_SIGNATURE)
            out.writeChunk("IHDR", header)
            out.writeChunk("IDAT", pixels)
            out.writeChunk("IEND", ByteArray(0))
        }.toByteArray()
    }

    // Row count and bytes per row (filter byte included) of each pass: one, or Adam7's seven.
    private fun scanlinesOf(width: Int, height: Int, interlaced: Boolean): List<Pair<Int, Int>> {
        if (!interlaced) return listOf(height to width + 1)
        return ADAM7.map { (xStart, yStart, xStep, yStep) ->
            (height - yStart + yStep - 1) / yStep to (width - xStart + xStep - 1) / xStep + 1
        }.filter { (rows, rowBytes) -> rows > 0 && rowBytes > 1 }
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

        // Signature, then the IHDR chunk with its length, type, data and CRC.
        const val PNG_HEADER_BYTES = 33

        // Length, type and CRC of a chunk with no data, which is what IEND is.
        const val EMPTY_CHUNK_BYTES = 12
        const val INFLATED_TEXT_BYTES = 4 * 1024 * 1024

        // The first API level seen to write a gain map into a PNG and read it back.
        const val GAIN_MAP_IN_PNG_API = 36

        // xStart, yStart, xStep, yStep of each Adam7 pass.
        val ADAM7 =
            listOf(
                listOf(0, 0, 8, 8),
                listOf(4, 0, 8, 8),
                listOf(0, 4, 4, 8),
                listOf(2, 0, 4, 4),
                listOf(0, 2, 2, 4),
                listOf(1, 0, 2, 2),
                listOf(0, 1, 1, 2),
            )

        // GIF89a, 1 x 1, two-colour table, one image.
        val GIF_1X1 =
            intArrayOf(
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00,
                0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF,
                0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
            ).map { it.toByte() }.toByteArray()
    }
}
