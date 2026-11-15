package com.omnidroid.tools.libretrodb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThumbnailIndexTest {
    @Test
    fun authenticatesGithubApiRequests() {
        val request =
            ThumbnailIndex.request(
                "https://api.github.com/repos/libretro-thumbnails/MAME/git/trees/master",
                "test-token",
            )

        assertEquals("Bearer test-token", request.headers().firstValue("Authorization").orElse(null))
    }

    @Test
    fun worksWithoutAToken() {
        listOf(null, "", " ").forEach { token ->
            val request =
                ThumbnailIndex.request(
                    "https://api.github.com/repos/libretro-thumbnails/MAME/git/trees/master",
                    token,
                )

            assertFalse(request.headers().firstValue("Authorization").isPresent)
        }
    }

    @Test
    fun keepsTokenOffOtherHostsAndInsecureRequests() {
        listOf("https://example.com/tree", "http://api.github.com/tree").forEach { url ->
            val request = ThumbnailIndex.request(url, "test-token")

            assertFalse(request.headers().firstValue("Authorization").isPresent)
        }
    }
}
