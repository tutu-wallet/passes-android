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
 * fd the sandbox can mmap — no plaintext touches disk (ADR 0005 F.1).
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
 * The buffer is populated with `write(2)` (no writable mapping ever exists), then sealed with
 * `F_SEAL_SEAL | F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK` before it is adopted into the
 * returned PFD. A renderer or decoder that receives the fd can read it but cannot write it,
 * map it writable, resize it, or lift the seals (ADR 0005 F.1).
 *
 * memfd_create lands in `android.system.Os` at API 30. Importers reject below their own
 * floor before this factory is asked for a PFD; the [TargetApi] narrows lint's view to API 30
 * and the inline `check` is defence in depth, so a caller that skips the gate fails loudly here
 * rather than crashing inside libcore.
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
            // Os.write returns the count actually written; loop to drain the buffer in
            // case the kernel short-writes. memfd-backed fds accept the whole buffer in
            // one go in practice, but the loop is the defensive shape.
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
            check(Os.fcntlInt(fd, MemfdSeals.F_GET_SEALS, 0) == MemfdSeals.ALL) {
                "memfd seals not applied"
            }
            return ParcelFileDescriptor.dup(fd)
        } finally {
            // ParcelFileDescriptor.dup duplicates the underlying fd; close ours so the
            // memfd is owned exclusively by the PFD handed back to the caller. runCatching
            // covers the case where dup() failed and already dropped our ownership claim.
            runCatching { Os.close(fd) }
        }
    }
}

/**
 * Linux `memfd` sealing values. `OsConstants` exposes only `MFD_CLOEXEC`, so the rest are the
 * stable kernel ABI values from `<linux/memfd.h>` and `<linux/fcntl.h>`, used through the public
 * `Os.fcntlInt` call only.
 */
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
