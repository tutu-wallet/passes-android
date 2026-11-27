package `is`.walt.passes.document.ui

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import `is`.walt.passes.image.android.ImageDecodeBinder
import `is`.walt.passes.document.BarcodedImageDocument
import `is`.walt.passes.document.Document
import `is`.walt.passes.document.DocumentId
import `is`.walt.passes.document.DocumentTelemetryGuard
import `is`.walt.passes.document.ImageDocument
import `is`.walt.passes.document.PdfDocument
import `is`.walt.passes.pdf.android.PdfRendererBinder
import `is`.walt.passes.document.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.faceIsTinted
import `is`.walt.passes.ui.core.toComposeColor

/**
 * Inline view of a [Document], dispatching on its arm: PDF to a pager, image and composite to one
 * image. It draws no provenance caption: the host's "Pass type" row carries that (ADR 0005 D5.T).
 */
@Composable
@Suppress("LongParameterList")
public fun DocumentView(
    doc: Document,
    pdfFile: ParcelFileDescriptor? = null,
    renderer: PdfRendererBinder? = null,
    imageFile: ParcelFileDescriptor? = null,
    imageDecoder: ImageDecodeBinder? = null,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    onOpenFullScreen: (() -> Unit)? = null,
    fullScreenAffordance: (@Composable (onOpen: () -> Unit) -> Unit)? = null,
    faceTint: Color = Color.Unspecified,
) {
    when (doc) {
        is PdfDocument -> PdfDocumentView(
            doc = doc,
            pdfFile = requireNotNull(pdfFile) { "DocumentView(PdfDocument) requires a non-null pdfFile" },
            renderer = requireNotNull(renderer) { "DocumentView(PdfDocument) requires a non-null renderer" },
            modifier = modifier,
            telemetry = telemetry,
            onOpenFullScreen = onOpenFullScreen,
            fullScreenAffordance = fullScreenAffordance,
            faceTint = faceTint,
        )
        // wpass-8lu: a composite renders its IMAGE half through the same isolated image-decode
        // surface as a plain image (same imageFile / imageDecoder pair, no new DocumentView
        // parameter). The generated barcode + format switcher are composed by the consumer with
        // passes-ui, keeping the two UI towers independent — this surface stays image-only.
        is ImageDocument, is BarcodedImageDocument -> ImageDocumentView(
            documentId = doc.id,
            imageFile = requireNotNull(imageFile) {
                "DocumentView(${doc::class.simpleName}) requires a non-null imageFile"
            },
            decoder = requireNotNull(imageDecoder) {
                "DocumentView(${doc::class.simpleName}) requires a non-null imageDecoder"
            },
            modifier = modifier,
            telemetry = telemetry,
            onOpenFullScreen = onOpenFullScreen,
            fullScreenAffordance = fullScreenAffordance,
            faceTint = faceTint,
        )
    }
}

/**
 * A swipeable pager of pages rasterised by the isolated renderer, letterboxed at a fixed 1x.
 * [pdfFile] is the caller's and must stay open while this is composed.
 */
@Composable
@Suppress("LongParameterList")
private fun PdfDocumentView(
    doc: PdfDocument,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    onOpenFullScreen: (() -> Unit)? = null,
    fullScreenAffordance: (@Composable (onOpen: () -> Unit) -> Unit)? = null,
    faceTint: Color = Color.Unspecified,
) {
    val cache = remember(doc.id) { PdfThumbnailCache() }
    DisposableEffect(doc.id) {
        onDispose { cache.clear() }
    }

    val pagerState = rememberPagerState(pageCount = { doc.pageCount })

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DocumentFaceSlot(
            faceTint = faceTint,
            onOpenFullScreen = onOpenFullScreen,
            fullScreenAffordance = fullScreenAffordance,
        ) { contentModifier ->
            // Adjacent page rasterises ahead of the viewport so a swipe or peek reveals a
            // ready page (wpass-tjc.3); up to 4 live pages mid-swipe, inside DEFAULT_PAGE_WINDOW.
            HorizontalPager(
                state = pagerState,
                modifier = contentModifier,
                beyondViewportPageCount = 1,
            ) { page ->
                DocumentPage(
                    document = doc,
                    pageIndex = page,
                    pdfFile = pdfFile,
                    renderer = renderer,
                    cache = cache,
                    telemetry = telemetry,
                )
            }
        }
    }
}

