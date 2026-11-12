package com.thekain.video

import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import org.junit.Assert.*
import org.junit.Test

class TransferRulesTest {
    @Test fun acceptsDirectHttpsAndRedactsSignedUrls() {
        val request = DirectLinks.parse("https://example.invalid/owned.mp4?token=fixture-only")
        assertEquals("owned.mp4", request.name)
        assertEquals("video/mp4", request.mime)
        assertFalse(request.toString().contains("token"))
        val task = DownloadTask("fixture", request.url, request.name, request.mime)
        assertFalse(task.toString().contains("token"))
    }

    @Test fun rejectsUnsafeAndNonDirectLinks() {
        listOf("http://example.invalid/video.mp4", "file:///tmp/video.mp4", "javascript:alert(1)",
            "https://user:pass@example.invalid/video.mp4", "https://@example.invalid/video.mp4",
            "https://example.invalid/watch?v=x", "https://example.invalid/video.mp4#fragment",
            "https://example.invalid/video.mp4\n", "https://example.invalid:0/video.mp4").forEach {
            try { DirectLinks.parse(it); fail("Accepted unsafe source") }
            catch (error: TransferFailure) { assertEquals(TransferError.INVALID_URL, error.code) }
        }
    }

    @Test fun outputsForEqualNamesHaveDistinctJobIdentity() {
        assertNotEquals(DirectLinks.publishedName("same.mp4", "first"), DirectLinks.publishedName("same.mp4", "second"))
        assertFalse(DirectLinks.safeName("../../bad\\name.mp4").contains('/'))
        assertFalse(DirectLinks.safeName("bad\u0000name.mp4").contains('\u0000'))
    }

    @Test fun longDirectNamesKeepExtensionAndCorrectMimeWhenPublished() {
        val supported = mapOf(
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mov" to "video/quicktime",
            "mp3" to "audio/mpeg",
            "m4a" to "audio/mp4"
        )
        for ((extension, mime) in supported) {
            // Multiple dots in the stem must not be confused with the media suffix.
            val stem = "series.s01.e02." + "very-long-episode-".repeat(12)
            val request = DirectLinks.parse("https://example.invalid/$stem.$extension?token=owned")
            assertEquals(extension, mime, request.mime)
            assertTrue("Name exceeds 96 characters: ${request.name}", request.name.length <= 96)
            assertTrue(request.name.startsWith("series.s01.e02."))
            assertTrue("Lost .$extension suffix", request.name.endsWith(".$extension"))
            val output = DirectLinks.publishedName(request.name, "fixture-id")
            assertTrue("Published name has wrong extension: $output", output.endsWith("-fixture-id.$extension"))
            assertEquals(extension, output.substringAfterLast('.'))
        }
    }

    @Test fun uppercaseAndBoundaryLengthNamesKeepCanonicalExtension() {
        val nearLimit = "a".repeat(92) + ".mp3"
        val atLimit = DirectLinks.parse("https://example.invalid/$nearLimit")
        assertEquals(96, atLimit.name.length)
        assertEquals(nearLimit, atLimit.name)
        val overLimit = DirectLinks.parse("https://example.invalid/${"a".repeat(200)}.MP3")
        assertEquals(96, overLimit.name.length)
        assertTrue(overLimit.name.endsWith(".mp3"))
        assertTrue(DirectLinks.publishedName(overLimit.name, "fixture").endsWith(".mp3"))
    }

    @Test fun fullTransferIsNotCompletionAndUnknownSizeStaysUnknown() {
        listOf(TransferState.DOWNLOADING, TransferState.VERIFYING, TransferState.PUBLISHING, TransferState.FAILED).forEach {
            assertEquals(99, TransferRules.percent(it, 100, 100))
        }
        assertNull(TransferRules.percent(TransferState.DOWNLOADING, 100, null))
        assertEquals(100, TransferRules.percent(TransferState.COMPLETED, 100, 100))
    }

    @Test fun completionRequiresOrderedPipelineAndTerminalJobsCannotRestart() {
        assertFalse(TransferRules.allowed(TransferState.DOWNLOADING, TransferState.COMPLETED))
        assertTrue(TransferRules.allowed(TransferState.DOWNLOADING, TransferState.VERIFYING))
        assertTrue(TransferRules.allowed(TransferState.VERIFYING, TransferState.PUBLISHING))
        assertTrue(TransferRules.allowed(TransferState.PUBLISHING, TransferState.COMPLETED))
        assertFalse(TransferRules.allowed(TransferState.COMPLETED, TransferState.DOWNLOADING))
        assertFalse(TransferRules.allowed(TransferState.CANCELED, TransferState.QUEUED))
    }

    @Test fun serverErrorsAreTypedAndOnlyTransientResponsesRetry() {
        assertEquals(TransferError.REMOTE_FORBIDDEN, TransferRules.httpError(403).code)
        assertEquals(TransferError.REMOTE_MISSING, TransferRules.httpError(404).code)
        assertFalse(TransferRules.httpError(403).retryable)
        assertFalse(TransferRules.httpError(404).retryable)
        assertTrue(TransferRules.httpError(429).retryable)
        assertTrue(TransferRules.httpError(503).retryable)
    }

    @Test fun resumeRequiresActual206StrongIdentityAndMatchingRange() {
        assertTrue(TransferRules.validResume(206, "bytes 5-9/10", "\"fixture\"", "\"fixture\"", 5, 10))
        assertFalse(TransferRules.validResume(200, null, "\"fixture\"", "\"fixture\"", 5, 10))
        assertFalse(TransferRules.validResume(206, "bytes 5-9/10", "\"new\"", "\"old\"", 5, 10))
        assertFalse(TransferRules.validResume(206, "bytes 0-9/10", "\"fixture\"", "\"fixture\"", 5, 10))
        assertFalse(TransferRules.validResume(206, "bytes 5-9/10", "W/\"weak\"", "W/\"weak\"", 5, 10))
    }

    @Test fun retryAfterSupportsSecondsAndHttpDateWithoutEarlyRetries() {
        assertEquals(30_000L, TransferRules.retryAfterMillis("30", 0))
        assertEquals(10_000L, TransferRules.retryAfterMillis("Thu, 1 Jan 1970 00:00:10 GMT", 0))
        assertEquals(0L, TransferRules.retryAfterMillis("invalid", 0))
        assertEquals(Long.MAX_VALUE, TransferRules.retryAfterMillis(Long.MAX_VALUE.toString(), 0))
    }
}
