package `is`.walt.passes.document

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThumbnailSizeTest {
    @Test
    fun landscapeRasterFitsTheBoxWidth() {
        assertThat(thumbnailSize(2048, 1536)).isEqualTo(ThumbnailSize(600, 450))
    }

    @Test
    fun portraitRasterFillsTheBox() {
        assertThat(thumbnailSize(1536, 2048)).isEqualTo(ThumbnailSize(600, 800))
    }

    @Test
    fun rasterInsideTheBoxKeepsItsSize() {
        assertThat(thumbnailSize(300, 200)).isEqualTo(ThumbnailSize(300, 200))
        assertThat(thumbnailSize(600, 800)).isEqualTo(ThumbnailSize(600, 800))
    }

    @Test
    fun extremeAspectKeepsAtLeastOnePixel() {
        assertThat(thumbnailSize(2048, 1)).isEqualTo(ThumbnailSize(600, 1))
        assertThat(thumbnailSize(1, 2048)).isEqualTo(ThumbnailSize(1, 800))
    }
}
