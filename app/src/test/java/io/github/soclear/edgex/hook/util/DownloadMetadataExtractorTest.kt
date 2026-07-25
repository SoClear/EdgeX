package io.github.soclear.edgex.hook.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DownloadMetadataExtractorTest {
    @Test
    fun extractsLegacyObfuscatedLayout() {
        val item = LegacyItem(
            c = DownloadInfo(
                a = GURL("https://example.com/file.zip"),
                b = "Mozilla/5.0 EdgA/149.0",
                c = "application/zip",
                d = "session=legacy",
                h = GURL("https://example.com/page"),
                k = 4096,
            )
        )

        val metadata = DownloadMetadataExtractor.extract(item)

        assertNotNull(metadata)
        assertEquals("https://example.com/file.zip", metadata?.url)
        assertEquals("application/zip", metadata?.mimeType)
        assertEquals("session=legacy", metadata?.cookie)
        assertEquals("Mozilla/5.0 EdgA/149.0", metadata?.userAgent)
        assertEquals("https://example.com/page", metadata?.referrer)
        assertEquals(4096L, metadata?.totalBytes)
    }

    @Test
    fun extractsActualEdge150LayoutWithMissingRequestHeaders() {
        val item = LegacyItem(
            c = DownloadInfo(
                a = GURL("https://cdn.example.org/archive.7z"),
                b = null,
                c = "application/x-7z-compressed",
                d = null,
                h = GURL("https://example.org/downloads"),
                k = 8_388_608,
            )
        )

        val metadata = DownloadMetadataExtractor.extract(item)

        assertNotNull(metadata)
        assertEquals("https://cdn.example.org/archive.7z", metadata?.url)
        assertEquals("application/x-7z-compressed", metadata?.mimeType)
        assertEquals(null, metadata?.cookie)
        assertEquals(null, metadata?.userAgent)
        assertEquals("https://example.org/downloads", metadata?.referrer)
        assertEquals(8_388_608L, metadata?.totalBytes)
    }

    @Test
    fun extractsStructurallyWhenObfuscatedFieldNamesChange() {
        val item = Edge150Item(
            q = DownloadInfo(
                a = GURL("not-used-by-name"),
                b = "not-used-by-name",
                c = "not-used-by-name",
                d = "not-used-by-name",
                h = GURL("not-used-by-name"),
                k = -1,
                primary = GURL("https://cdn.example.org/archive.7z"),
                source = GURL("https://example.org/downloads"),
                agent = "Mozilla/5.0 Chrome/150.0 EdgA/150.0",
                type = "application/x-7z-compressed",
                headers = "token=edge150; secure=true",
                expectedSize = 8_388_608,
            )
        )

        val metadata = DownloadMetadataExtractor.extract(item)

        assertNotNull(metadata)
        assertEquals("https://cdn.example.org/archive.7z", metadata?.url)
        assertEquals("application/x-7z-compressed", metadata?.mimeType)
        assertEquals("token=edge150; secure=true", metadata?.cookie)
        assertEquals("Mozilla/5.0 Chrome/150.0 EdgA/150.0", metadata?.userAgent)
        assertEquals("https://example.org/downloads", metadata?.referrer)
        assertEquals(8_388_608L, metadata?.totalBytes)
    }

    private class LegacyItem(val c: DownloadInfo)
    private class Edge150Item(val q: DownloadInfo)

    @Suppress("LongParameterList")
    private class DownloadInfo(
        val a: GURL,
        val b: String?,
        val c: String,
        val d: String?,
        val h: GURL,
        val k: Long,
        val primary: GURL? = null,
        val source: GURL? = null,
        val agent: String? = null,
        val type: String? = null,
        val headers: String? = null,
        val expectedSize: Long = -1,
    )

    private class GURL(private val value: String) {
        @Suppress("unused")
        fun j(): String = value
    }
}
