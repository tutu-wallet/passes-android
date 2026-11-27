package `is`.walt.passes.ui

import android.graphics.Bitmap
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.ui.internal.ByteBoundedLru
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decode results that [BoundedImage] keeps for every image shown with this cache, refusals included,
 * up to [maxBytes] of pixels and image bytes. Hoist one per list. The host can only clear it.
 */
public class PassImageCache(maxBytes: Int = DEFAULT_MAX_BYTES) {
    private val entries = ByteBoundedLru<Key, PassImage>(maxBytes) { key, image -> weightOf(key, image) }

    /** Drops every entry; an image shown afterwards is decoded again. */
    public fun clear(): Unit = entries.clear()

    internal fun get(bytes: ImageBytes, bounds: ImageRenderBounds): PassImage? = entries.get(Key(bytes, bounds))

    internal fun put(bytes: ImageBytes, bounds: ImageRenderBounds, image: PassImage): Unit =
        entries.put(Key(bytes, bounds), image)

    // The key holds the image bytes, so they count against the budget with the pixels.
    private data class Key(val bytes: ImageBytes, val bounds: ImageRenderBounds)

    private fun weightOf(key: Key, image: PassImage): Int {
        val pixels = (image as? PassImage.Decoded)?.bitmap?.allocationByteCount ?: 0
        return pixels + key.bytes.bytes.size + ENTRY_OVERHEAD_BYTES
    }

    public companion object {
        /** 32 MiB: at least fifty 8-bit images decoded into a 342 x 442 px box, twice a small list tile. */
        public const val DEFAULT_MAX_BYTES: Int = 32 * 1024 * 1024

        // Keeps entries that hold no pixels, such as refusals, bounded in number.
        private const val ENTRY_OVERHEAD_BYTES = 1024
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