/**
 * One image decoded in the isolated image sandbox, letterboxed at a fixed 1x.
 * [imageFile] is the caller's and must stay open while this is composed.
 */
@Composable
@Suppress("LongParameterList")
private fun ImageDocumentView(
    documentId: DocumentId,
    imageFile: ParcelFileDescriptor,
    decoder: ImageDecodeBinder,
    modifier: Modifier = Modifier,
    telemetry: DocumentTelemetryGuard = DocumentTelemetryGuard.NoOp,
    onOpenFullScreen: (() -> Unit)? = null,
    fullScreenAffordance: (@Composable (onOpen: () -> Unit) -> Unit)? = null,
    faceTint: Color = Color.Unspecified,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DocumentFaceSlot(
            faceTint = faceTint,
            onOpenFullScreen = onOpenFullScreen,
            fullScreenAffordance = fullScreenAffordance,
        ) { contentModifier ->
            Box(modifier = contentModifier) {
                InlineDecodedImage(
                    documentId = documentId,
                    imageFile = imageFile,
                    decoder = decoder,
                    telemetry = telemetry,
                )
            }
        }
    }
}

/**
 * The frame, tappable content region and full-screen entry both arms share, so they cannot
 * drift. [content] receives the slot-filling, tap-wired modifier to place its surface with.
 */
@Composable
private fun ColumnScope.DocumentFaceSlot(
    faceTint: Color,
    onOpenFullScreen: (() -> Unit)?,
    fullScreenAffordance: (@Composable (onOpen: () -> Unit) -> Unit)?,
    content: @Composable (contentModifier: Modifier) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .documentFace(faceTint),
    ) {
        val contentModifier = Modifier
            .fillMaxSize()
            .let {
                if (onOpenFullScreen != null) it.clickable(onClick = onOpenFullScreen) else it
            }
            .padding(PaddingValues(horizontal = 16.dp, vertical = 8.dp))
        content(contentModifier)

        // A host-supplied affordance floats over the content bottom-centre (wpass-emn);
        // the host accepts that overlap by choosing a floating affordance.
        if (onOpenFullScreen != null && fullScreenAffordance != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            ) {
                fullScreenAffordance(onOpenFullScreen)
            }
        }
    }

    // No custom affordance: the neutral banner docks below the content, never over it.
    if (onOpenFullScreen != null && fullScreenAffordance == null) {
        FullScreenBanner(onClick = onOpenFullScreen)
    }
}

