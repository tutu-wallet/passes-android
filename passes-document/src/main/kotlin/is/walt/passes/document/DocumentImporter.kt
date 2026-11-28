package `is`.walt.passes.document

import android.content.Context
import `is`.walt.passes.core.ScannableFormat

/**
 * The one document import: sniffs PDF or image and runs it through the isolated backends (ADR 0005 I3).
 * It owns the branch, the one bounded read and the result mapping; storage is the consumer's `persist`.
 */
public interface DocumentImporter {
    /**
     * Calls [persist] once before any `Imported*` arm; a throw other than cancellation is StorageHandoffFailed.
     * With a [confirmBarcode] hook an image also gets the isolated barcode decode; the hook accepts or declines it.
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
