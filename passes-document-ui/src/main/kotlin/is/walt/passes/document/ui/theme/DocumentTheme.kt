package `is`.walt.passes.document.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Supplies [LocalDocumentSemantics] to every `passes-document-ui` composable in [content]. Sibling
 * to `PassesTheme`; general colour and type come from the host's `MaterialTheme`.
 */
@Composable
public fun DocumentTheme(
    semantics: DocumentSemantics,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalDocumentSemantics provides semantics,
        content = content,
    )
}

/** The host's [DocumentSemantics]. Throws outside a [DocumentTheme], so no surface renders a default palette. */
public val LocalDocumentSemantics: ProvidableCompositionLocal<DocumentSemantics> =
    staticCompositionLocalOf {
        error(
            "LocalDocumentSemantics not provided. Wrap document-rendering composables " +
                "in DocumentTheme(semantics = ...) at the host root (typically inside " +
                "the host's MaterialTheme scope and alongside any PassesTheme scope).",
        )
    }
