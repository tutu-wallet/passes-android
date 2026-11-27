package `is`.walt.passes.ui.internal

/**
 * Least-recently-used map whose entries' weights, as [weigh] gives them, total at most [maxBytes].
 * An entry heavier than [maxBytes] is not kept. Safe to call from any thread.
 */
internal class ByteBoundedLru<K : Any, V : Any>(
    private val maxBytes: Int,
    private val weigh: (K, V) -> Int,
) {
    init {
        require(maxBytes > 0) { "maxBytes must be positive, was $maxBytes" }
    }

    private class Weighted<V>(val value: V, val weight: Int)

    private val entries = LinkedHashMap<K, Weighted<V>>(INITIAL_CAPACITY, LOAD_FACTOR, true)

    var bytes: Long = 0L
        @Synchronized get
        private set

    @Synchronized
    fun get(key: K): V? = entries[key]?.value

    @Synchronized
    fun put(key: K, value: V) {
        entries.remove(key)?.let { bytes -= it.weight }
        val weight = weigh(key, value)
        if (weight > maxBytes) return
        entries[key] = Weighted(value, weight)
        bytes += weight
        val eldest = entries.values.iterator()
        while (bytes > maxBytes) {
            bytes -= eldest.next().weight
            eldest.remove()
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        bytes = 0L
    }

    private companion object {
        const val INITIAL_CAPACITY = 16
        const val LOAD_FACTOR = 0.75f
    }
}
