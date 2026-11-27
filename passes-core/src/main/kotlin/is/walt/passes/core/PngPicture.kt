package `is`.walt.passes.core

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * A PNG cut down to the chunks that carry its picture. Text, metadata, animation, gain map and
 * unknown chunks are left out, so a decoder given the result reads none of them.
 */
public object PngPicture {
    /** The most a kept colour profile (iCCP) inflates to. A larger one is left out. */
    public const val MAX_PROFILE_BYTES: Int = 256 * 1024

    /**
     * A copy of [bytes] holding IHDR, the first sRGB, gAMA, cHRM, iCCP, PLTE and tRNS ahead of the
     * image data, every IDAT, and an empty IEND. Null for bytes that are not a PNG or end inside a chunk.
     */
    public fun of(bytes: ByteArray): ByteArray? {
        if (PngHeader.read(bytes) == null) return null
        val picture = ByteArray(bytes.size + EMPTY_IEND.size)
        PNG_SIGNATURE.copyInto(picture)
        var written = PNG_SIGNATURE.size
        val kept = HashSet<Int>()
        var offset = PNG_SIGNATURE.size
        while (offset < bytes.size) {
            val chunk = chunkAt(bytes, offset) ?: return null
            if (chunk.type == IEND) break
            // Once the image data has begun, nothing but image data is kept.
            val isFirstOfItsKind = IDAT !in kept && chunk.type !in kept && chunk.isPictureChunk(bytes)
            if (chunk.type == IDAT || isFirstOfItsKind) {
                kept += chunk.type
                bytes.copyInto(picture, written, chunk.start, chunk.end)
                written += chunk.end - chunk.start
            }
            offset = chunk.end
        }
        EMPTY_IEND.copyInto(picture, written)
        return picture.copyOf(written + EMPTY_IEND.size)
    }
}

/** One chunk of [PngPicture.of]'s walk: its length and type are read, its CRC and data are not. */
private class Chunk(val type: Int, val start: Int, val dataLength: Int) {
    val dataStart: Int get() = start + CHUNK_HEADER_BYTES
    val end: Int get() = dataStart + dataLength + U32_BYTES
}

/** The chunk at [offset], or null when [bytes] end before its declared end. */
private fun chunkAt(bytes: ByteArray, offset: Int): Chunk? {
    val room = bytes.size - offset - CHUNK_HEADER_BYTES - U32_BYTES
    // The length is an unsigned 32-bit value, compared as a Long so that it cannot wrap.
    val length = if (room < 0) return null else readU32BigEndian(bytes, offset)
    if (length > room) return null
    return Chunk(type = readU32BigEndian(bytes, offset + U32_BYTES).toInt(), start = offset, dataLength = length.toInt())
}

private fun Chunk.isPictureChunk(bytes: ByteArray): Boolean {
    val lengths = PICTURE_CHUNK_LENGTHS[type] ?: return false
    return dataLength in lengths && (type != ICCP || profileFits(bytes, dataStart, dataLength))
}

/** True for iCCP data that is a name, a terminator, method 0 and a stream within the profile cap. */
private fun profileFits(bytes: ByteArray, start: Int, length: Int): Boolean {
    val nameEnd =
        (0 until minOf(length, MAX_PROFILE_NAME_BYTES + 1)).firstOrNull { bytes[start + it] == NUL } ?: return false
    val streamStart = nameEnd + 2
    if (length < streamStart || bytes[start + nameEnd + 1] != DEFLATE) return false
    return inflatesWithin(bytes, start + streamStart, length - streamStart, PngPicture.MAX_PROFILE_BYTES)
}

/** Inflates into a scratch buffer and keeps nothing: true for a whole stream of at most [limit] bytes. */
private fun inflatesWithin(bytes: ByteArray, start: Int, length: Int, limit: Int): Boolean {
    val inflater = Inflater()
    return try {
        inflater.setInput(bytes, start, length)
        val scratch = ByteArray(INFLATE_SCRATCH_BYTES)
        var total = 0
        do {
            // Zero means the stream ended, was cut short, or asks for a dictionary.
            val count = inflater.inflate(scratch)
            total += count
        } while (count > 0 && total <= limit)
        inflater.finished() && total <= limit
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

/** The chunks kept once each ahead of the image data, with the data lengths the PNG format gives them. */
private val PICTURE_CHUNK_LENGTHS: Map<Int, IntRange> =
    mapOf(
        chunkType("IHDR") to 13..13,
        chunkType("sRGB") to 1..1,
        chunkType("gAMA") to 4..4,
        chunkType("cHRM") to 32..32,
        ICCP to 0..Int.MAX_VALUE,
        chunkType("PLTE") to 3..768,
        chunkType("tRNS") to 1..256,
    )

// Length, type, no data, CRC.
private val EMPTY_IEND: ByteArray =
    byteArrayOf(0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())

private const val CHUNK_HEADER_BYTES = 2 * U32_BYTES
private const val MAX_PROFILE_NAME_BYTES = 79
private const val INFLATE_SCRATCH_BYTES = 8 * 1024
private const val NUL: Byte = 0
private const val DEFLATE: Byte = 0
