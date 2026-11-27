package `is`.walt.passes.ui

import android.graphics.Bitmap
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The only path from pass image bytes to pixels: a bounded `ImageDecoder` decode of a PNG to at most
 * [bounds]. A refused image draws nothing and reports to [telemetry]; an [OutOfMemoryError] propagates.
 */
@Composable
public fun BoundedImage(
    bytes: ImageBytes,
    @Suppress("UNUSED_PARAMETER") role: ImageRole,
    contentDescription: String?,
    telemetry: UiTelemetryGuard,
    modifier: Modifier = Modifier,
    bounds: ImageRenderBounds = ImageRenderBounds.Default,
) {
    var bitmap by remember(bytes, bounds) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(bytes, bounds) {
        val (decoded, reason) = withContext(Dispatchers.IO) {
            decodeBoundedBitmap(bytes.bytes, bounds)
        }
        bitmap = decoded
        reason?.let { telemetry.onImageDecodeRejected(it) }
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
internal fun decodeBoundedBitmap(
    rawBytes: ByteArray,
    bounds: ImageRenderBounds,
): Pair<Bitmap?, ImageDecodeRejection?> {
    pngRejection(rawBytes)?.let { return null to it }
    // The platform reader holds what metadata chunks carry, so it is given the picture's chunks alone.
    val picture = PngPicture.of(rawBytes)
    val decoded =
        if (picture == null) {
            BoundedBitmap.Rejected(ImageDecodeRejection.Malformed)
        } else {
            decodeBounded(rawBytes = picture, policy = displayPolicy(bounds))
        }
    return when (decoded) {
        is BoundedBitmap.Decoded -> decoded.bitmap to null
        is BoundedBitmap.Rejected -> null to decoded.reason
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
