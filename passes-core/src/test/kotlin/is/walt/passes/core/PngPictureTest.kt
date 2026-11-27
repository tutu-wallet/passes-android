package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class PngPictureTest {
    @Test
    fun keepsOnlyTheChunksThatCarryThePicture() {
        val picture =
            listOf(
                IHDR,
                "sRGB" to ByteArray(1),
                "gAMA" to ByteArray(4),
                "cHRM" to ByteArray(32),
                "PLTE" to ByteArray(768),
                "tRNS" to ByteArray(256),
                "IDAT" to byteArrayOf(1, 2, 3),
                "IDAT" to byteArrayOf(4),
                IEND,
            )
        val baggage =
            listOf("tEXt", "zTXt", "iTXt", "eXIf", "pHYs", "sBIT", "bKGD", "tIME", "sPLT", "hIST", "cICP") +
                listOf("acTL", "fcTL", "fdAT", "gmAP", "gdAT", "npTc", "npLb", "npOl", "abCd", "ABCD")
        val withBaggage =
            picture.take(1) + baggage.map { it to ByteArray(40) } + picture.drop(1)

        assertThat(PngPicture.of(png(withBaggage))).isEqualTo(png(picture))
    }

    @Test
    fun aPngThatIsAllPictureComesBackByteForByte() {
        val png = png(listOf(IHDR, "PLTE" to ByteArray(3), "IDAT" to byteArrayOf(7, 7), IEND))

        assertThat(PngPicture.of(png)).isEqualTo(png)
    }

    @Test
    fun keepsOnlyTheFirstOfEachChunkButEveryImageDataChunk() {
        val first = listOf("sRGB" to byteArrayOf(1), "gAMA" to ByteArray(4), "cHRM" to ByteArray(32))
        val palette = listOf("PLTE" to byteArrayOf(1, 1, 1), "tRNS" to byteArrayOf(1))
        val again = listOf(IHDR, "sRGB" to byteArrayOf(2), "gAMA" to ByteArray(4), "cHRM" to ByteArray(32))
        val paletteAgain = listOf("PLTE" to byteArrayOf(2, 2, 2), "tRNS" to byteArrayOf(2))
        val data = listOf("IDAT" to byteArrayOf(1), "IDAT" to ByteArray(0), "IDAT" to byteArrayOf(3))

        val picture = PngPicture.of(png(listOf(IHDR) + first + palette + again + paletteAgain + data + IEND))

        assertThat(picture).isEqualTo(png(listOf(IHDR) + first + palette + data + IEND))
    }

    @Test
    fun dropsAChunkWhoseLengthIsNotItsKinds() {
        val wrong =
            listOf(
                "sRGB" to ByteArray(2),
                "gAMA" to ByteArray(5),
                "cHRM" to ByteArray(31),
                "PLTE" to ByteArray(769),
                "tRNS" to ByteArray(257),
            )

        val picture = PngPicture.of(png(listOf(IHDR) + wrong + ("IDAT" to byteArrayOf(1)) + IEND))

        assertThat(picture).isEqualTo(png(listOf(IHDR, "IDAT" to byteArrayOf(1), IEND)))
    }

    @Test
    fun keepsNothingButImageDataOnceItHasBegun() {
        val late = listOf("PLTE" to ByteArray(3), "tRNS" to ByteArray(1), "sRGB" to ByteArray(1), "zTXt" to ByteArray(9))
        val chunks = listOf(IHDR, "IDAT" to byteArrayOf(1)) + late + ("IDAT" to byteArrayOf(2)) + late + IEND

        val picture = PngPicture.of(png(chunks))

        assertThat(picture).isEqualTo(png(listOf(IHDR, "IDAT" to byteArrayOf(1), "IDAT" to byteArrayOf(2), IEND)))
    }

    @Test
    fun endsAtTheFirstEndChunkAndWritesItEmpty() {
        val clean = png(listOf(IHDR, "IDAT" to byteArrayOf(1), IEND))
        val trailing = clean + png(listOf(IHDR, "zTXt" to ByteArray(64), "IDAT" to byteArrayOf(9), IEND)) + ByteArray(5)
        val endWithData = png(listOf(IHDR, "IDAT" to byteArrayOf(1), "IEND" to ByteArray(64)))

        assertThat(PngPicture.of(trailing)).isEqualTo(clean)
        assertThat(PngPicture.of(endWithData)).isEqualTo(clean)
    }

    @Test
    fun aPngWithoutAnEndChunkIsGivenOne() {
        val picture = PngPicture.of(png(listOf(IHDR, "tEXt" to ByteArray(8), "IDAT" to byteArrayOf(1))))

        assertThat(picture).isEqualTo(png(listOf(IHDR, "IDAT" to byteArrayOf(1), IEND)))
    }

    @Test
    fun keepsAColourProfileWithinTheCap() {
        val atTheCap = "iCCP" to profileChunk(inflatedBytes = PngPicture.MAX_PROFILE_BYTES)
        val png = png(listOf(IHDR, atTheCap, "IDAT" to byteArrayOf(1), IEND))

        assertThat(PngPicture.MAX_PROFILE_BYTES).isEqualTo(262_144)
        assertThat(PngPicture.of(png)).isEqualTo(png)
    }

    @Test
    fun dropsAColourProfileThatInflatesPastTheCap() {
        val overTheCap = "iCCP" to profileChunk(inflatedBytes = PngPicture.MAX_PROFILE_BYTES + 1)
        val huge = "iCCP" to profileChunk(inflatedBytes = 8_000_000)

        for (profile in listOf(overTheCap, huge)) {
            val picture = PngPicture.of(png(listOf(IHDR, profile, "IDAT" to byteArrayOf(1), IEND)))

            assertThat(picture).isEqualTo(png(listOf(IHDR, "IDAT" to byteArrayOf(1), IEND)))
        }
    }

    @Test
    fun dropsAColourProfileItCannotRead() {
        val good = profileChunk(inflatedBytes = 512)
        val unterminatedName = ByteArray(80) { 'n'.code.toByte() } + good
        val unknownMethod = good.copyOf().also { it[PROFILE_NAME.length + 1] = 1 }
        val truncated = good.copyOf(good.size - 4)
        val notDeflate = "icc".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + ByteArray(64) { 0x55 }
        val nameOnly = "icc".toByteArray(Charsets.US_ASCII)

        for (profile in listOf(unterminatedName, unknownMethod, truncated, notDeflate, nameOnly, ByteArray(0))) {
            val picture = PngPicture.of(png(listOf(IHDR, "iCCP" to profile, "IDAT" to byteArrayOf(1), IEND)))

            assertThat(picture).isEqualTo(png(listOf(IHDR, "IDAT" to byteArrayOf(1), IEND)))
        }
    }

    @Test
    fun bytesThatAreNotAPngHaveNoPicture() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
        val zeroWidth = png(listOf("IHDR" to ByteArray(13), "IDAT" to byteArrayOf(1), IEND))

        for (bytes in listOf(jpeg, zeroWidth, ByteArray(64), ByteArray(0))) {
            assertThat(PngPicture.of(bytes)).isNull()
        }
    }

    @Test
    fun aChunkThatRunsPastTheBytesHasNoPicture() {
        val png = png(listOf(IHDR, "IDAT" to ByteArray(32), IEND))
        val cutInImageData = png.copyOf(png.size - 20)
        val cutInAChunkHeader = png(listOf(IHDR, "IDAT" to ByteArray(32))) + ByteArray(11)
        val cutInTheHeaderChunk = png.copyOf(SIGNATURE.size + 8 + 13 + 3)
        // An unsigned 32-bit length: read signed it is -1, and added to an offset it would wrap.
        val hugeLength = png(listOf(IHDR)) + byteArrayOf(-1, -1, -1, -1) + "tEXt".toByteArray(Charsets.US_ASCII) + ByteArray(64)

        for (bytes in listOf(cutInImageData, cutInAChunkHeader, cutInTheHeaderChunk, hugeLength)) {
            assertThat(PngPicture.of(bytes)).isNull()
        }
    }

    private fun png(chunks: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { out ->
            out.write(SIGNATURE)
            for ((type, data) in chunks) {
                val typed = type.toByteArray(Charsets.US_ASCII) + data
                DataOutputStream(out).apply {
                    writeInt(data.size)
                    write(typed)
                    writeInt(CRC32().apply { update(typed) }.value.toInt())
                }
            }
        }.toByteArray()

    // An iCCP chunk's data: profile name, terminator, compression method 0, a zlib stream.
    private fun profileChunk(inflatedBytes: Int): ByteArray {
        val stream =
            ByteArrayOutputStream().also { out ->
                DeflaterOutputStream(out).use { it.write(ByteArray(inflatedBytes)) }
            }.toByteArray()
        return PROFILE_NAME.toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0) + stream
    }

    private companion object {
        const val PROFILE_NAME = "icc"
        val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val IHDR = "IHDR" to byteArrayOf(0, 0, 0, 1, 0, 0, 0, 1, 8, 0, 0, 0, 0)
        val IEND = "IEND" to ByteArray(0)
    }
}
