package io.legado.app.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RustRemoteFetchTest {

    @Test
    fun decompressZipFirstEntryIfNeededReturnsPlainBodyForNonZip() {
        val body = "plain".toByteArray()

        assertSame(body, RustRemoteFetch.decompressZipFirstEntryIfNeeded(body, "text/plain"))
    }

    @Test
    fun decompressZipFirstEntryIfNeededReturnsFirstZipEntryBody() {
        val zipBytes = ByteArrayOutputStream().use { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("rules.json"))
                zip.write("""[{"name":"alpha"}]""".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("ignored.json"))
                zip.write("""[{"name":"beta"}]""".toByteArray())
                zip.closeEntry()
            }
            out.toByteArray()
        }

        assertArrayEquals(
            """[{"name":"alpha"}]""".toByteArray(),
            RustRemoteFetch.decompressZipFirstEntryIfNeeded(zipBytes, "application/zip; charset=utf-8")
        )
    }
}
