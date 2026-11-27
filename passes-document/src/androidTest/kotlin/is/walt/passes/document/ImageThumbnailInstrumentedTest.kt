package `is`.walt.passes.document

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The stored thumbnail of an imported image, through the real sandbox and encoder. */
@RunWith(AndroidJUnit4::class)
class ImageThumbnailInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun largeImageStoresAThumbnailInsideTheBoxAndKeepsItsRasterDimensions() {
        val persist = importImage(width = 2000, height = 1500)

        assertThat(persist.widthPx).isEqualTo(2000)
        assertThat(persist.heightPx).isEqualTo(1500)
        assertThat(pngSize(persist.thumbnailBytes)).isEqualTo(600 to 450)
    }

    @Test
    fun smallImageStoresAThumbnailAtItsOwnSize() {
        val persist = importImage(width = 300, height = 400)

        assertThat(pngSize(persist.thumbnailBytes)).isEqualTo(300 to 400)
    }

    private fun importImage(width: Int, height: Int): DocumentPersist.Image {
        val persisted = mutableListOf<DocumentPersist>()
        val result = pngFd(width, height).use {
            runBlocking {
                DocumentImporter.create(context).import(
                    source = DocumentImportSource.FileDescriptor(it),
                    displayLabel = "photo.png",
                    persist = { p -> persisted += p },
                )
            }
        }
        assertThat(result).isInstanceOf(DocumentImportResult.ImportedImage::class.java)
        return persisted.single() as DocumentPersist.Image
    }

    // Test-side read of the stored PNG's header; the kernel itself never decodes it here.
    private fun pngSize(png: ByteArray): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, options)
        assertThat(options.outMimeType).isEqualTo("image/png")
        return options.outWidth to options.outHeight
    }

    private fun pngFd(width: Int, height: Int): ParcelFileDescriptor {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val file = File.createTempFile("thumbnail", ".png", context.cacheDir)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        file.delete()
        return pfd
    }
}
