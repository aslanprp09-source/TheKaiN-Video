package com.thekain.video

import com.thekain.video.domain.*
import com.thekain.video.presentation.*
import com.thekain.video.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeSessionTest {
    private fun source(name: String = "owned.mp4") = SourceMedia(name, "example.invalid",
        listOf(MediaVariant("original", DirectLinks.parse("https://example.invalid/$name"))))

    @Test fun cannotDownloadUnanalyzedInputOrInventVariants() = runTest {
        var calls = 0
        val home = HomeSession(this, SourceAnalyzer { source() }, { calls++; "op" })
        home.edit("https://example.invalid/owned.mp4")
        home.download(); assertEquals(0, calls)
        home.analyze(); runCurrent()
        home.select("invented-1080p")
        assertEquals("original", home.state.value.selected)
        home.download(); assertEquals(1, calls)
    }

    @Test fun editingCancelsAnalysisAndLateResultsCannotRestoreStalePreview() = runTest {
        val late = CompletableDeferred<SourceMedia>()
        val home = HomeSession(this, SourceAnalyzer {
            withContext(NonCancellable) { late.await() }
        }, { "op" })
        home.edit("https://example.invalid/old.mp4"); home.analyze(); runCurrent()
        assertEquals(AnalysisPhase.ANALYZING, home.state.value.phase)
        home.edit("https://example.invalid/new.mp4")
        late.complete(source("old.mp4")); runCurrent()
        assertEquals(AnalysisPhase.IDLE, home.state.value.phase)
        assertNull(home.state.value.source)
        assertTrue(home.state.value.draft.endsWith("new.mp4"))
    }

    @Test fun rapidDownloadAndUnrelatedErrorDoNotDuplicateQueueThenRepeatNeedsConfirmation() = runTest {
        var calls = 0
        val home = HomeSession(this, SourceAnalyzer { source() }, { "op-${++calls}" })
        home.edit("https://example.invalid/owned.mp4"); home.analyze(); runCurrent()
        repeat(3) { home.download() }
        home.edit("https://example.invalid/other.mp4")
        home.acknowledge("unrelated", false, TransferError.UNKNOWN)
        home.download()
        assertEquals(1, calls)
        assertTrue(home.state.value.enqueuePending)
        assertTrue(home.state.value.draft.endsWith("owned.mp4"))
        home.acknowledge("op-1", true, null)
        home.download(); assertTrue(home.state.value.repeatConfirmation); assertEquals(1, calls)
        home.dismissRepeat(); assertFalse(home.state.value.repeatConfirmation)
        home.download(); home.download(confirmed = true)
        assertEquals(2, calls)
    }

    @Test fun enqueueFailureUnlocksRetryWithoutLosingReadySource() = runTest {
        var calls = 0
        val home = HomeSession(this, SourceAnalyzer { source() }, { "op-${++calls}" })
        home.edit("https://example.invalid/owned.mp4"); home.analyze(); runCurrent(); home.download()
        home.acknowledge("op-1", false, TransferError.NO_DISK_SPACE)
        assertFalse(home.state.value.enqueuePending)
        assertFalse(home.state.value.accepted)
        assertEquals(AnalysisPhase.READY, home.state.value.phase)
        assertEquals(TransferError.NO_DISK_SPACE, home.state.value.enqueueError)
        home.download(); assertEquals(2, calls)
    }

    @Test fun explicitCancelPreservesDraftAndDoesNotEnqueue() = runTest {
        var calls = 0
        val home = HomeSession(this, SourceAnalyzer { delay(10_000); source() }, { calls++; "op" })
        home.edit("https://example.invalid/owned.mp4"); home.analyze(); runCurrent()
        home.cancelAnalysis(); advanceUntilIdle(); home.download()
        assertEquals(AnalysisPhase.IDLE, home.state.value.phase)
        assertEquals("https://example.invalid/owned.mp4", home.state.value.draft)
        assertEquals(0, calls)
    }

    @Test fun invalidAndUnsupportedSourcesAreTypedAndNeverReady() = runTest {
        var analyses = 0
        val home = HomeSession(this, SourceAnalyzer { analyses++; throw AnalysisFailure(AnalysisError.UNSUPPORTED_SOURCE) }, { "op" })
        home.edit("file:///private/clip.mp4"); home.analyze(); runCurrent()
        assertEquals(0, analyses)
        assertEquals(AnalysisError.INVALID_URL, home.state.value.analysisError)
        home.edit("https://example.invalid/script-player"); home.analyze(); runCurrent()
        assertEquals(1, analyses)
        assertEquals(AnalysisPhase.ERROR, home.state.value.phase)
        assertEquals(AnalysisError.UNSUPPORTED_SOURCE, home.state.value.analysisError)
        assertNull(home.state.value.variant)
    }
}
