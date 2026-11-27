package `is`.walt.passes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.walt.passes.core.ScannableCard
import `is`.walt.passes.ui.core.faceIsTinted
import `is`.walt.passes.ui.core.isolated

/**
 * Scan surface for a [ScannableCard]: a card face holding a literal-white code panel, the label
 * and the payload. It draws no provenance signal; the host's "Pass type" row does (C2).
 */
@Composable
public fun ScannableCardScreen(
    card: ScannableCard,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    faceTint: Color = Color.Unspecified,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(SCREEN_MARGIN),
        contentAlignment = Alignment.Center,
    ) {
        CodeCard(card = card, showLabel = showLabel, faceTint = faceTint)
    }
}

/** The content-sized card: tinted face, white code panel, then label and payload on the face. */
@Composable
private fun CodeCard(
    card: ScannableCard,
    showLabel: Boolean,
    faceTint: Color,
) {
    val paint = facePaint(
        faceTint = faceTint,
        surface = MaterialTheme.colorScheme.surface,
        onSurface = MaterialTheme.colorScheme.onSurface,
        onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Surface(
        color = paint.face,
        shape = RoundedCornerShape(CARD_RADIUS),
    ) {
        Column(
            modifier = Modifier.padding(CARD_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PANEL_TO_TEXT_GAP),
        ) {
            Surface(
                color = SCAN_CODE_PANEL,
                shape = RoundedCornerShape(PANEL_RADIUS),
            ) {
                // No image description: the label and payload below announce this card,
                // so describing the image too would make TalkBack read the label twice.
                ScannableCodeImage(
                    card = card,
                    imageDescription = null,
                    modifier = Modifier.padding(CODE_QUIET_ZONE),
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LABEL_TO_PAYLOAD_GAP),
            ) {
                if (showLabel) {
                    Text(
                        text = isolated(card.label),
                        style = MaterialTheme.typography.titleMedium
                            .copy(fontWeight = FontWeight.SemiBold),
                        color = paint.ink,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // POS-scan fallback for GH #102; only the detail surface is large enough.
                // Sits on the face, not the code panel, so the panel stays a pure scan
                // target.
                SelectionContainer {
                    Text(
                        text = isolated(card.payload),
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                        color = paint.payloadInk,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** The three colors [ScannableCardScreen]'s card face resolves to. */
internal data class FacePaint(val face: Color, val ink: Color, val payloadInk: Color)

/**
 * Resolves the card face's paint from the consumer's [faceTint], falling back to the theme
 * tokens when there is no tint to take (including a fully transparent one — see
 * [faceIsTinted]). The payload is the POS fallback, not decorative meta: on a tint it takes
 * full ink, because INK_FLIP_LUMINANCE's worst case leaves no headroom for alpha. Untinted
 * keeps the contrast-checked theme token.
 *
 * Internal and theme-token-injecting (not private, not reading `MaterialTheme` itself) so a
 * test can pin the fallbacks: both branches paint *something* and neither changes the tree,
 * so a composition assertion cannot see which one ran.
 */
internal fun facePaint(
    faceTint: Color,
    surface: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
): FacePaint = if (faceIsTinted(faceTint)) {
    val tintInk = inkOn(faceTint)
    FacePaint(face = faceTint, ink = tintInk, payloadInk = tintInk)
} else {
    FacePaint(face = surface, ink = onSurface, payloadInk = onSurfaceVariant)
}

/**
 * Contrast-derived ink for text sitting on [ScannableCardScreen]'s face. Consumers may
 * pass any tint, so the flip keeps the label and payload legible rather than assuming a
 * light palette. Neutral black/white by design: `passes-ui` carries no brand values.
 *
 * Internal so a test can pin the guarantee across the tint range, since the swatches a
 * smoke test happens to pick would only exercise one branch.
 */
internal fun inkOn(tint: Color): Color =
    if (tint.luminance() > INK_FLIP_LUMINANCE) Color.Black else Color.White

/** Panel padding around the code. The scan quiet zone is in the matrix; this is visual. */
private val CODE_QUIET_ZONE = 16.dp

private val SCREEN_MARGIN = 24.dp
private val CARD_RADIUS = 20.dp
private val CARD_PADDING = 18.dp
private val PANEL_RADIUS = 16.dp
private val PANEL_TO_TEXT_GAP = 16.dp
private val LABEL_TO_PAYLOAD_GAP = 4.dp

/**
 * Where black and white ink tie on WCAG contrast: black scores `(L + 0.05) / 0.05` and
 * white `1.05 / (L + 0.05)`, which cross at `L = sqrt(0.0525) - 0.05 ≈ 0.179`. Flipping
 * at the intuitive 0.5 instead would hand every mid-tone tint the *worse* of the two —
 * e.g. `#2E8B7F` (L ≈ 0.206) would take white at 4.1:1 over black at 5.1:1. The tie point
 * is also the worst case: no tint scores below 4.58:1 against the ink chosen here.
 */
private const val INK_FLIP_LUMINANCE = 0.179f

/**
 * Literally white, never a theme token and never [ScannableCardScreen]'s face tint,
 * because the code is real content and must stay theme-independent and scannable in dark
 * mode. Mirrors `COMPACT_CODE_BACKING` so the list-face and detail-face renders of a code
 * share one white-backing rule. Internal (not private) so the smoke test pins the value;
 * rerouting the panel off this constant, or letting a tint reach it, is amending the
 * contract rather than refactoring it.
 */
internal val SCAN_CODE_PANEL: Color = Color.White
