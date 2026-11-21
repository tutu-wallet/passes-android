package `is`.walt.passes.ui

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/** ADR 0005 D5.N: kernel copy says "this app". No string literal or string resource names a host. */
class CopyNamesNoHostTest {
    @Test
    fun noStringLiteralOrResourceNamesAHost() {
        val offenders = File("src/main").walkTopDown()
            .filter { it.extension == "kt" || it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    "${file.path}:${index + 1}".takeIf { !line.isComment() && HOST_NAME_IN_TEXT.containsMatchIn(line) }
                }
            }
            .toList()
        assertWithMessage("copy that names a host; say \"this app\"").that(offenders).isEmpty()
    }

    private fun String.isComment(): Boolean =
        trimStart().let { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") || it.startsWith("<!--") }

    private companion object {
        // A quoted Kotlin literal, or the text of an XML element, that contains the word.
        val HOST_NAME_IN_TEXT = Regex("\"[^\"]*\\bwalt\\b[^\"]*\"|>[^<]*\\bwalt\\b[^<]*<", RegexOption.IGNORE_CASE)
    }
}
