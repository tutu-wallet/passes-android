package `is`.walt.passes.document.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import `is`.walt.passes.document.BarcodedImageDocument
import `is`.walt.passes.document.Document
import `is`.walt.passes.document.DocumentId
import `is`.walt.passes.document.DocumentTelemetryGuard
import `is`.walt.passes.document.ImageDocument
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.image.android.ImageDecodeBinder
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.pdf.android.RenderResult
import `is`.walt.passes.pdf.android.RenderSourceRect
import `is`.walt.passes.document.ui.internal.DEFAULT_MAX_SCALE
import `is`.walt.passes.document.ui.internal.ZoomableImage
import `is`.walt.passes.document.ui.internal.decodePage
import `is`.walt.passes.document.ui.internal.renderOrDiscard
import `is`.walt.passes.document.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * Full-screen, pinch-zoomable view of a [Document]: the document and the host's close control,
 * nothing else. Same arm dispatch and backend pairs as [DocumentView]; no share-out (ADR 0005 D8).
 *
 * @param initialPage Zero-based PDF page to open on, clamped to the page range; read on first
 *   composition only.
 * @param closeButton Host close control, drawn at [Alignment.TopStart]; the host owns its chrome
 *   and insets.
 */
@Composable
@Suppress("LongParameterList")
public fun FullScreenDocumentView(
    doc: Document,
    pdfFile: ParcelFileDescriptor? = null,
    renderer: PdfRendererBinder? = null,
    imageFile: ParcelFileDescriptor? = null,
    imageDecoder: ImageDecodeBinder? = null,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    initialPage: Int = 0,
    closeButton: @Composable (onClose: () -> Unit) -> Unit = { handler ->
        CloseFullScreenButton(onClick = handler)
    },
) {
    val semantics = LocalDocumentSemantics.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(semantics.frameBackground.toComposeColor()),
    ) {
        when (doc) {
            is PdfDocument -> FullScreenPdfPager(
                doc = doc,
                pdfFile = requireNotNull(pdfFile) {
                    "FullScreenDocumentView(PdfDocument) requires a non-null pdfFile"
                },
                renderer = requireNotNull(renderer) {
                    "FullScreenDocumentView(PdfDocument) requires a non-null renderer"
                },
                telemetry = telemetry,
                initialPage = initialPage,
            )
            // A composite's photo zooms like a plain image; its barcode half is the consumer's.
            is ImageDocument, is BarcodedImageDocument -> FullScreenImagePage(
                documentId = doc.id,
                imageFile = requireNotNull(imageFile) {
                    "FullScreenDocumentView(${doc::class.simpleName}) requires a non-null imageFile"
                },
                decoder = requireNotNull(imageDecoder) {
                    "FullScreenDocumentView(${doc::class.simpleName}) requires a non-null imageDecoder"
                },
                telemetry = telemetry,
            )
        }

        Box(modifier = Modifier.align(Alignment.TopStart)) {
            closeButton(onClose)
        }
    }
}

@Composable
private fun FullScreenPdfPager(
    doc: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    telemetry: DocumentTelemetryGuard,
    initialPage: Int,
) {
    val cache = remember(doc.id) { PdfThumbnailCache() }
    DisposableEffect(doc.id) {
        onDispose { cache.clear() }
    }

    val pagerState = rememberPagerState(
        initialPage = initialPage.coerceIn(0, (doc.pageCount - 1).coerceAtLeast(0)),
        pageCount = { doc.pageCount },
    )

    // No beyondViewportPageCount, unlike the inline pager: full-screen rasters are far larger.
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        FullScreenPage(
            document = doc,
            pageIndex = page,
            pdfFile = pdfFile,
            renderer = renderer,
            cache = cache,
            telemetry = telemetry,
        )
    }
}

/**
 * One raster decoded once at the slot size times [DEFAULT_MAX_SCALE], clamped to 4 MP; the
 * sandbox never upscales, so this is the sharpest raster it will produce for any zoom.
 */
@Composable
private fun FullScreenImagePage(
    documentId: DocumentId,
    imageFile: ParcelFileDescriptor,
    decoder: ImageDecodeBinder,
    telemetry: DocumentTelemetryGuard,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val (requestW, requestH) = with(density) {
            val rawW = (maxWidth.toPx() * DEFAULT_MAX_SCALE).toInt().coerceAtLeast(1)
            val rawH = (maxHeight.toPx() * DEFAULT_MAX_SCALE).toInt().coerceAtLeast(1)
            clampToMaxPixels(rawW, rawH, MAX_REQUEST_PIXELS)
        }

        val state = rememberDocumentImage(
            documentId = documentId,
            imageFile = imageFile,
            decoder = decoder,
            targetSizePx = IntSize(requestW, requestH),
            telemetry = telemetry,
        )
        // Loading and Failed render nothing; the frame tone is the placeholder.
        when (state) {
            is DocumentImageState.Loading, is DocumentImageState.Failed -> Unit
            is DocumentImageState.Rendered -> ZoomableImage(
                bitmap = state.image,
                // Fixed text: ADR 0005 D4 forbids reading anything from the image.
                contentDescription = "Image document",
                modifier = Modifier.fillMaxSize(),
                pageAspect = state.sourceAspect,
            )
        }
    }
}

