package `is`.walt.passes.core

/**
 * What a PNG declares in its IHDR chunk. The sides are unsigned 32-bit values, hence [Long];
 * [interlaced] is true for any interlace method other than none.
 */
public class PngHeader private constructor(
    public val width: Long,
    public val height: Long,
    public val interlaced: Boolean,
) {
    /** True when the declared pixel count is over [limit]. Cannot overflow, whatever the sides. */
    public fun exceedsPixelCount(limit: Long): Boolean = width > limit / height

    public companion object {
        /**
         * Reads the PNG signature and a 13-byte IHDR chunk from the first 29 bytes, decoding nothing.
         * Null for bytes that do not start with both, or that declare a zero side.
         */
        public fun read(bytes: ByteArray): PngHeader? {
            val startsAsPng =
                bytes.size >= HEADER_LENGTH &&
                    bytes.matchesAt(0, PNG_SIGNATURE) &&
                    bytes.matchesAt(IHDR_START_OFFSET, IHDR_START)
            if (!startsAsPng) return null
            val width = readU32BigEndian(bytes, WIDTH_OFFSET)
            val height = readU32BigEndian(bytes, HEIGHT_OFFSET)
            val interlaced = bytes[INTERLACE_OFFSET] != NOT_INTERLACED
            return if (width == 0L || height == 0L) null else PngHeader(width, height, interlaced)
        }
    }
}

private fun ByteArray.matchesAt(
    offset: Int,
    expected: ByteArray,
): Boolean = expected.indices.all { this[offset + it] == expected[it] }

private fun readU32BigEndian(
    bytes: ByteArray,
    offset: Int,
): Long =
    (0 until U32_BYTES).fold(0L) { value, i ->
        value.shl(Byte.SIZE_BITS) or (bytes[offset + i].toLong() and 0xFFL)
    }

private val PNG_SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

// The chunk's length, always 13, then its type.
private val IHDR_START: ByteArray = byteArrayOf(0, 0, 0, 13) + "IHDR".toByteArray(Charsets.US_ASCII)

private const val U32_BYTES = 4
private const val IHDR_START_OFFSET = 8
private const val WIDTH_OFFSET = 16
private const val HEIGHT_OFFSET = 20
private const val INTERLACE_OFFSET = 28
private const val HEADER_LENGTH = INTERLACE_OFFSET + 1
private const val NOT_INTERLACED: Byte = 0
