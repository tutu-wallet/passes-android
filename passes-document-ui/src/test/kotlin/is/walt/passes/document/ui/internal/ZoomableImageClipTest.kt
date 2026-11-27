package `is`.walt.passes.document.ui.internal

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * Zoomed [ZoomableImage] content never draws outside its slot onto host content beside it (ADR 0005
 * Z.7). Renders to pixels, since layout bounds cannot see `graphicsLayer` overdraw.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZoomableImageClipTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun doubleTapZoomedBitmapNeverDrawsOutsideItsSlot() {
        val red = Bitmap.createBitmap(40, 80, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(RED) }
            .asImageBitmap()

        composeRule.setContent {
            // A zoom slot with an unpainted neighbour band below it, so overdraw is not masked.
            Box(modifier = Modifier.size(200.dp, 400.dp).background(Color.White)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxWidth().height(340.dp)) {
                        ZoomableImage(
                            bitmap = red,
                            contentDescription = "zoom-target",
                            modifier = Modifier.fillMaxSize(),
                            pageAspect = 0.5f,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("neighbour"),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("zoom-target").performTouchInput {
            doubleClick()
        }
        composeRule.waitForIdle()

        val view = (composeRule.onRoot().fetchSemanticsNode().root as ViewRootForTest).view
        val capture = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(capture))
        val density = composeRule.density.density
        val neighbourTopPx = (
            composeRule.onNodeWithTag("neighbour").getUnclippedBoundsInRoot().top.value * density
            ).roundToInt()

        val counts = countRed(capture, neighbourTopPx)
        // Sanity: the red bitmap rendered AND the double-tap zoom engaged — otherwise
        // the neighbour assertion would pass vacuously against an unzoomed (in-bounds) fit.
        assertThat(counts.inSlot).isGreaterThan(0)
        assertThat(counts.atSlotEdge).isGreaterThan(0)
        assertWithMessage(
            "zoomed content pixels drew into the neighbour band below the slot (Z.7); " +
                "ZoomableImage must clip to its own bounds",
        ).that(counts.inNeighbour).isEqualTo(0)
    }

    private data class RedCounts(val inSlot: Int, val inNeighbour: Int, val atSlotEdge: Int)

    private fun countRed(capture: Bitmap, neighbourTopPx: Int): RedCounts {
        var inSlot = 0
        var inNeighbour = 0
        var atSlotEdge = 0
        for (i in 0 until capture.width * capture.height) {
            val x = i % capture.width
            val y = i / capture.width
            if (capture.getPixel(x, y) != RED) continue
            if (y >= neighbourTopPx) inNeighbour++ else inSlot++
            // At 1x the 0.5-aspect fit leaves letterbox gutters at the slot's horizontal
            // edges; red there proves the 2x zoom really engaged.
            if (y < neighbourTopPx && (x < 4 || x > capture.width - 5)) atSlotEdge++
        }
        return RedCounts(inSlot, inNeighbour, atSlotEdge)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
