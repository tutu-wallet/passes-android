package `is`.walt.passes.image.decode

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** [decodeBounded] against the platform `ImageDecoder`, which the JVM suite only stubs. */
@RunWith(AndroidJUnit4::class)
class BoundedBitmapDecodeInstrumentedTest {

    private enum class Reason { TooLarge, Malformed, RuntimeFailure, Oom }

    private fun policy(maxSide: Int, maxSize: DecodeSize? = null) =
        BoundedDecodePolicy(
            allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
            gate = { _, w, h -> if (w > maxSide || h > maxSide) Reason.TooLarge else null },
            maxSize = maxSize,
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
    fun decodesAnImageOverTheBoxAtAWholeNumberDivision() {
        val result =
            decodeBounded(
                rawBytes = pngOf(width = 100, height = 60),
                policy = policy(maxSide = 100, maxSize = DecodeSize(40, 40)),
            )
        val bitmap = (result as BoundedBitmap.Decoded).bitmap
        assertThat(bitmap.width).isEqualTo(33)
        assertThat(bitmap.height).isEqualTo(20)
    }

    @Test
    fun neverUpscalesToABoxLargerThanTheSource() {
        val result =
            decodeBounded(
                rawBytes = pngOf(width = 16, height = 16),
                policy = policy(maxSide = 64, maxSize = DecodeSize(4096, 4096)),
            )
        val bitmap = (result as BoundedBitmap.Decoded).bitmap
        assertThat(bitmap.width).isEqualTo(16)
        assertThat(bitmap.height).isEqualTo(16)
    }

    @Test
    fun rejectsOverCapImageWhateverTheBox() {
        val result =
            decodeBounded(
                rawBytes = pngOf(width = 16, height = 16),
                policy = policy(maxSide = 1, maxSize = DecodeSize(16, 16)),
            )
        assertThat(result).isEqualTo(BoundedBitmap.Rejected(Reason.TooLarge))
    }

    @Test
    fun downsampledDecodeHoldsNoFullSizeIntermediate() {
        // The source just under twice the box is the shape an exact-fit target decodes at full size.
        val side = 3839
        val png = blackPngOf(side)
        val baseline = Debug.getNativeHeapAllocatedSize()
        val done = AtomicBoolean(false)
        var peak = baseline
        val sampler = Thread { while (!done.get()) peak = maxOf(peak, Debug.getNativeHeapAllocatedSize()) }
        sampler.start()

        val result = decodeBounded(rawBytes = png, policy = policy(maxSide = side, maxSize = DecodeSize(1920, 1920)))

        done.set(true)
        sampler.join()
        val bitmap = (result as BoundedBitmap.Decoded).bitmap
        assertThat(bitmap.width).isEqualTo(1919)
        val fullSizeBytes = side.toLong() * side * 4
        assertThat(peak - baseline).isLessThan(fullSizeBytes / 2)
        bitmap.recycle()
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

    // A valid all-black greyscale square, streamed so the fixture itself allocates almost nothing.
    private fun blackPngOf(side: Int): ByteArray {
        val header =
            ByteArrayOutputStream().also {
                DataOutputStream(it).apply {
                    writeInt(side)
                    writeInt(side)
                    write(byteArrayOf(8, 0, 0, 0, 0))
                }
            }.toByteArray()
        val pixels =
            ByteArrayOutputStream().also { out ->
                DeflaterOutputStream(out).use { deflater ->
                    val row = ByteArray(side + 1)
                    repeat(side) { deflater.write(row) }
                }
            }.toByteArray()
        return ByteArrayOutputStream().also { out ->
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
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
}
