package `is`.walt.passes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PassImageCacheTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val bounds = ImageRenderBounds(maxWidthPx = 100, maxHeightPx = 100)
    private val decodeThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    @Test
    fun aCachedImageIsNotDecodedAgain() = runBlocking {
        val cache = PassImageCache()
        val decodes = AtomicInteger()
        val decode = countingDecode(decodes)

        val first = loadPassImage(image(1), bounds, cache, decodeThread, decode)
        val second = loadPassImage(image(1), bounds, cache, decodeThread, decode)

        assertThat(decodes.get()).isEqualTo(1)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun theSameBytesAtOtherBoundsAreAnotherEntry() = runBlocking {
        val cache = PassImageCache()
        val decodes = AtomicInteger()
        val decode = countingDecode(decodes)

        loadPassImage(image(1), bounds, cache, decodeThread, decode)
        loadPassImage(image(1), ImageRenderBounds.Default, cache, decodeThread, decode)

        assertThat(decodes.get()).isEqualTo(2)
    }

    @Test
    fun withoutACacheEveryLoadDecodes() = runBlocking {
        val decodes = AtomicInteger()
        val decode = countingDecode(decodes)

        loadPassImage(image(1), bounds, cache = null, decodeThread, decode)
        loadPassImage(image(1), bounds, cache = null, decodeThread, decode)

        assertThat(decodes.get()).isEqualTo(2)
    }

    @Test
    fun aDecodeThatStartedLandsInTheCacheWhenItsCallerLeaves() = runBlocking {
        val cache = PassImageCache()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decode = { _: ByteArray, _: ImageRenderBounds ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            refused()
        }

        val caller = launch(Dispatchers.Default) { loadPassImage(image(1), bounds, cache, decodeThread, decode) }
        started.await(5, TimeUnit.SECONDS)
        caller.cancel()
        release.countDown()
        caller.join()

        assertThat(cache.get(image(1), bounds)).isNotNull()
    }

    @Test
    fun aLoadCancelledWhileQueuedDoesNotDecode() = runBlocking {
        val cache = PassImageCache()
        val decoded = CopyOnWriteArrayList<Int>()
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val decode = { bytes: ByteArray, _: ImageRenderBounds ->
            decoded += bytes.first().toInt()
            running.countDown()
            release.await(5, TimeUnit.SECONDS)
            refused()
        }

        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            loadPassImage(image(1), bounds, cache, decodeThread, decode)
        }
        running.await(5, TimeUnit.SECONDS)
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            loadPassImage(image(2), bounds, cache, decodeThread, decode)
        }
        queued.cancel()
        release.countDown()
        joinAll(first, queued)

        assertThat(decoded).containsExactly(1)
        assertThat(cache.get(image(2), bounds)).isNull()
    }

    @Test
    fun decodesRunAtMostTwoAtATime() = runBlocking {
        val running = AtomicInteger()
        val peak = AtomicInteger()
        val twoRunning = CountDownLatch(2)
        val decode = { _: ByteArray, _: ImageRenderBounds ->
            peak.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            twoRunning.countDown()
            twoRunning.await(1, TimeUnit.SECONDS)
            Thread.sleep(20)
            running.decrementAndGet()
            refused()
        }

        coroutineScope {
            List(8) { launch { loadPassImage(image(it), bounds, cache = null, decode = decode) } }.joinAll()
        }

        assertThat(DECODE_PARALLELISM).isEqualTo(2)
        assertThat(peak.get()).isEqualTo(DECODE_PARALLELISM)
    }

    @Test
    fun aDecodedImageCountsItsPixelsAgainstTheBudget() {
        val pixels = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val pixelBytes = pixels.allocationByteCount

        val tight = PassImageCache(maxBytes = pixelBytes)
        tight.put(image(1), bounds, PassImage.Decoded(pixels))
        val roomy = PassImageCache(maxBytes = pixelBytes * 2)
        roomy.put(image(1), bounds, PassImage.Decoded(pixels))

        assertThat(tight.get(image(1), bounds)).isNull()
        assertThat(roomy.get(image(1), bounds)).isNotNull()
    }

    @Test
    fun clearDropsEveryEntry() {
        val cache = PassImageCache()
        cache.put(image(1), bounds, refused())

        cache.clear()

        assertThat(cache.get(image(1), bounds)).isNull()
    }

    @Test
    fun aCachedImageDrawsInTheFirstFrame() {
        val cache = PassImageCache()
        cache.put(image(1), bounds, PassImage.Decoded(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)))
        // With the clock held, a state written after the first composition is never drawn.
        composeRule.mainClock.autoAdvance = false

        composeRule.setContent { TestImage(image(1), cache, NoopUiTelemetryGuard) }

        composeRule.onNodeWithContentDescription(DESCRIPTION).assertExists()
    }

    @Test
    fun aRefusalIsReportedOnceWhileTheCacheHoldsIt() {
        val reports = showTwice(PassImageCache())

        assertThat(reports).containsExactly(ImageDecodeRejection.NotPng)
    }

    @Test
    fun withoutACacheARefusalIsReportedOnEachEntry() {
        val reports = showTwice(cache = null)

        assertThat(reports).containsExactly(ImageDecodeRejection.NotPng, ImageDecodeRejection.NotPng)
    }

    /** Shows bytes that are not a PNG, takes them out of composition and shows them again. */
    private fun showTwice(cache: PassImageCache?): List<ImageDecodeRejection> {
        val reports = CopyOnWriteArrayList<ImageDecodeRejection>()
        val telemetry =
            object : UiTelemetryGuard by NoopUiTelemetryGuard {
                override fun onImageDecodeRejected(reason: ImageDecodeRejection) {
                    reports += reason
                }
            }
        var shown by mutableStateOf(true)
        composeRule.setContent { if (shown) TestImage(image(1), cache, telemetry) }
        composeRule.waitUntil(WAIT_MS) { reports.isNotEmpty() }

        shown = false
        composeRule.waitForIdle()
        shown = true
        composeRule.waitForIdle()
        if (cache == null) composeRule.waitUntil(WAIT_MS) { reports.size == 2 } else drainDecodes()
        return reports.toList()
    }

    // Anything queued on the decode dispatcher before this call has run when it returns.
    private fun drainDecodes() = runBlocking {
        List(DECODE_PARALLELISM) { launch(passImageDecodeDispatcher) { Thread.sleep(50) } }.joinAll()
        composeRule.waitForIdle()
    }

    @Composable
    private fun TestImage(bytes: ImageBytes, cache: PassImageCache?, telemetry: UiTelemetryGuard) {
        BoundedImage(
            bytes = bytes,
            role = ImageRole.Logo,
            contentDescription = DESCRIPTION,
            telemetry = telemetry,
            modifier = Modifier.size(48.dp),
            bounds = bounds,
            cache = cache,
        )
    }

    // Not a PNG, so the decode refuses it from the bytes without the platform decoder.
    private fun image(seed: Int) = ImageBytes(ByteArray(16) { seed.toByte() })

    private fun refused() = PassImage.Refused(ImageDecodeRejection.NotPng)

    private fun countingDecode(count: AtomicInteger) = { _: ByteArray, _: ImageRenderBounds ->
        count.incrementAndGet()
        refused()
    }

    private companion object {
        const val DESCRIPTION = "logo"
        const val WAIT_MS = 5_000L
    }
}
