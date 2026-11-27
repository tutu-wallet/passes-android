package `is`.walt.passes.ui

import android.graphics.ImageDecoder
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import `is`.walt.passes.core.ImageBytes
import `is`.walt.passes.core.ImageRole
import `is`.walt.passes.core.PngHeader
import `is`.walt.passes.core.PngPicture
import `is`.walt.passes.image.decode.BoundedBitmap
import `is`.walt.passes.image.decode.BoundedDecodePolicy
import `is`.walt.passes.image.decode.DecodeSize
import `is`.walt.passes.image.decode.decodeBounded
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only path from pass image bytes, or a document's stored thumbnail, to pixels: a bounded PNG decode to at
 * most [bounds], kept in [cache] if given. A refusal draws nothing and reports to [telemetry]; an OOM propagates.
 */
@Composable
@Suppress("LongParameterList")
public fun BoundedImage(
    bytes: ImageBytes,
    @Suppress("UNUSED_PARAMETER") role: ImageRole,
    contentDescription: String?,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    bounds: ImageRenderBounds = ImageRenderBounds.Default,
    cache: PassImageCache? = null,
) {
    // Read during composition, so a cached image draws in the first frame.
    val lookup = remember(bytes, bounds, cache) { cache?.lookup(bytes, bounds) }
    val cached = remember(lookup) { lookup?.cached() }
    var bitmap by remember(bytes, bounds, cache) { mutableStateOf((cached as? PassImage.Decoded)?.bitmap) }

    LaunchedEffect(bytes, bounds, cache) {
        when (val image = cached ?: loadPassImage(bytes, bounds, lookup)) {
            is PassImage.Decoded -> bitmap = image.bitmap
            is PassImage.Refused -> telemetry.onImageDecodeRejected(image.reason)
        }
    }

    val current = bitmap
    if (current != null) {
        Image(
            painter = BitmapPainter(current.asImageBitmap()),
            contentDescription = contentDescription,
            modifier = modifier,
        )
    } else {
        Spacer(modifier)
    }
}

/**
 * Pass image decodes running at once in the process. The codec can hold an interlaced source whole,
 * so the first show of a list would otherwise hold one per visible image.
 */
internal const val DECODE_PARALLELISM: Int = 2

internal val passImageDecodeDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(DECODE_PARALLELISM)

/**
 * Decodes off the caller's thread and keeps the result there, so a decode that has started is kept
 * when its caller leaves. One still queued when its caller leaves never runs.
 */
internal suspend fun loadPassImage(
    bytes: ImageBytes,
    bounds: ImageRenderBounds,
    lookup: PassImageCache.Lookup?,
    dispatcher: CoroutineDispatcher = passImageDecodeDispatcher,
    decode: (ByteArray, ImageRenderBounds) -> PassImage = ::decodePassImage,
): PassImage =
    withContext(dispatcher) {
        // Again here: a decode of the same image queued ahead of this one may have kept it.
        lookup?.cached() ?: decode(bytes.bytes, bounds).let { lookup?.keep(it) ?: it }
    }

/**
 * Source pixel count above which an image is refused from its header, before the bitmap is
 * allocated. Equal to the parser's default cap, so a non-interlaced PNG a default import admits can render.
 */
internal const val MAX_SOURCE_PIXELS: Long = 4096L * 4096L

/**
 * The lower ceiling of an interlaced PNG, which the platform codec holds whole at any output
 * size: the area of [ImageRenderBounds.Default].
 */
internal const val MAX_INTERLACED_SOURCE_PIXELS: Long = 1920L * 1920L

private const val PNG_MIME_TYPE = "image/png"

/** The refusal read from the bytes themselves. A refused image is never given to the platform decoder. */
internal fun pngRejection(rawBytes: ByteArray): ImageDecodeRejection? {
    val header = PngHeader.read(rawBytes) ?: return ImageDecodeRejection.NotPng
    val ceiling = if (header.interlaced) MAX_INTERLACED_SOURCE_PIXELS else MAX_SOURCE_PIXELS
    return if (header.exceedsPixelCount(ceiling)) ImageDecodeRejection.ExceedsArea else null
}

/** The refusal again, from the type and size the platform decoder reports. It cannot see interlacing. */
internal fun sourceRejection(mimeType: String?, width: Int, height: Int): ImageDecodeRejection? =
    when {
        mimeType != PNG_MIME_TYPE -> ImageDecodeRejection.NotPng
        width.toLong() * height.toLong() > MAX_SOURCE_PIXELS -> ImageDecodeRejection.ExceedsArea
        else -> null
    }

/** The decode behind [BoundedImage], split out so tests can drive it without a composition. */
internal fun decodePassImage(
    rawBytes: ByteArray,
    bounds: ImageRenderBounds,
): PassImage {
    pngRejection(rawBytes)?.let { return PassImage.Refused(it) }
    // Only the chunks that draw reach the platform reader, so a metadata chunk costs the decode nothing.
    val picture = PngPicture.of(rawBytes)
    val decoded =
        if (picture == null) {
            BoundedBitmap.Rejected(ImageDecodeRejection.Malformed)
        } else {
            decodeBounded(rawBytes = picture, policy = displayPolicy(bounds))
        }
    return when (decoded) {
        is BoundedBitmap.Decoded -> PassImage.Decoded(decoded.bitmap)
        is BoundedBitmap.Rejected -> PassImage.Refused(decoded.reason)
    }
}

private fun displayPolicy(bounds: ImageRenderBounds): BoundedDecodePolicy<ImageDecodeRejection> =
    BoundedDecodePolicy(
        // Display only, so a hardware bitmap is fine.
        allocator = ImageDecoder.ALLOCATOR_DEFAULT,
        gate = ::sourceRejection,
        maxSize = DecodeSize(bounds.maxWidthPx, bounds.maxHeightPx),
        onMalformed = { ImageDecodeRejection.Malformed },
        onRuntimeFailure = { ImageDecodeRejection.Other },
        // The host has no rejection bucket for an OOM, so it propagates.
        onOutOfMemory = null,
    )
