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
        target = null,
        onMalformed = { Reason.Malformed },
        onRuntimeFailure = { Reason.RuntimeFailure },
        onOutOfMemory = onOutOfMemory,
    )

    @Test
    fun containOutOfMemoryPropagatesWhenNoFold() {
        // In-process display posture: no rejection bucket for an OOM, so signal propagate.
        val contained = containOutOfMemory(pendingRejection = null, policy = policy(maxSide = 8, onOutOfMemory = null))
        assertThat(contained).isNull()
    }

    @Test
    fun containOutOfMemoryFoldsWhenSupplied() {
        // Sandbox posture: an OOM is contained as the caller's fold value.
        val contained = containOutOfMemory(pendingRejection = null, policy = policy(maxSide = 8))
        assertThat(contained).isEqualTo(BoundedBitmap.Rejected(Reason.Oom))
    }

    @Test
    fun containOutOfMemoryPrefersPendingGateRejection() {
        // A header rejection already in flight wins over the OOM fold (the `rejection ?:` idiom).
        val contained = containOutOfMemory(pendingRejection = Reason.TooLarge, policy = policy(maxSide = 8))
        assertThat(contained).isEqualTo(BoundedBitmap.Rejected(Reason.TooLarge))
    }

    private val header = DecodeSize(64, 32)

    @Test
    fun noTargetDecodesAtTheHeaderSize() {
        assertThat(decodeSize(header, rejected = false, target = null)).isEqualTo(header)
        assertThat(decodeSize(header, rejected = false, target = { _, _ -> null })).isEqualTo(header)
    }

    @Test
    fun targetInsideTheSourceIsKept() {
        assertThat(decodeSize(header, rejected = false, target = { w, h -> DecodeSize(w / 2, h / 2) }))
            .isEqualTo(DecodeSize(32, 16))
    }

    @Test
    fun targetLargerThanTheSourceIsClampedToIt() {
        assertThat(decodeSize(header, rejected = false, target = { _, _ -> DecodeSize(4096, 4096) })).isEqualTo(header)
    }

    @Test
    fun nonPositiveTargetIsClampedToOnePixel() {
        assertThat(decodeSize(header, rejected = false, target = { _, _ -> DecodeSize(0, -5) }))
            .isEqualTo(DecodeSize(1, 1))
    }

    @Test
    fun rejectedImageDecodesAtOnePixelWhateverTheTarget() {
        assertThat(decodeSize(header, rejected = true, target = { w, h -> DecodeSize(w, h) }))
            .isEqualTo(DecodeSize(1, 1))
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
                target = null,
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
