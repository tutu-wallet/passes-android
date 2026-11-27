package `is`.walt.passes.ui

import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ParserConfig
import `is`.walt.passes.image.decode.DecodeSize
import org.junit.Test

/** The pure halves of the display decode: the source ceiling and the fit into the bounds. */
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

    @Test
    fun sourceInsideTheBoundsKeepsItsSize() {
        assertThat(fitWithin(360, 220, ImageRenderBounds.Default)).isEqualTo(DecodeSize(360, 220))
        assertThat(fitWithin(1920, 1920, ImageRenderBounds.Default)).isEqualTo(DecodeSize(1920, 1920))
    }

    @Test
    fun sourceWiderThanTheBoundsScalesBothAxes() {
        assertThat(fitWithin(2000, 100, ImageRenderBounds.Default)).isEqualTo(DecodeSize(1920, 96))
    }

    @Test
    fun sourceTallerThanTheBoundsScalesBothAxes() {
        assertThat(fitWithin(100, 3840, ImageRenderBounds.Default)).isEqualTo(DecodeSize(50, 1920))
    }

    @Test
    fun sourceAtTheCeilingFitsTheDefaultBounds() {
        assertThat(fitWithin(4096, 4096, ImageRenderBounds.Default)).isEqualTo(DecodeSize(1920, 1920))
    }

    @Test
    fun tileBoundsFitTheTighterAxis() {
        val tile = ImageRenderBounds(maxWidthPx = 200, maxHeightPx = 300)
        assertThat(fitWithin(1000, 500, tile)).isEqualTo(DecodeSize(200, 100))
        assertThat(fitWithin(500, 1500, tile)).isEqualTo(DecodeSize(100, 300))
    }

    @Test
    fun extremeAspectKeepsAtLeastOnePixel() {
        assertThat(fitWithin(16_777_216, 1, ImageRenderBounds.Default)).isEqualTo(DecodeSize(1920, 1))
        assertThat(fitWithin(1, 16_777_216, ImageRenderBounds.Default)).isEqualTo(DecodeSize(1, 1920))
    }
}
