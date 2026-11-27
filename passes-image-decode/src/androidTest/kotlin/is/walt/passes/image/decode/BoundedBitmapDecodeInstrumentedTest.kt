package `is`.walt.passes.image.decode

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** [decodeBounded] against the platform `ImageDecoder`, which the JVM suite only stubs. */
@RunWith(AndroidJUnit4::class)
class BoundedBitmapDecodeInstrumentedTest {

    private enum class Reason { TooLarge, Malformed, RuntimeFailure, Oom }

    private fun policy(maxSide: Int, target: DecodeTarget? = null) =
        BoundedDecodePolicy(
            allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
            gate = { _, w, h -> if (w > maxSide || h > maxSide) Reason.TooLarge else null },
            target = target,
            onMalformed = { Reason.Malformed },
            onRuntimeFailure = { Reason.RuntimeFailure },
            onOutOfMemory = { Reason.Oom },
        )

    @Test
    fun decodesValidImageWithinCaps() {
        val result = decodeBounded(rawBytes = pngOf(width = 16, height = 16), policy = policy(maxSide = 64))
        assertThat(result).isInstanceOf(BoundedBitmap.Decoded::class.java)
        val bitmap = (result as BoundedBitmap.Decoded).bitmap
        assertThat(bitmap.width).isEqualTo(16)
        assertThat(bitmap.height).isEqualTo(16)
        // Software allocator: a readable, non-hardware bitmap (a symbol decode needs getPixels).
        assertThat(bitmap.config).isNotEqualTo(Bitmap.Config.HARDWARE)
    }

    @Test
    fun rejectsOverCapImageByGateWithoutFullAllocation() {
        // A 16x16 image under a 1px cap trips the gate; the listener forces a 1x1 decode so the
        // rejected path allocates nothing of size.
        val result = decodeBounded(rawBytes = pngOf(width = 16, height = 16), policy = policy(maxSide = 1))
        assertThat(result).isEqualTo(BoundedBitmap.Rejected(Reason.TooLarge))
    }

    @Test
    fun decodesAnAcceptedImageAtItsTarget() {
        val result =
            decodeBounded(
                rawBytes = pngOf(width = 64, height = 32),
                policy = policy(maxSide = 64, target = { _, _ -> DecodeSize(32, 16) }),
            )
        val bitmap = (result as BoundedBitmap.Decoded).bitmap
        assertThat(bitmap.width).isEqualTo(32)
        assertThat(bitmap.height).isEqualTo(16)
    }

    @Test
    fun neverUpscalesToATargetLargerThanTheSource() {
        val result =
            decodeBounded(
                rawBytes = pngOf(width = 16, height = 16),
                policy = policy(maxSide = 64, target = { _, _ -> DecodeSize(4096, 4096) }),
            )
        val bitmap = (result as BoundedBitmap.Decoded).bitmap
        assertThat(bitmap.width).isEqualTo(16)
        assertThat(bitmap.height).isEqualTo(16)
    }

    @Test
    fun rejectsOverCapImageWhateverTheTarget() {
        val result =
            decodeBounded(
                rawBytes = pngOf(width = 16, height = 16),
                policy = policy(maxSide = 1, target = { _, _ -> DecodeSize(16, 16) }),
            )
        assertThat(result).isEqualTo(BoundedBitmap.Rejected(Reason.TooLarge))
    }

    @Test
    fun foldsMalformedContainerToOnMalformed() {
        // Random bytes are not a decodable container; with no gate rejection in flight the
        // thrown failure folds to the caller's onMalformed value.
        val garbage = ByteArray(256) { (it * 37 + 13).toByte() }
        val result = decodeBounded(rawBytes = garbage, policy = policy(maxSide = 64))
        assertThat(result).isEqualTo(BoundedBitmap.Rejected(Reason.Malformed))
    }

    private fun pngOf(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}
