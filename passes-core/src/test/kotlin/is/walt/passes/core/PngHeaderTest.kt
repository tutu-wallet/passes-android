package `is`.walt.passes.core

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.internal.SyntheticPkpass
import org.junit.Test

class PngHeaderTest {
    @Test
    fun readsTheDeclaredSizeOfAPng() {
        val header = PngHeader.read(SyntheticPkpass.fakePng(widthDeclared = 360, heightDeclared = 220))

        assertThat(header?.width).isEqualTo(360L)
        assertThat(header?.height).isEqualTo(220L)
        assertThat(header?.interlaced).isFalse()
    }

    @Test
    fun readsTheInterlaceFlag() {
        val png = SyntheticPkpass.fakePng().also { it[INTERLACE_OFFSET] = 1 }

        assertThat(PngHeader.read(png)?.interlaced).isTrue()
    }

    @Test
    fun anUnknownInterlaceMethodCountsAsInterlaced() {
        val png = SyntheticPkpass.fakePng().also { it[INTERLACE_OFFSET] = 7 }

        assertThat(PngHeader.read(png)?.interlaced).isTrue()
    }

    @Test
    fun readsASideWithTheHighBitSetAsUnsigned() {
        val png = SyntheticPkpass.fakePng(widthDeclared = -1, heightDeclared = 1)

        assertThat(PngHeader.read(png)?.width).isEqualTo(0xFFFF_FFFFL)
    }

    @Test
    fun pixelCountIsComparedWithoutOverflow() {
        val header = PngHeader.read(SyntheticPkpass.fakePng(widthDeclared = 4096, heightDeclared = 4096))!!
        val largest = PngHeader.read(SyntheticPkpass.fakePng(widthDeclared = -1, heightDeclared = -1))!!

        assertThat(header.exceedsPixelCount(4096L * 4096L)).isFalse()
        assertThat(header.exceedsPixelCount(4096L * 4096L - 1)).isTrue()
        assertThat(header.exceedsPixelCount(0L)).isTrue()
        // 4294967295 squared is over Long.MAX_VALUE, where a multiplication would wrap.
        assertThat(largest.exceedsPixelCount(Long.MAX_VALUE)).isTrue()
    }

    @Test
    fun otherContainersAreNotAPng() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
        val gif = "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(64)
        val webp = "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WEBPVP8 ".toByteArray(Charsets.US_ASCII)
        val heif = ByteArray(4) + "ftypheic".toByteArray(Charsets.US_ASCII) + ByteArray(64)

        for (bytes in listOf(jpeg, gif, webp, heif, ByteArray(64), ByteArray(0))) {
            assertThat(PngHeader.read(bytes)).isNull()
        }
    }

    @Test
    fun aPngCutOffInsideItsHeaderIsNotAPng() {
        val png = SyntheticPkpass.fakePng()

        assertThat(PngHeader.read(png.copyOf(INTERLACE_OFFSET))).isNull()
        assertThat(PngHeader.read(png.copyOf(INTERLACE_OFFSET + 1))).isNotNull()
    }

    @Test
    fun aSignatureWithoutAnIhdrChunkFirstIsNotAPng() {
        val png = SyntheticPkpass.fakePng().also { it[IHDR_TYPE_OFFSET] = 'X'.code.toByte() }

        assertThat(PngHeader.read(png)).isNull()
    }

    @Test
    fun anIhdrChunkOfAnotherLengthIsNotAPng() {
        val png = SyntheticPkpass.fakePng().also { it[IHDR_LENGTH_OFFSET] = 14 }

        assertThat(PngHeader.read(png)).isNull()
    }

    @Test
    fun aZeroSideIsNotAPng() {
        assertThat(PngHeader.read(SyntheticPkpass.fakePng(widthDeclared = 0, heightDeclared = 4))).isNull()
        assertThat(PngHeader.read(SyntheticPkpass.fakePng(widthDeclared = 4, heightDeclared = 0))).isNull()
    }

    private companion object {
        const val IHDR_LENGTH_OFFSET = 11
        const val IHDR_TYPE_OFFSET = 12
        const val INTERLACE_OFFSET = 28
    }
}
