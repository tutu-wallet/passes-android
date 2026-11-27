package `is`.walt.passes.image.decode

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The one audited decode of untrusted compressed image bytes to a [Bitmap], shared by every
 * consumer. Mechanism only: the caller's [policy] carries the caps, the target size and the folds.
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
                // An image already at its target keeps the platform's unscaled decode.
                val target = decodeSize(header, rejected = rejection != null, policy.target)
                if (target != header) decoder.setTargetSize(target.width, target.height)
            }
        rejection?.let {
            bitmap.recycle()
            BoundedBitmap.Rejected(it)
        } ?: BoundedBitmap.Decoded(bitmap)
    } catch (_: IOException) {
        // A gate rejection already in flight wins over every fold below.
        BoundedBitmap.Rejected(rejection ?: policy.onMalformed())
    } catch (_: IllegalArgumentException) {
        BoundedBitmap.Rejected(rejection ?: policy.onMalformed())
    } catch (oom: OutOfMemoryError) {
        containOutOfMemory(rejection, policy) ?: throw oom
    } catch (_: RuntimeException) {
        BoundedBitmap.Rejected(rejection ?: policy.onRuntimeFailure())
    }
}

/**
 * The size to decode at: 1x1 for a rejected image, else the caller's target kept inside
 * 1..[header], so the bitmap is never larger than the size the gate accepted.
 */
internal fun decodeSize(header: DecodeSize, rejected: Boolean, target: DecodeTarget?): DecodeSize {
    val wanted = if (rejected) DecodeSize(1, 1) else target?.of(header.width, header.height) ?: header
    return DecodeSize(wanted.width.coerceIn(1, header.width), wanted.height.coerceIn(1, header.height))
}

/**
 * The contained rejection when [policy] folds an OOM (a [pendingRejection] from the gate wins),
 * or null when the caller's posture is to let the error propagate.
 */
internal fun <R : Any> containOutOfMemory(
    pendingRejection: R?,
    policy: BoundedDecodePolicy<R>,
): BoundedBitmap<R>? = policy.onOutOfMemory?.let { BoundedBitmap.Rejected(pendingRejection ?: it()) }

/**
 * One call site's decode posture. [target] null decodes at source size; [onOutOfMemory] null lets
 * an OOM propagate, which is the in-process display posture.
 */
public class BoundedDecodePolicy<R : Any>(
    public val allocator: Int,
    public val gate: ImageHeaderGate<R>,
    public val target: DecodeTarget?,
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

/**
 * The size to decode an accepted image at, from its header size. A null or source-size answer
 * decodes unscaled; the mechanism clamps the answer so a target can only shrink the bitmap.
 */
public fun interface DecodeTarget {
    public fun of(width: Int, height: Int): DecodeSize?
}

public data class DecodeSize(val width: Int, val height: Int)

/** Outcome of [decodeBounded]. The caller owns and must release a decoded [Bitmap]. */
public sealed interface BoundedBitmap<out R> {
    // Not a data class: a Bitmap has identity, not value, equality.
    public class Decoded(public val bitmap: Bitmap) : BoundedBitmap<Nothing>

    public data class Rejected<out R>(val reason: R) : BoundedBitmap<R>
}
