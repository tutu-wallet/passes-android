package `is`.walt.passes.ui.core

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified

/**
 * Whether a face takes [faceTint]; false for unspecified and fully transparent tints. Shared by
 * the scannable face and the document frame; it decides legibility, not provenance.
 */
public fun faceIsTinted(faceTint: Color): Boolean =
    faceTint.isSpecified && faceTint.alpha > 0f
