package com.thekain.video
import com.thekain.video.domain.DirectLinks
import com.thekain.video.presentation.*
import com.thekain.video.source.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DisabledVariantTest {
    @Test fun extractorCandidateCanBeInspectedButCannotBeEnqueuedEvenWithConfirmation() = runTest {
        var enqueued = 0
        val request = DirectLinks.parse("https://example.invalid/owned.mp4")
        val candidate = MediaVariant("real", request, downloadAllowed = false)
        val home = HomeSession(this, SourceAnalyzer { SourceMedia("Owned", "example.invalid", listOf(candidate)) }, { enqueued++; "op" })
        home.edit(request.url); home.analyze(); runCurrent()
        assertEquals(AnalysisPhase.READY, home.state.value.phase)
        assertFalse(home.state.value.variant!!.downloadAllowed)
        home.download(); home.download(confirmed = true)
        assertEquals(0, enqueued); assertFalse(home.state.value.enqueuePending)
        assertNull(home.state.value.enqueueError)
    }
}
