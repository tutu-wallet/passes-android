package `is`.walt.passes.isolation

import android.annotation.TargetApi
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants

/**
 * Materializes a byte payload into a [ParcelFileDescriptor] suitable for handing across a
 * bind to an isolated worker service. The shared seam both [is.walt.passes.isolation]
 * consumers reach for when they need to put *bytes already in the main process* into an
 * fd the sandbox can read — no plaintext touches disk (ADR 0005 F.1). Consumers read with
 * `pread`/`read` or private mappings: before Linux 6.7 a write-sealed memfd refuses even a
 * read-only shared mapping.
 *
 * Not every consumer needs this: a worker whose hostile input must never enter the main
 * process at all (e.g. image decode) hands the *source* fd across directly instead of
 * reading the bytes here. Those consumers use only the bind-session facade and skip this
 * factory. The factory exists for the PDF path, which header-sniffs and size-bounds the
 * bytes in the main process before binding and therefore already holds them in RAM.
 *
 * Kept behind an interface so unit tests can inject a pipe-backed fake (the real
 * [MemfdPfdFactory] calls `Os.memfd_create`, which is not reachable from the JVM-side test
 * runtime). The same single-implementation discipline applies as before consolidation:
 * adding a third production implementation in walt-android would re-open the
 * parallel-implementation gap this shared module exists to close.
 */
public interface PfdFactory {
    /**
     * Allocate an in-memory PFD, write [bytes] into it, then rewind so the worker reads
     * from offset 0. [MemfdPfdFactory] also seals the buffer read-only and fixed-size before
     * returning it (ADR 0005 F.1).
     */
    public fun fromBytes(bytes: ByteArray): ParcelFileDescriptor
}

/**
 * The production [PfdFactory]: an anonymous in-RAM file via `memfd_create`. [debugName] is
 * the kernel's purely-cosmetic label for the fd (visible in `/proc/<pid>/fd`); consumers
 * pass a per-worker name so a leaked fd is attributable.
 *
 * The buffer is written with `write(2)`, then sealed against write, grow, shrink and seal
 * changes before it is adopted into the returned PFD (ADR 0005 F.1).
 *
 * `memfd_create` needs API 30; the inline `check` fails loudly if a caller skips its gate.
 */
@TargetApi(Build.VERSION_CODES.R)
public class MemfdPfdFactory(
    private val debugName: String,
) : PfdFactory {
    override fun fromBytes(bytes: ByteArray): ParcelFileDescriptor {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "memfd_create requires Android 11 (API 30) or newer"
        }
        val fd = Os.memfd_create(debugName, OsConstants.MFD_CLOEXEC or MemfdSeals.MFD_ALLOW_SEALING)
        try {
            // Loop in case the kernel short-writes.
            var offset = 0
            while (offset < bytes.size) {
                val written = Os.write(fd, bytes, offset, bytes.size - offset)
                if (written <= 0) {
                    error("memfd write stalled at offset=$offset")
                }
                offset += written
            }
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            Os.fcntlInt(fd, MemfdSeals.F_ADD_SEALS, MemfdSeals.ALL)
            // Mask: newer kernels may add seals of their own (e.g. F_SEAL_EXEC).
            val applied = Os.fcntlInt(fd, MemfdSeals.F_GET_SEALS, 0) and MemfdSeals.ALL
            check(applied == MemfdSeals.ALL) {
                "memfd seals not applied"
            }
            return ParcelFileDescriptor.dup(fd)
        } finally {
            // dup() duplicated the fd; close ours so the returned PFD owns the memfd.
            runCatching { Os.close(fd) }
        }
    }
}

// OsConstants exposes only MFD_CLOEXEC; the rest are Linux ABI values, used via public Os calls.
internal object MemfdSeals {
    const val MFD_ALLOW_SEALING: Int = 0x0002
    const val F_ADD_SEALS: Int = 1033
    const val F_GET_SEALS: Int = 1034
    const val F_SEAL_SEAL: Int = 0x0001
    const val F_SEAL_SHRINK: Int = 0x0002
    const val F_SEAL_GROW: Int = 0x0004
    const val F_SEAL_WRITE: Int = 0x0008
    const val ALL: Int = F_SEAL_SEAL or F_SEAL_WRITE or F_SEAL_GROW or F_SEAL_SHRINK
}