@Composable
@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
private fun FullScreenPage(
    document: PdfDocument,
    pageIndex: Int,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    cache: PdfThumbnailCache,
    telemetry: DocumentTelemetryGuard,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        // Sized from the pager slot, clamped to the 4 MP cap (ADR 0005 D7).
        val (requestW, requestH) = with(density) {
            val rawW = maxWidth.toPx().toInt().coerceAtLeast(1)
            val rawH = maxHeight.toPx().toInt().coerceAtLeast(1)
            clampToMaxPixels(rawW, rawH, MAX_REQUEST_PIXELS)
        }

        // The base page uses the shared cached facade; sub-rect renders below are uncached, since
        // a stale one would show a sharper but wrong region after a pan.
        val baseState = rememberPdfThumbnail(
            document = document,
            pdfFile = pdfFile,
            renderer = renderer,
            targetSizePx = IntSize(requestW, requestH),
            page = pageIndex,
            telemetry = telemetry,
            cache = cache,
        )
        // Loading and Failed both mean no base image yet; the zoom overlay needs one.
        val baseRendered = when (baseState) {
            is PdfThumbnailState.Loading, is PdfThumbnailState.Failed -> null
            is PdfThumbnailState.Rendered -> baseState
        }

        var zoomedReplacement by remember(document.id, pageIndex) {
            mutableStateOf<ImageBitmap?>(null)
        }
        var zoomedReplacementHandle by remember(document.id, pageIndex) {
            mutableStateOf<Bitmap?>(null)
        }
        var pendingRect by remember(document.id, pageIndex) {
            mutableStateOf<RenderSourceRect?>(null)
        }

        DisposableEffect(document.id, pageIndex) {
            onDispose {
                zoomedReplacementHandle?.let { if (!it.isRecycled) it.recycle() }
                zoomedReplacementHandle = null
                zoomedReplacement = null
            }
        }

        baseRendered?.let { rendered ->
            ZoomableImage(
                bitmap = rendered.image,
                // Positional text only: ADR 0005 D4 forbids extracting text from the page.
                contentDescription = "Page ${pageIndex + 1} of ${document.pageCount}",
                modifier = Modifier.fillMaxSize(),
                pageAspect = rendered.pageAspect,
                zoomedReplacement = zoomedReplacement,
                // A new gesture frees the previous sub-rect bitmap and starts from the base one.
                onTransformStarted = {
                    zoomedReplacementHandle?.let { if (!it.isRecycled) it.recycle() }
                    zoomedReplacementHandle = null
                    zoomedReplacement = null
                },
                onZoomedRegionChanged = { rect -> pendingRect = rect },
            )
        }

        LaunchedEffect(pendingRect, document.id, pageIndex, requestW, requestH) {
            val pending = pendingRect ?: return@LaunchedEffect
            // renderOrDiscard frees a stale or cancelled result's SharedMemory, even when a newer
            // pinch re-keys this effect mid-transact.
            val result = renderOrDiscard(
                renderer = renderer,
                pdf = pdfFile,
                page = pageIndex,
                widthPx = requestW,
                heightPx = requestH,
                sourceRect = pending,
                isStillWanted = { pendingRect === pending },
            ) ?: return@LaunchedEffect
            if (result is RenderResult.Ok) {
                val decoded = decodePage(result, telemetry)
                if (decoded != null) {
                    zoomedReplacementHandle?.let { if (!it.isRecycled) it.recycle() }
                    zoomedReplacementHandle = decoded.bitmap
                    zoomedReplacement = decoded.image
                }
            }
        }
    }
}

@Composable
private fun CloseFullScreenButton(onClick: () -> Unit) {
    val semantics = LocalDocumentSemantics.current
    Box(
        modifier = Modifier
            .padding(12.dp)
            .background(semantics.fullScreenBannerBackground.toComposeColor())
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 8.dp)),
    ) {
        Text(
            text = semantics.closeFullScreenLabel,
            color = semantics.fullScreenBannerForeground.toComposeColor(),
        )
    }
}

private fun clampToMaxPixels(widthPx: Int, heightPx: Int, maxPixels: Long): Pair<Int, Int> {
    val product = widthPx.toLong() * heightPx.toLong()
    if (product <= maxPixels) return widthPx to heightPx
    val scale = kotlin.math.sqrt(maxPixels.toDouble() / product.toDouble())
    val w = (widthPx * scale).toInt().coerceAtLeast(1)
    val h = (heightPx * scale).toInt().coerceAtLeast(1)
    return w to h
}

// Mirrors PdfRendererService.MAX_PIXELS and ImageDecodeConfig.maxOutputPixels; a request
// ceiling only, the sandboxes enforce the cap.
private const val MAX_REQUEST_PIXELS: Long = 4L * 1024 * 1024
