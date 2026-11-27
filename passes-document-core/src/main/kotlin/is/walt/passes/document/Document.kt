package `is`.walt.passes.document

/**
 * An imported, never signature-verified document; a sibling of `Pass`, not a subtype (ADR 0005 D1).
 * Holds the fields every arm shares. [displayLabel] comes from the consumer, never from content (D4).
 */
public sealed interface Document {
    public val id: DocumentId
    public val displayLabel: String
    public val byteCount: Long
    public val importedAtEpochMs: Long
    public val provenance: Provenance
}

/**
 * Opaque identifier for a stored [Document]. Each arm wraps its id in a value class so
 * calling code cannot accidentally substitute a `String` from another domain (a pass id,
 * a filename, a user input) into APIs that expect a document id. Sealed so the set of
 * id kinds tracks the set of [Document] arms.
 */
public sealed interface DocumentId {
    public val value: String
}
