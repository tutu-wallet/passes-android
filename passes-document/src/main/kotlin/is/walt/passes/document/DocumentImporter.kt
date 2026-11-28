package `is`.walt.passes.document

import android.content.Context
import `is`.walt.passes.core.ScannableFormat

/**
 * The one document import: sniffs PDF or image and runs it through the isolated backends (ADR 0005 I3).
 * It owns the branch, the one bounded read and the result mapping; storage is the consumer's `persist`.
 */
public interface DocumentImporter {
    /**
     * Calls [persist] once before any `Imported*` arm, never on a rejection; a throw is StorageHandoffFailed.
     * A non-null [confirmBarcode] runs the barcode decode, then accepts or declines its reading (ADR 0005 C3).
     */
    public suspend fun import(
        source: DocumentImportSource,
        displayLabel: String,
        confirmBarcode: (suspend (payload: String, format: ScannableFormat) -> Boolean)? = null,
        persist: suspend (DocumentPersist) -> Unit,
    ): DocumentImportResult

    public companion object {
        public fun create(
            context: Context,
            config: DocumentImportConfig = DocumentImportConfig(),
        ): DocumentImporter = DefaultDocumentImporter.create(context, config)
    }
}
