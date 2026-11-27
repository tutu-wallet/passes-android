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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private typealias Decode = (ByteArray, ImageRenderBounds) -> PassImage

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PassImageCacheTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val bounds = ImageRenderBounds(maxWidthPx = 100, maxHeightPx = 100)
    private val executors = mutableListOf<ExecutorCoroutineDispatcher>()
    private val decodeThread = threads(1)

    @After
    fun closeThreads() = executors.forEach { it.close() }

    @Test
    fun aCachedImageIsNotDecodedAgain() = runBlocking {
        val cache = PassImageCache()
        val decodes = AtomicInteger()

        val first = load(1, cache, countingDecode(decodes))
        val second = load(1, cache, countingDecode(decodes))

        assertThat(decodes.get()).isEqualTo(1)
        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun theSameBytesAtOtherBoundsAreAnotherEntry() = runBlocking {
        val cache = PassImageCache()
        val decodes = AtomicInteger()

        load(1, cache, countingDecode(decodes))
        load(1, cache, countingDecode(decodes), bounds = ImageRenderBounds.Default)

        assertThat(decodes.get()).isEqualTo(2)
    }

    @Test
    fun withoutACacheEveryLoadDecodes() = runBlocking {
        val decodes = AtomicInteger()

        load(1, cache = null, decode = countingDecode(decodes))
        load(1, cache = null, decode = countingDecode(decodes))

        assertThat(decodes.get()).isEqualTo(2)
    }

    @Test
    fun twoLoadsOfOneImageAtOnceShareTheFirstResultKept() = runBlocking {
        val cache = PassImageCache()
        val bothDecoding = CountDownLatch(2)
        val decode = { _: ByteArray, _: ImageRenderBounds ->
            bothDecoding.countDown()
            bothDecoding.await(5, TimeUnit.SECONDS)
            refused()
        }
        val twoThreads = threads(2)

        val results = List(2) { async { load(1, cache, decode, twoThreads) } }.map { it.await() }

        assertThat(bothDecoding.count).isEqualTo(0)
        assertThat(results[1]).isSameInstanceAs(results[0])
        assertThat(cached(cache, 1)).isSameInstanceAs(results[0])
    }

    @Test
    fun aDecodeThatStartedIsKeptWhenItsCallerLeaves() = runBlocking {
        val cache = PassImageCache()
        val (started, release, decode) = blockingDecode()

        val caller = launch(Dispatchers.Default) { load(1, cache, decode) }
        started.await(5, TimeUnit.SECONDS)
        caller.cancel()
        release.countDown()
        caller.join()

        assertThat(cached(cache, 1)).isNotNull()
    }

    @Test
    fun aClearDuringADecodeDropsItsResult() = runBlocking {
        val cache = PassImageCache()
        val (started, release, decode) = blockingDecode()

        val result = async(Dispatchers.Default) { load(1, cache, decode) }
        started.await(5, TimeUnit.SECONDS)
        cache.clear()
        release.countDown()

        assertThat(result.await()).isInstanceOf(PassImage.Refused::class.java)
        assertThat(cached(cache, 1)).isNull()
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

        val first = launch(start = CoroutineStart.UNDISPATCHED) { load(1, cache, decode) }
        running.await(5, TimeUnit.SECONDS)
        val queued = launch(start = CoroutineStart.UNDISPATCHED) { load(2, cache, decode) }
        queued.cancel()
        release.countDown()
        joinAll(first, queued)

        assertThat(decoded).containsExactly(1)
        assertThat(cached(cache, 2)).isNull()
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
            List(8) { launch { load(it, cache = null, decode = decode, dispatcher = passImageDecodeDispatcher) } }
                .joinAll()
        }

        assertThat(DECODE_PARALLELISM).isEqualTo(2)
        assertThat(peak.get()).isEqualTo(DECODE_PARALLELISM)
    }

    @Test
    fun aDecodedImageCountsItsPixelsAgainstTheBudget() {
        val pixels = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val rest = image(1).bytes.size + PassImageCache.ENTRY_OVERHEAD_BYTES

        val tight = PassImageCache(maxBytes = pixels.allocationByteCount + rest - 1)
        tight.lookup(image(1), bounds).keep(PassImage.Decoded(pixels))
        val exact = PassImageCache(maxBytes = pixels.allocationByteCount + rest)
        exact.lookup(image(1), bounds).keep(PassImage.Decoded(pixels))

        assertThat(cached(tight, 1)).isNull()
        assertThat(cached(exact, 1)).isNotNull()
    }

    @Test
    fun refusalsCountTheirBytesAgainstTheBudget() {
        val cache = PassImageCache(maxBytes = 2 * (image(1).bytes.size + PassImageCache.ENTRY_OVERHEAD_BYTES))

        for (seed in 1..3) cache.lookup(image(seed), bounds).keep(refused())

        assertThat(cached(cache, 1)).isNull()
        assertThat(cached(cache, 2)).isNotNull()
        assertThat(cached(cache, 3)).isNotNull()
    }

    @Test
    fun clearDropsEveryEntry() {
        val cache = PassImageCache()
        cache.lookup(image(1), bounds).keep(refused())

        cache.clear()

        assertThat(cached(cache, 1)).isNull()
    }

    @Test
    fun aCachedImageDrawsInTheFirstFrame() {
        val cache = PassImageCache()
        cache.lookup(image(1), bounds).keep(PassImage.Decoded(Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)))
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
    fun aRefusalKeptForARowThatLeftIsReportedByTheNextToShowIt() {
        val cache = PassImageCache()
        cache.lookup(image(1), bounds).keep(refused())

        val reports = showTwice(cache)

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
        if (cache == null) composeRule.waitUntil(WAIT_MS) { reports.size == 2 } else settleDecodes()
        return reports.toList()
    }

    // Lets anything queued on the decode dispatcher start and finish before the reports are read.
    private fun settleDecodes() = runBlocking {
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

    private suspend fun load(
        seed: Int,
        cache: PassImageCache?,
        decode: Decode,
        dispatcher: CoroutineDispatcher = decodeThread,
        bounds: ImageRenderBounds = this.bounds,
    ): PassImage = loadPassImage(image(seed), bounds, cache?.lookup(image(seed), bounds), dispatcher, decode)

    private fun cached(cache: PassImageCache, seed: Int): PassImage? = cache.lookup(image(seed), bounds).cached()

    // Not a PNG, so the decode refuses it from the bytes without the platform decoder.
    private fun image(seed: Int) = ImageBytes(ByteArray(16) { seed.toByte() })

    private fun refused() = PassImage.Refused(ImageDecodeRejection.NotPng)

    private fun countingDecode(count: AtomicInteger) = { _: ByteArray, _: ImageRenderBounds ->
        count.incrementAndGet()
        refused()
    }

    /** A decode that signals its start and waits for release. */
    private fun blockingDecode(): Triple<CountDownLatch, CountDownLatch, Decode> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        return Triple(started, release) { _, _ ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            refused()
        }
    }

    private fun threads(count: Int) =
        Executors.newFixedThreadPool(count).asCoroutineDispatcher().also { executors += it }

    private companion object {
        const val DESCRIPTION = "logo"
        const val WAIT_MS = 5_000L
    }
}
