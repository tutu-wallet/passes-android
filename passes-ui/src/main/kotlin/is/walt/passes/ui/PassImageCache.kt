package `is`.walt.passes.ui

import android.graphics.Bitmap
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.ui.internal.ByteBoundedLru

/**
 * What [BoundedImage] decoded or refused, up to [maxBytes] of pixels and image bytes; only clearing is public.
 * Hoist one per list with `remember { PassImageCache() }`; clear it after the composition that drops a deleted item.
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
        /** 32 MiB: about fifty images filling a 342 x 442 px box (twice a small list tile). */
        public const val DEFAULT_MAX_BYTES: Int = 32 * 1024 * 1024

        // Keeps entries that hold no pixels, such as refusals, bounded in number.
        internal const val ENTRY_OVERHEAD_BYTES: Int = 1024

        private fun weightOf(key: Key, image: PassImage): Int {
            val pixels = (image as? PassImage.Decoded)?.bitmap?.allocationByteCount ?: 0
            return pixels + key.bytes.bytes.size + ENTRY_OVERHEAD_BYTES
        }
    }
}

/** One decode's result. */
internal sealed interface PassImage {
    // Not a data class: a Bitmap has identity, not value, equality.
    class Decoded(val bitmap: Bitmap) : PassImage

    data class Refused(val reason: ImageDecodeRejection) : PassImage
}
