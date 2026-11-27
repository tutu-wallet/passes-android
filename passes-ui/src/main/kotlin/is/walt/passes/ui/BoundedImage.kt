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
import `is`.walt.passes.image.decode.BoundedBitmap
import `is`.walt.passes.image.decode.BoundedDecodePolicy
import `is`.walt.passes.image.decode.DecodeSize
import `is`.walt.passes.image.decode.decodeBounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The only path from pass image bytes to pixels: a bounded, off-main-thread `ImageDecoder` decode
 * to at most [bounds]. A refused image draws nothing and reports its reason to [telemetry].
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
 * allocated. Equal to the parser's default cap, so an image a default import admits can render.
 */
internal const val MAX_SOURCE_PIXELS: Long = 4096L * 4096L

internal fun sourceRejection(width: Int, height: Int): ImageDecodeRejection? =
    if (width.toLong() * height.toLong() > MAX_SOURCE_PIXELS) ImageDecodeRejection.ExceedsArea else null

/** The aspect-preserving fit of a [width] x [height] source into [bounds], never upscaled. */
internal fun fitWithin(width: Int, height: Int, bounds: ImageRenderBounds): DecodeSize {
    val scale = minOf(bounds.maxWidthPx.toDouble() / width, bounds.maxHeightPx.toDouble() / height, 1.0)
    return DecodeSize(
        width = (width * scale).roundToInt().coerceIn(1, bounds.maxWidthPx),
        height = (height * scale).roundToInt().coerceIn(1, bounds.maxHeightPx),
    )
}

/** The decode behind [BoundedImage], split out so tests can drive it without a composition. */
internal fun decodeBoundedBitmap(
    rawBytes: ByteArray,
    bounds: ImageRenderBounds,
): Pair<Bitmap?, ImageDecodeRejection?> =
    when (
        val decoded =
            decodeBounded(
                rawBytes = rawBytes,
                policy =
                    BoundedDecodePolicy(
                        // Display only, so a hardware bitmap is fine.
                        allocator = ImageDecoder.ALLOCATOR_DEFAULT,
                        gate = { _, width, height -> sourceRejection(width, height) },
                        target = { width, height -> fitWithin(width, height, bounds) },
                        onMalformed = { ImageDecodeRejection.Malformed },
                        onRuntimeFailure = { ImageDecodeRejection.Other },
                        // The host has no rejection bucket for an OOM, so it propagates.
                        onOutOfMemory = null,
                    ),
            )
    ) {
        is BoundedBitmap.Decoded -> decoded.bitmap to null
        is BoundedBitmap.Rejected -> null to decoded.reason
    }
