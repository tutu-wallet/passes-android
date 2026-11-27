package `is`.walt.passes.image.decode

import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The pure halves, plus the gate dispatch through Robolectric's `ImageDecoder` stub (about 100 x 100
 * for any bytes). The platform decode itself is [BoundedBitmapDecodeInstrumentedTest]'s.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BoundedBitmapDecodeTest {

    // A caller's rejection taxonomy. The mechanism names no reason of its own; this stands in
    // for DecodeFailureReason / ImageDecodeRejection.
    private enum class Reason { TooLarge, Malformed, RuntimeFailure, Oom }

    private fun policy(
        maxSide: Int,
        onOutOfMemory: (() -> Reason)? = { Reason.Oom },
    ) = BoundedDecodePolicy(
        allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
        gate = { _, w, h -> if (w > maxSide || h > maxSide) Reason.TooLarge else null },
        maxSize = null,
        onMalformed = { Reason.Malformed },
        onRuntimeFailure = { Reason.RuntimeFailure },
        onOutOfMemory = onOutOfMemory,
    )

    @Test
    fun containOutOfMemoryPropagatesWhenNoFold() {
        // In-process display posture: no rejection bucket for an OOM, so signal propagate.
        val contained = containOutOfMemory(policy(maxSide = 8, onOutOfMemory = null))
        assertThat(contained).isNull()
    }

    @Test
    fun containOutOfMemoryFoldsWhenSupplied() {
        // Sandbox posture: an OOM is contained as the caller's fold value.
        val contained = containOutOfMemory(policy(maxSide = 8))
        assertThat(contained).isEqualTo(BoundedBitmap.Rejected(Reason.Oom))
    }

    private val box = DecodeSize(1920, 1920)

    private fun decodeSize(width: Int, height: Int, maxSize: DecodeSize? = box) =
        decodeSize(DecodeSize(width, height), maxSize)

    @Test
    fun noBoxDecodesAtTheHeaderSize() {
        assertThat(decodeSize(4096, 4096, maxSize = null)).isEqualTo(DecodeSize(4096, 4096))
    }

    @Test
    fun headerInsideTheBoxKeepsItsSize() {
        assertThat(decodeSize(360, 220)).isEqualTo(DecodeSize(360, 220))
        assertThat(decodeSize(1920, 1920)).isEqualTo(DecodeSize(1920, 1920))
    }

    @Test
    fun headerOverTheBoxIsDividedByTheSmallestWholeNumberThatFits() {
        assertThat(decodeSize(1921, 1921)).isEqualTo(DecodeSize(960, 960))
        assertThat(decodeSize(3839, 3839)).isEqualTo(DecodeSize(1919, 1919))
        assertThat(decodeSize(3840, 3840)).isEqualTo(DecodeSize(1920, 1920))
        assertThat(decodeSize(4096, 4096)).isEqualTo(DecodeSize(1365, 1365))
    }

    @Test
    fun theTighterAxisPicksTheDivisor() {
        assertThat(decodeSize(2000, 100)).isEqualTo(DecodeSize(1000, 50))
        assertThat(decodeSize(100, 3840)).isEqualTo(DecodeSize(50, 1920))
        val tile = DecodeSize(200, 300)
        assertThat(decodeSize(1000, 500, maxSize = tile)).isEqualTo(DecodeSize(200, 100))
        assertThat(decodeSize(500, 1500, maxSize = tile)).isEqualTo(DecodeSize(100, 300))
    }

    @Test
    fun aBoxTwiceTheOutputDecodesAtLeastTheOutputFit() {
        val outputs = listOf(DecodeSize(64, 64), DecodeSize(195, 252), DecodeSize(600, 800), DecodeSize(1080, 2048))
        val headers = (1..40).flatMap { i -> listOf(DecodeSize(97 * i, 61 * i), DecodeSize(53 * i, 149 * i)) }
        for (output in outputs) {
            for (header in headers) {
                val fit = minOf(output.width.toDouble() / header.width, output.height.toDouble() / header.height, 1.0)
                val decoded = decodeSize(header, DecodeSize(output.width * 2, output.height * 2))
                assertThat(decoded.width).isAtLeast((header.width * fit).toInt())
                assertThat(decoded.height).isAtLeast((header.height * fit).toInt())
            }
        }
    }

    @Test
    fun extremeAspectKeepsAtLeastOnePixel() {
        assertThat(decodeSize(16_777_216, 1)).isEqualTo(DecodeSize(1919, 1))
        assertThat(decodeSize(1, 16_777_216)).isEqualTo(DecodeSize(1, 1919))
        assertThat(decodeSize(Int.MAX_VALUE, Int.MAX_VALUE, maxSize = DecodeSize(1, 1))).isEqualTo(DecodeSize(1, 1))
    }

    @Test
    fun decodeSizeRefusesANonPositiveSide() {
        val thrown = runCatching { DecodeSize(0, 16) }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun decodeRejectsWhenGateTripsOnStubDimensions() {
        // Robolectric's stub bitmap exceeds a 1px cap, so the gate rejects with the caller's reason.
        val result = decodeBounded(rawBytes = STUB_INPUT, policy = policy(maxSide = 1))
        assertThat(result).isEqualTo(BoundedBitmap.Rejected(Reason.TooLarge))
    }

    @Test
    fun decodeReturnsBitmapWhenGateAccepts() {
        val result = decodeBounded(rawBytes = STUB_INPUT, policy = policy(maxSide = 4096))
        assertThat(result).isInstanceOf(BoundedBitmap.Decoded::class.java)
        assertThat((result as BoundedBitmap.Decoded).bitmap).isNotNull()
    }

    @Test
    fun decodeCarriesADifferentRejectionTaxonomy() {
        // Same mechanism, a second caller type: proves the generic is not pinned to one enum.
        val stringPolicy =
            BoundedDecodePolicy<String>(
                allocator = ImageDecoder.ALLOCATOR_DEFAULT,
                gate = { _, _, _ -> "too-big" },
                maxSize = null,
                onMalformed = { "malformed" },
                onRuntimeFailure = { "runtime" },
                onOutOfMemory = null,
            )
        val result = decodeBounded(rawBytes = STUB_INPUT, policy = stringPolicy)
        assertThat(result).isEqualTo(BoundedBitmap.Rejected("too-big"))
    }

    private companion object {
        // Arbitrary bytes; the Robolectric shadow yields a stub bitmap regardless of content.
        val STUB_INPUT = ByteArray(64) { (it * 31).toByte() }
    }
}