/** The inline image arm's decode-and-render body, the single-image analogue of [DocumentPage]. */
@Composable
private fun InlineDecodedImage(
    documentId: DocumentId,
    imageFile: ParcelFileDescriptor,
    decoder: ImageDecodeBinder,
    telemetry: DocumentTelemetryGuard,
) {
    val density = LocalDensity.current
    val requestWidthPx = with(density) {
        TARGET_PAGE_WIDTH_DP.dp.toPx().toInt().coerceAtLeast(1)
    }
    val requestHeightPx = with(density) {
        TARGET_PAGE_HEIGHT_DP.dp.toPx().toInt().coerceAtLeast(1)
    }
    val state = rememberDocumentImage(
        documentId = documentId,
        imageFile = imageFile,
        decoder = decoder,
        targetSizePx = IntSize(requestWidthPx, requestHeightPx),
        telemetry = telemetry,
    )
    // Loading / Failed render nothing inline; the frame tone is the placeholder, as in DocumentPage.
    when (state) {
        is DocumentImageState.Loading, is DocumentImageState.Failed -> Unit
        is DocumentImageState.Rendered -> Image(
            bitmap = state.image,
            // ADR 0005 D4 forbids extracting text/metadata from the image; a fixed
            // neutral description is the only safe TalkBack fallback.
            contentDescription = "Image document",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Paints the frame behind the page or image for both arms: background only, never a clip or
 * filter on content. A tint brings the rounded card shape; untinted keeps the flat frame tone.
 */
@Composable
private fun Modifier.documentFace(faceTint: Color): Modifier =
    if (faceIsTinted(faceTint)) {
        background(faceTint, RoundedCornerShape(FACE_RADIUS))
    } else {
        background(LocalDocumentSemantics.current.frameBackground.toComposeColor())
    }

/**
 * Card radius from the 26.08.08 design's card anatomy spec. Duplicated as `CARD_RADIUS` in
 * `passes-ui`'s `ScannableCardScreen`, which fulfils the same spec for the scannable face;
 * hoisting both onto one shared token is wpass-nbr.
 */
private val FACE_RADIUS = 20.dp

// wpass-jil: the kernel's neutral default open-full-screen affordance, docked below the
// page. Label and colours come from DocumentSemantics; a host wanting a different
// register (pill, leading icon, floating placement) supplies its own via DocumentView's
// `fullScreenAffordance` slot (wpass-emn).
@Composable
private fun FullScreenBanner(onClick: () -> Unit) {
    val semantics = LocalDocumentSemantics.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantics.fullScreenBannerBackground.toComposeColor())
            .clickable(onClick = onClick)
            .padding(PaddingValues(horizontal = 16.dp, vertical = 12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = semantics.fullScreenBannerLabel,
            style = MaterialTheme.typography.labelMedium,
            color = semantics.fullScreenBannerForeground.toComposeColor(),
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun DocumentPage(
    document: PdfDocument,
    pageIndex: Int,
    pdfFile: ParcelFileDescriptor,
    renderer: PdfRendererBinder,
    cache: PdfThumbnailCache,
    telemetry: DocumentTelemetryGuard,
) {
    val density = LocalDensity.current
    val requestWidthPx = with(density) {
        TARGET_PAGE_WIDTH_DP.dp.toPx().toInt().coerceAtLeast(1)
    }
    val requestHeightPx = with(density) {
        TARGET_PAGE_HEIGHT_DP.dp.toPx().toInt().coerceAtLeast(1)
    }

    val state = rememberPdfThumbnail(
        document = document,
        pdfFile = pdfFile,
        renderer = renderer,
        targetSizePx = IntSize(requestWidthPx, requestHeightPx),
        page = pageIndex,
        telemetry = telemetry,
        cache = cache,
    )

    // Fills the pager's slot at a fixed 1x (zoom is the full-screen surface's); ContentScale.Fit
    // letterboxes. Loading and Failed render nothing: the pager itself is the placeholder.
    when (state) {
        is PdfThumbnailState.Loading, is PdfThumbnailState.Failed -> Unit
        is PdfThumbnailState.Rendered -> Image(
            bitmap = state.image,
            // ADR 0005 D4 forbids extracting text from the PDF; positional caption
            // is the only safe TalkBack fallback.
            contentDescription = "Page ${pageIndex + 1} of ${document.pageCount}",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// Render budget defaults. The renderer service caps the bitmap area independently
// (ADR 0005 D7); these are the UI-side request size for the inline surface.
// Unlike `FullScreenDocumentView` (which derives the request from the actual slot
// via BoxWithConstraints), the inline surface is fixed 1x and ContentScale.Fit masks
// any over/under-render — the BoxWithConstraints cost isn't worth it for a thumbnail-
// sized slot.
// Internal (not private) so DocumentFaceTintTest can pin that the request really is
// derived from these and not from the slot; a slot-derived request would make the
// tint-invariance it asserts depend on layout the test does not control.
internal const val TARGET_PAGE_WIDTH_DP: Int = 360
internal const val TARGET_PAGE_HEIGHT_DP: Int = 480
