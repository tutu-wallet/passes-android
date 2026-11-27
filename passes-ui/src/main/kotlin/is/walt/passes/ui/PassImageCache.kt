package `is`.walt.passes.ui

import android.graphics.Bitmap
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.ui.internal.ByteBoundedLru
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What [BoundedImage] decoded or refused, kept up to [maxBytes] of pixels and image bytes. Hoist one per
 * list with `remember { PassImageCache() }` and clear it when a pass is deleted; the host can only clear it.
 */
public class PassImageCache(maxBytes: Int = DEFAULT_MAX_BYTES) {
    private val entries = ByteBoundedLru<Key, PassImage>(maxBytes, ::weightOf)

    /** Drops every entry, and the result of any decode already under way; nothing is recycled. */
    public fun clear(): Unit = entries.clear()

    internal fun lookup(bytes: ImageBytes, bounds: ImageRenderBounds): Lookup = Lookup(Key(bytes, bounds))

    /** One image at one bounds, hashed once. A [clear] after it is taken drops what it would keep. */
    internal inner class Lookup internal constructor(private val key: Key) {
        private val generation = entries.generation

        fun cached(): PassImage? = entries.get(key)

        /** Keeps [image], or returns the result already held, so every caller shares one. */
        fun keep(image: PassImage): PassImage = entries.putIfAbsent(key, image, generation) ?: image
    }

    // Holds the image bytes, which count against the budget with the pixels.
    internal class Key(val bytes: ImageBytes, val bounds: ImageRenderBounds) {
        private val hash = 31 * bytes.hashCode() + bounds.hashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean =
            other is Key && hash == other.hash && bounds == other.bounds && bytes == other.bytes
    }

    public companion object {
        /** 32 MiB: about fifty small images decoded into a 342 x 442 px box, twice a small list tile. */
        public const val DEFAULT_MAX_BYTES: Int = 32 * 1024 * 1024

        // Keeps entries that hold no pixels, such as refusals, bounded in number.
        internal const val ENTRY_OVERHEAD_BYTES: Int = 1024

        private fun weightOf(key: Key, image: PassImage): Int {
            val pixels = (image as? PassImage.Decoded)?.bitmap?.allocationByteCount ?: 0
            return pixels + key.bytes.bytes.size + ENTRY_OVERHEAD_BYTES
        }
    }
}

/** One decode's result. Not data classes: a [Bitmap] has identity, and each refusal is reported once. */
internal sealed interface PassImage {
    class Decoded(val bitmap: Bitmap) : PassImage

    class Refused(val reason: ImageDecodeRejection) : PassImage {
        private val reported = AtomicBoolean(false)

        /** True for the first caller only. */
        fun claimReport(): Boolean = reported.compareAndSet(false, true)
    }
}
