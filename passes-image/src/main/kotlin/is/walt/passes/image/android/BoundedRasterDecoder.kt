package `is`.walt.passes.image.android

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import `is`.walt.passes.image.decode.BoundedBitmap
import `is`.walt.passes.image.decode.BoundedDecodePolicy
import `is`.walt.passes.image.decode.DecodeSize
import `is`.walt.passes.image.decode.decodeBounded
import `is`.walt.passes.isolation.readBoundedBytes
import kotlin.math.roundToInt

/**
 * Decodes an original user image inside the sandbox to a bounded raster; only that raster crosses the
 * binder. Every cap is checked before the platform decoder allocates the bitmap.
 */
internal fun decodeRasterFromPfd(
    image: ParcelFileDescriptor,
    maxWidthPx: Int,
    maxHeightPx: Int,
    config: ImageDecodeConfig,
): ImageDecodeResult {
    // Refuses an over-cap output request before reading anything, as PdfRendererService does.
    if (!isOutputSizeValid(maxWidthPx, maxHeightPx, config.maxOutputPixels)) {
        return ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
    }
    val read = runCatching { readBoundedBytes(image, config.maxBytes) }
    val bytes = read.getOrNull()
    return when {
        read.isFailure -> ImageDecodeResult.Rejected(ImageDecodeRejectedKind.DecodeFailed)
        bytes == null -> ImageDecodeResult.Rejected(ImageDecodeRejectedKind.OversizedAtImport)
        else -> decodeBoundedRaster(bytes, maxWidthPx, maxHeightPx, config)
    }
}

internal fun isOutputSizeValid(maxWidthPx: Int, maxHeightPx: Int, maxOutputPixels: Long): Boolean =
    maxWidthPx > 0 && maxHeightPx > 0 && maxWidthPx.toLong() * maxHeightPx.toLong() <= maxOutputPixels

/**
 * The header-cap decision, or null to decode: a container outside the allowlist is
 * [ImageDecodeRejectedKind.NotAnImage], an oversized one [ImageDecodeRejectedKind.DimensionsTooLarge].
 */
internal fun headerRejection(
    mimeType: String?,
    width: Int,
    height: Int,
    config: ImageDecodeConfig,
): ImageDecodeRejectedKind? =
    when {
        mimeType !in config.allowedMimeTypes -> ImageDecodeRejectedKind.NotAnImage
        width > config.maxDimensionPx || height > config.maxDimensionPx -> ImageDecodeRejectedKind.DimensionsTooLarge
        width.toLong() * height.toLong() > config.maxAreaPx -> ImageDecodeRejectedKind.DimensionsTooLarge
        else -> null
    }

/** [srcW] x [srcH] fitted into the bound, aspect kept and never enlarged past the source. */
internal fun outputDims(srcW: Int, srcH: Int, maxWidthPx: Int, maxHeightPx: Int): OutputDims {
    val fit = minOf(maxWidthPx.toFloat() / srcW, maxHeightPx.toFloat() / srcH, 1f)
    val w = (srcW * fit).roundToInt().coerceIn(1, maxWidthPx)
    val h = (srcH * fit).roundToInt().coerceIn(1, maxHeightPx)
    return OutputDims(w, h)
}

internal data class OutputDims(val widthPx: Int, val heightPx: Int)

/**
 * Decodes under [rasterPolicy], then scales to the output and packs it into [SharedMemory]. Size and
 * aspect come from the header, so the codec's whole-number division never shifts them.
 */
internal fun decodeBoundedRaster(
    rawBytes: ByteArray,
    maxWidthPx: Int,
    maxHeightPx: Int,
    config: ImageDecodeConfig,
): ImageDecodeResult {
    var header: DecodeSize? = null
    val decoded = decodeBounded(rawBytes, rasterPolicy(maxWidthPx, maxHeightPx, config) { header = it })
    return when (decoded) {
        is BoundedBitmap.Rejected -> ImageDecodeResult.Rejected(decoded.reason)
        is BoundedBitmap.Decoded -> packScaledRaster(decoded.bitmap, checkNotNull(header), maxWidthPx, maxHeightPx)
    }
}

/**
 * The sandbox's decode posture: a software allocator so the pixels can be copied out, the
 * [headerRejection] caps, and OOM folded as a decompression bomb. [onHeader] gets an accepted image's size.
 */
internal fun rasterPolicy(
    maxWidthPx: Int,
    maxHeightPx: Int,
    config: ImageDecodeConfig,
    onHeader: (DecodeSize) -> Unit,
): BoundedDecodePolicy<ImageDecodeRejectedKind> =
    BoundedDecodePolicy(
        allocator = ImageDecoder.ALLOCATOR_SOFTWARE,
        gate = { mimeType, width, height ->
            val rejection = headerRejection(mimeType, width, height, config)
            if (rejection == null) onHeader(DecodeSize(width, height))
            rejection
        },
        // Twice the output, so the whole-number division still covers it and only a downscale follows.
        maxSize = DecodeSize(maxWidthPx * 2, maxHeightPx * 2),
        onMalformed = { ImageDecodeRejectedKind.NotAnImage },
        onRuntimeFailure = { ImageDecodeRejectedKind.DecodeFailed },
        onOutOfMemory = { ImageDecodeRejectedKind.DimensionsTooLarge },
    )

/** Scales [bitmap] to [header] fitted into the bound, copies it into read-only [SharedMemory], recycles both. */
private fun packScaledRaster(bitmap: Bitmap, header: DecodeSize, maxWidthPx: Int, maxHeightPx: Int): ImageDecodeResult {
    val dims = outputDims(header.width, header.height, maxWidthPx, maxHeightPx)
    val scaled =
        if (dims.widthPx == bitmap.width && dims.heightPx == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, dims.widthPx, dims.heightPx, true)
        }
    return try {
        ImageDecodeResult.Ok(
            sharedMemory = packIntoSharedMemory(scaled, dims.widthPx, dims.heightPx),
            widthPx = dims.widthPx,
            heightPx = dims.heightPx,
            sourceAspect = header.width.toFloat() / header.height.toFloat(),
        )
    } finally {
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
    }
}

private fun packIntoSharedMemory(bitmap: Bitmap, widthPx: Int, heightPx: Int): SharedMemory {
    val byteCount = widthPx * heightPx * BYTES_PER_PIXEL
    val sm = SharedMemory.create("walt-image-decode", byteCount)
    val buf = sm.mapReadWrite()
    try {
        bitmap.copyPixelsToBuffer(buf)
    } finally {
        SharedMemory.unmap(buf)
    }
    sm.setProtect(OsConstants.PROT_READ)
    return sm
}

private const val BYTES_PER_PIXEL = 4
