package `is`.walt.passes.isolation

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof of the ADR 0005 F.1 seal set: the PFD [MemfdPfdFactory] returns carries
 * `F_SEAL_SEAL | F_SEAL_WRITE | F_SEAL_GROW | F_SEAL_SHRINK`, so the receiving side can read
 * the bytes but cannot write, resize or unseal them. Needs `memfd_create` (API 30).
 */
@RunWith(AndroidJUnit4::class)
class MemfdPfdFactoryInstrumentedTest {
    private val payload = ByteArray(PAYLOAD_SIZE) { it.toByte() }

    @Before
    fun requireMemfd() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    }

    @Test
    fun returnedFdCarriesTheFullSealSet() {
        MemfdPfdFactory("seal-test").fromBytes(payload).use { pfd ->
            val seals = Os.fcntlInt(pfd.fileDescriptor, MemfdSeals.F_GET_SEALS, 0)
            assertThat(seals).isEqualTo(MemfdSeals.ALL)
        }
    }

    @Test
    fun writeToTheSealedFdFails() {
        MemfdPfdFactory("seal-test").fromBytes(payload).use { pfd ->
            val e = assertThrows(ErrnoException::class.java) {
                Os.write(pfd.fileDescriptor, byteArrayOf(1), 0, 1)
            }
            assertThat(e.errno).isEqualTo(OsConstants.EPERM)
        }
    }

    @Test
    fun resizeAndFurtherSealChangesAreRefused() {
        MemfdPfdFactory("seal-test").fromBytes(payload).use { pfd ->
            val fd = pfd.fileDescriptor
            assertThrows(ErrnoException::class.java) { Os.ftruncate(fd, 1L) }
            assertThrows(ErrnoException::class.java) { Os.ftruncate(fd, PAYLOAD_SIZE * 2L) }
            // F_SEAL_SEAL: no seal can be added or removed any more.
            assertThrows(ErrnoException::class.java) {
                Os.fcntlInt(fd, MemfdSeals.F_ADD_SEALS, MemfdSeals.ALL)
            }
        }
    }

    @Test
    fun sealedFdStillReadsBackFromOffsetZero() {
        MemfdPfdFactory("seal-test").fromBytes(payload).use { pfd ->
            val buf = ByteArray(PAYLOAD_SIZE)
            val n = Os.read(pfd.fileDescriptor, buf, 0, buf.size)
            assertThat(n).isEqualTo(PAYLOAD_SIZE)
            assertThat(buf).isEqualTo(payload)
        }
    }

    @Test
    fun sealedFdRendersThroughThePlatformPdfRenderer() {
        // The PDF consumer reads through PdfRenderer; F_SEAL_WRITE must not break it.
        assumeTrue(Build.VERSION.SDK_INT >= PDF_RENDERER_MIN_SDK)
        MemfdPfdFactory("seal-pdf").fromBytes(minimalPdf()).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                assertThat(renderer.pageCount).isEqualTo(1)
                renderer.openPage(0).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.recycle()
                }
            }
        }
    }

    private fun minimalPdf(): ByteArray {
        val bodies = listOf(
            "<</Type/Catalog/Pages 2 0 R>>",
            "<</Type/Pages/Kids[3 0 R]/Count 1>>",
            "<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]>>",
        )
        val out = StringBuilder("%PDF-1.4\n")
        val offsets = bodies.mapIndexed { i, body ->
            out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") }
        }
        val xrefAt = out.length
        out.append("xref\n0 4\n0000000000 65535 f \n")
        offsets.forEach { out.append(String.format("%010d 00000 n \n", it)) }
        out.append("trailer\n<</Size 4/Root 1 0 R>>\nstartxref\n$xrefAt\n%%EOF\n")
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    private companion object {
        const val PDF_RENDERER_MIN_SDK = 34
        const val PAYLOAD_SIZE = 4096
    }
}
