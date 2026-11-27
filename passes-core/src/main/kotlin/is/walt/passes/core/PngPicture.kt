package `is`.walt.passes.core

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A PNG cut down to the chunks that carry its picture. Text, metadata, animation, gain map and
 * unknown chunks are left out, so a decoder given the result reads none of them.
 */
public object PngPicture {
    /** The most a kept colour profile (iCCP) inflates to. A larger one is left out. */
    internal const val MAX_PROFILE_BYTES: Int = 256 * 1024

    /**
     * A copy of [bytes] holding IHDR, a well-formed first sRGB, gAMA, cHRM, iCCP, PLTE and tRNS ahead of
     * the image data, every IDAT and an empty IEND. Null for bytes that are not a PNG or end inside a chunk.
     */
    public fun of(bytes: ByteArray): ByteArray? = if (PngHeader.read(bytes) == null) null else pictureOf(bytes)
}

private fun pictureOf(bytes: ByteArray): ByteArray? {
    val picture = ByteArray(bytes.size + EMPTY_IEND.size)
    PNG_SIGNATURE.copyInto(picture)
    var written = PNG_SIGNATURE.size
    val decided = HashSet<Int>()
    var imageDataBegun = false
    var offset = PNG_SIGNATURE.size
    while (offset < bytes.size) {
        val chunk = chunkAt(bytes, offset) ?: return null
        if (chunk.type == IEND) break
        if (chunk.type == IDAT) imageDataBegun = true
        // Once the image data has begun, nothing but image data is kept.
        val kept = if (imageDataBegun) chunk.type == IDAT else chunk.isPictureChunk(bytes, decided)
        if (kept) {
            bytes.copyInto(picture, written, chunk.start, chunk.end)
            written += chunk.end - chunk.start
        }
        offset = chunk.end
    }
    EMPTY_IEND.copyInto(picture, written)
    return picture.copyOf(written + EMPTY_IEND.size)
}

/** One chunk of [PngPicture.of]'s walk, placed by its length and type. Its CRC is never read. */
private class Chunk(val type: Int, val start: Int, val dataLength: Int) {
    val dataStart: Int get() = start + CHUNK_HEADER_BYTES
    val end: Int get() = dataStart + dataLength + U32_BYTES
}

/** The chunk at [offset], or null when [bytes] end before its declared end. */
private fun chunkAt(
    bytes: ByteArray,
    offset: Int,
): Chunk? {
    val room = bytes.size - offset - CHUNK_HEADER_BYTES - U32_BYTES
    // The length is an unsigned 32-bit value, compared as a Long so that it cannot wrap.
    val length = if (room < 0) null else readU32BigEndian(bytes, offset).takeIf { it <= room }
    return length?.let {
        Chunk(type = readU32BigEndian(bytes, offset + U32_BYTES).toInt(), start = offset, dataLength = it.toInt())
    }
}

/**
 * True for a well-formed chunk that is the first of a picture kind. A kind is decided by its first
 * chunk alone: [decided] holds it from then on, so a later one is never looked at.
 */
private fun Chunk.isPictureChunk(
    bytes: ByteArray,
    decided: MutableSet<Int>,
): Boolean {
    val lengths = PICTURE_CHUNK_LENGTHS[type]
    val isFirstOfItsKind = lengths != null && decided.add(type)
    return isFirstOfItsKind && dataLength in lengths && (type != ICCP || profileFits(bytes, dataStart, dataLength))
}

/** True for iCCP data that is a name, a terminator, method 0 and a whole profile within the cap. */
private fun profileFits(
    bytes: ByteArray,
    start: Int,
    length: Int,
): Boolean {
    val nameEnd =
        (0 until minOf(length, MAX_PROFILE_NAME_BYTES + 1)).firstOrNull { bytes[start + it] == NUL } ?: return false
    val streamStart = nameEnd + 2
    val isDeflate = length >= streamStart && bytes[start + nameEnd + 1] == DEFLATE
    return isDeflate && isWholeProfile(bytes, start + streamStart, length - streamStart, PngPicture.MAX_PROFILE_BYTES)
}

/**
 * Inflates into a scratch buffer and keeps nothing. True for one whole stream and nothing after it,
 * of at most [limit] bytes, that opens, as an ICC profile does, with its own length.
 */
private fun isWholeProfile(
    bytes: ByteArray,
    start: Int,
    length: Int,
    limit: Int,
): Boolean {
    val inflater = Inflater()
    return try {
        inflater.setInput(bytes, start, length)
        val scratch = ByteArray(INFLATE_SCRATCH_BYTES)
        var count = inflater.inflate(scratch)
        // A reader may allocate the length a profile declares before it has inflated that much.
        val declared = if (count >= U32_BYTES) readU32BigEndian(scratch, 0) else NO_LENGTH
        var total = count
        // Zero means the stream ended, was cut short, or asks for a dictionary.
        while (count > 0 && total <= limit) {
            count = inflater.inflate(scratch)
            total += count
        }
        inflater.finished() && inflater.remaining == 0 && total <= limit && declared == total.toLong()
    } catch (_: DataFormatException) {
        false
    } finally {
        inflater.end()
    }
}

private fun chunkType(name: String): Int = readU32BigEndian(name.toByteArray(Charsets.US_ASCII), 0).toInt()

private val IDAT = chunkType("IDAT")
private val IEND = chunkType("IEND")
private val ICCP = chunkType("iCCP")

/** The kinds kept once each ahead of the image data, with the range of data lengths the PNG format sets each. */
private val PICTURE_CHUNK_LENGTHS: Map<Int, IntRange> =
    mapOf(
        chunkType("IHDR") to 13..13,
        chunkType("sRGB") to 1..1,
        chunkType("gAMA") to 4..4,
        chunkType("cHRM") to 32..32,
        ICCP to 0..MAX_PROFILE_CHUNK_BYTES,
        chunkType("PLTE") to 3..768,
        chunkType("tRNS") to 1..256,
    )

// Length, type, no data, CRC.
private val EMPTY_IEND: ByteArray =
    byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())

private const val CHUNK_HEADER_BYTES = 2 * U32_BYTES
private const val MAX_PROFILE_NAME_BYTES = 79

// The cap, with room for the name, the stream's framing and a profile stored uncompressed.
private const val MAX_PROFILE_CHUNK_BYTES = PngPicture.MAX_PROFILE_BYTES + 1024
private const val INFLATE_SCRATCH_BYTES = 8 * 1024
private const val NO_LENGTH = -1L
private const val NUL: Byte = 0
private const val DEFLATE: Byte = 0
