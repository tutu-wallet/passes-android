package `is`.walt.passes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.BarcodeEncoder
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.ui.internal.BARCODE_RENDER_FAILURE_DESCRIPTION
import `is`.walt.passes.ui.internal.barcodeContentScale
import `is`.walt.passes.ui.internal.toMonochromeBitmap

/**
 * [card]'s code alone, described by its label, at the gate-distance minimum sizes of
 * [BarcodeView]. An encoder failure renders a described blank of the same size.
 */
@Composable
public fun ScannableCardView(
    card: ScannableCard,
    modifier: Modifier = Modifier,
) {
    ScannableCodeImage(card, imageDescription = card.label, modifier = modifier)
}

/**
 * The encoded code alone. [imageDescription] is null where the composing surface already
 * announces the label or payload in text, so TalkBack does not read it twice.
 */
@Composable
internal fun ScannableCodeImage(
    card: ScannableCard,
    imageDescription: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(card.payload, card.format) {
        when (val result = BarcodeEncoder.encode(card.payload, card.format)) {
            is EncodeResult.Success -> result.matrix.toMonochromeBitmap()
            is EncodeResult.Failure -> null
        }
    }
    BarcodeImage(card.format, bitmap, imageDescription, modifier)
}

@Composable
private fun BarcodeImage(
    format: ScannableFormat,
    bitmap: Bitmap?,
    imageDescription: String?,
    modifier: Modifier,
) {
    val (minWidthDp, minHeightDp) = format.minRenderSizeDp()
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(
                image = bitmap.asImageBitmap(),
                filterQuality = FilterQuality.None,
            ),
            contentDescription = imageDescription,
            contentScale = format.barcodeContentScale(),
            modifier = modifier.defaultMinSize(
                minWidth = minWidthDp.dp,
                minHeight = minHeightDp.dp,
            ),
        )
    } else {
        // Same size as the barcode so layout does not shift, and described so the blank is not silent.
        Spacer(
            modifier = modifier
                .defaultMinSize(minWidth = minWidthDp.dp, minHeight = minHeightDp.dp)
                .semantics { contentDescription = BARCODE_RENDER_FAILURE_DESCRIPTION },
        )
    }
}

/** Per-symbology min on-screen size in dp. Mirrors [BarcodeView] for gate consistency. */
private fun ScannableFormat.minRenderSizeDp(): Pair<Int, Int> = when (this) {
    ScannableFormat.Qr, ScannableFormat.Aztec -> 240 to 240
    // 4:1 approximates the writer's measured output at the pinned EC and quiet zone
    // (3.4:1 to 4.4:1 across the payload range), so ContentScale.Fit letterboxes little and
    // the modules land close to square.
    ScannableFormat.Pdf417 -> 320 to 80
    ScannableFormat.Code128,
    ScannableFormat.Ean13,
    ScannableFormat.UpcA,
    ScannableFormat.Code39,
    -> 320 to 96
}
