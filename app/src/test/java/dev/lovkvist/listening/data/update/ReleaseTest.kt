package dev.lovkvist.listening.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewer("0.3.0", "0.2.0"))
        assertTrue(isNewer("v0.10.0", "0.9.1"))
        assertTrue(isNewer("1.0", "0.9.9"))
        assertFalse(isNewer("0.2.0", "0.2.0"))
        assertFalse(isNewer("0.2.0", "0.2.1"))
        assertFalse(isNewer("0.2.0", "0.2.0-debug"))
    }

    @Test
    fun parsesTheApkAsset() {
        val json = """
            {"tag_name": "v0.3.0", "body": "Notes", "draft": false, "prerelease": false,
             "html_url": "https://github.com/x/y/releases/tag/v0.3.0",
             "assets": [
               {"name": "checksums.txt", "browser_download_url": "https://example.com/c.txt", "size": 1},
               {"name": "app-0.3.0.apk", "browser_download_url": "https://example.com/app.apk", "size": 42,
                "digest": "sha256:abc123"}
             ]}
        """.trimIndent()
        assertEquals(
            Release("0.3.0", "Notes", "https://example.com/app.apk", 42, "abc123", "https://github.com/x/y/releases/tag/v0.3.0"),
            parseRelease(json),
        )
    }

    @Test
    fun releaseWithoutApkIsIgnored() {
        assertNull(parseRelease("""{"tag_name": "v0.3.0", "assets": []}"""))
    }
}
