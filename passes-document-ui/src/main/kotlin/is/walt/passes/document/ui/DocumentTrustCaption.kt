package `is`.walt.passes.document.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import `is`.walt.passes.document.ui.internal.InfoOutlineIcon
import `is`.walt.passes.document.ui.theme.LocalDocumentSemantics
import `is`.walt.passes.ui.core.toComposeColor

/**
 * The locked "user-provided document" caption (ADR 0005 D5): fixed wording beside an info
 * icon. No parameter, token or overload alters its wording or removes it.
 */
@Composable
public fun DocumentTrustCaption(
    modifier: Modifier = Modifier,
) {
    val semantics = LocalDocumentSemantics.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CHIP_INSET)
            .clip(RoundedCornerShape(CHIP_RADIUS))
            .background(semantics.captionBackground.toComposeColor())
            .padding(PaddingValues(horizontal = 16.dp, vertical = 10.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = InfoOutlineIcon,
            // Decorative: the caption text beside it is the node TalkBack reads.
            contentDescription = null,
            tint = semantics.captionIconTint.toComposeColor(),
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = TRUST_CAPTION_TEXT,
            style = MaterialTheme.typography.labelMedium,
            color = semantics.captionForeground.toComposeColor(),
        )
    }
}

/** Inset rounded-chip geometry for the caption. Kernel-owned, not themable. */
private val CHIP_INSET = 12.dp
private val CHIP_RADIUS = 10.dp

/** The caption copy, worded by ADR 0005 D5.N. Changing it is a security-policy edit; tests assert the literal. */
internal const val TRUST_CAPTION_TEXT: String =
    "User-provided document. This app has not verified the source."
