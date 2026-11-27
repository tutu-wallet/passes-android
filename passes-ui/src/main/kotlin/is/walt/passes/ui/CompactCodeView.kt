package `is`.walt.passes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.BarcodeEncoder
import `is`.walt.passes.core.EncodeResult
import `is`.walt.passes.core.ScannableFormat
import `is`.walt.passes.ui.internal.BARCODE_RENDER_FAILURE_DESCRIPTION
import `is`.walt.passes.ui.internal.barcodeContentScale
import `is`.walt.passes.ui.internal.toMonochromeBitmap

/**
 * Compact `(payload, format)` render for sizes below [ScannableCardView]'s minimums; the caller sizes it.
 * Mechanism only, for previews off the list: no label, caption or signature affordance composes here.
 */
@Composable
public fun CompactCodeView(
    payload: String,
    format: ScannableFormat,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmap = remember(payload, format) {
        when (val result = BarcodeEncoder.encode(payload, format)) {
            is EncodeResult.Success -> result.matrix.toMonochromeBitmap()
            is EncodeResult.Failure -> null
        }
    }
    val (minWidthDp, minHeightDp) = format.compactMinSizeDp()

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = minWidthDp.dp, minHeight = minHeightDp.dp)
            .background(COMPACT_CODE_BACKING),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                painter = BitmapPainter(
                    image = bitmap.asImageBitmap(),
                    filterQuality = FilterQuality.None,
                ),
                contentDescription = contentDescription,
                contentScale = format.barcodeContentScale(),
                modifier = Modifier
                    .matchParentSize()
                    .padding(QUIET_ZONE_PADDING),
            )
        } else {
            // Same-sized failure placeholder; silent blank rectangles are the worst
            // a11y failure mode. Wording matches ScannableCardView's placeholder.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .semantics { this.contentDescription = BARCODE_RENDER_FAILURE_DESCRIPTION },
            )
        }
    }
}

/** Floors that only stop a collapsed render when the caller forgets to size the tile; not a scannability promise. */
private fun ScannableFormat.compactMinSizeDp(): Pair<Int, Int> = when (this) {
    ScannableFormat.Qr, ScannableFormat.Aztec -> 48 to 48
    ScannableFormat.Pdf417 -> 96 to 40
    ScannableFormat.Code128,
    ScannableFormat.Ean13,
    ScannableFormat.UpcA,
    ScannableFormat.Code39,
    -> 96 to 32
}

private val QUIET_ZONE_PADDING = 8.dp

/** Literally white, never a theme token, so the code scans in dark mode. Internal so the smoke test pins it. */
internal val COMPACT_CODE_BACKING: Color = Color.White
