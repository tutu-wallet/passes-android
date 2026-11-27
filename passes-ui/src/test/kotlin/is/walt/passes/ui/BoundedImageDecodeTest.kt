package `is`.walt.passes.ui

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ParserConfig
import org.junit.Test

/** The source ceiling of the display decode. The fit into the bounds is `passes-image-decode`'s. */
class BoundedImageDecodeTest {

    @Test
    fun sourceCeilingIs4096SquareAndMatchesTheParserDefault() {
        assertThat(MAX_SOURCE_PIXELS).isEqualTo(16_777_216L)
        assertThat(MAX_SOURCE_PIXELS).isEqualTo(ParserConfig.DEFAULT_MAX_IMAGE_PIXEL_COUNT.toLong())
    }

    @Test
    fun sourceAtTheCeilingIsAccepted() {
        assertThat(sourceRejection(width = 4096, height = 4096)).isNull()
        assertThat(sourceRejection(width = 16_777_216, height = 1)).isNull()
    }

    @Test
    fun sourceOverTheCeilingIsRejected() {
        assertThat(sourceRejection(width = 4097, height = 4096)).isEqualTo(ImageDecodeRejection.ExceedsArea)
    }

    @Test
    fun sourceWhoseAreaOverflowsAnIntIsRejected() {
        assertThat(sourceRejection(width = 65_536, height = 65_536)).isEqualTo(ImageDecodeRejection.ExceedsArea)
        assertThat(sourceRejection(width = Int.MAX_VALUE, height = Int.MAX_VALUE))
            .isEqualTo(ImageDecodeRejection.ExceedsArea)
    }
}
