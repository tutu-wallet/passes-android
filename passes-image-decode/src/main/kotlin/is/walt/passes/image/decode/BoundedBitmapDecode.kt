package `is`.walt.passes.image.decode

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The one audited decode of untrusted compressed image bytes to a [Bitmap], shared by every
 * consumer. Mechanism only: the caller's [policy] carries the caps, the output box and the folds.
 */
public fun <R : Any> decodeBounded(
    rawBytes: ByteArray,
    policy: BoundedDecodePolicy<R>,
): BoundedBitmap<R> {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(rawBytes))
    var rejection: R? = null
    return try {
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = policy.allocator
                val header = DecodeSize(info.size.width, info.size.height)
                rejection = policy.gate.evaluate(info.mimeType, header.width, header.height)
                // Thrown before the platform decodes a pixel, so a refused image is never decoded.
                if (rejection != null) throw HeaderRefused()
                // An image already at its target keeps the platform's unscaled decode.
                val target = decodeSize(header, policy.maxSize)
                if (target != header) decoder.setTargetSize(target.width, target.height)
            }
        BoundedBitmap.Decoded(bitmap)
    } catch (_: HeaderRefused) {
        BoundedBitmap.Rejected(checkNotNull(rejection))
    } catch (_: IOException) {
        BoundedBitmap.Rejected(policy.onMalformed())
    } catch (_: IllegalArgumentException) {
        // setTargetSize, or a header with a non-positive side.
        BoundedBitmap.Rejected(policy.onMalformed())
    } catch (oom: OutOfMemoryError) {
        containOutOfMemory(policy) ?: throw oom
    } catch (_: RuntimeException) {
        BoundedBitmap.Rejected(policy.onRuntimeFailure())
    }
}

/** Ends a decode from the header listener when the caller's gate refuses the image. */
private class HeaderRefused : RuntimeException(null, null, false, false)

/**
 * The size to decode at: [header] divided by the smallest whole number that fits [maxSize]. The
 * codec samples at that number; no full-size bitmap is scaled down.
 */
internal fun decodeSize(header: DecodeSize, maxSize: DecodeSize?): DecodeSize {
    val box = maxSize ?: header
    val divisor = maxOf(ceilDiv(header.width, box.width), ceilDiv(header.height, box.height))
    return DecodeSize(maxOf(1, header.width / divisor), maxOf(1, header.height / divisor))
}

// Both operands are positive, as DecodeSize requires.
private fun ceilDiv(dividend: Int, divisor: Int): Int = (dividend - 1) / divisor + 1

/** The contained rejection when [policy] folds an OOM, or null when it lets the error propagate. */
internal fun <R : Any> containOutOfMemory(policy: BoundedDecodePolicy<R>): BoundedBitmap<R>? =
    policy.onOutOfMemory?.let { BoundedBitmap.Rejected(it()) }

/**
 * One call site's decode posture. [maxSize] null decodes at source size; [onOutOfMemory] null lets
 * an OOM propagate, which is the in-process display posture.
 */
public class BoundedDecodePolicy<R : Any>(
    public val allocator: Int,
    public val gate: ImageHeaderGate<R>,
    public val maxSize: DecodeSize?,
    public val onMalformed: () -> R,
    public val onRuntimeFailure: () -> R,
    public val onOutOfMemory: (() -> R)?,
)

/**
 * The accept/reject decision, taken from the header before the bitmap is allocated. Returns the
 * caller's rejection reason, or null to decode.
 */
public fun interface ImageHeaderGate<out R> {
    public fun evaluate(mimeType: String?, width: Int, height: Int): R?
}

/** A bitmap size in pixels, both sides positive. */
public data class DecodeSize(val width: Int, val height: Int) {
    init {
        require(width > 0 && height > 0) { "size must be positive, was $width x $height" }
    }
}

/** Outcome of [decodeBounded]. The caller owns and must release a decoded [Bitmap]. */
public sealed interface BoundedBitmap<out R> {
    // Not a data class: a Bitmap has identity, not value, equality.
    public class Decoded(public val bitmap: Bitmap) : BoundedBitmap<Nothing>

    public data class Rejected<out R>(val reason: R) : BoundedBitmap<R>
}
