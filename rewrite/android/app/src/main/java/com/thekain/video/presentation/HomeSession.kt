package com.thekain.video.presentation

import com.thekain.video.domain.*
import com.thekain.video.source.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class AnalysisPhase { IDLE, ANALYZING, READY, ERROR }
data class HomeState(
    val draft: String = "", val phase: AnalysisPhase = AnalysisPhase.IDLE,
    val source: SourceMedia? = null, val selected: String? = null,
    val analysisError: AnalysisError? = null, val enqueueError: TransferError? = null,
    val enqueuePending: Boolean = false, val accepted: Boolean = false, val repeatConfirmation: Boolean = false
) {
    val variant get() = source?.variants?.firstOrNull { it.id == selected }
}

/** Pure application state; no Android/Compose imports, stale results or asynchronous double-submit window. */
class HomeSession(private val scope: CoroutineScope, private val analyzer: SourceAnalyzer,
                  private val enqueue: (DirectRequest) -> String?, initialDraft: String = "") {
    private val mutable = MutableStateFlow(HomeState(draft = initialDraft))
    val state = mutable.asStateFlow()
    private var generation = 0
    private var analysis: Job? = null
    private var pendingOperation: String? = null
    private var pendingSource: String? = null
    private val acceptedSources = mutableSetOf<String>()

    fun edit(value: String) {
        if (mutable.value.enqueuePending) return
        generation++
        analysis?.cancel()
        mutable.value = HomeState(draft = value)
    }

    fun analyze() {
        val previous = mutable.value
        if (previous.enqueuePending) return
        generation++
        val mine = generation
        analysis?.cancel()
        try { SourceUrls.parse(previous.draft) } catch (error: AnalysisFailure) {
            mutable.value = previous.copy(phase = AnalysisPhase.ERROR, source = null, selected = null, analysisError = error.code)
            return
        }
        mutable.value = HomeState(draft = previous.draft, phase = AnalysisPhase.ANALYZING)
        analysis = scope.launch {
            try {
                val result = analyzer.analyze(previous.draft)
                if (result.variants.isEmpty()) throw AnalysisFailure(AnalysisError.NO_FORMATS)
                if (mine != generation) return@launch
                val first = result.variants.first()
                mutable.value = HomeState(previous.draft, AnalysisPhase.READY, result, first.id,
                    accepted = first.request.url in acceptedSources)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (mine == generation) mutable.value = HomeState(previous.draft, AnalysisPhase.ERROR,
                    analysisError = (error as? AnalysisFailure)?.code ?: AnalysisError.UNKNOWN)
            }
        }
    }

    fun cancelAnalysis() { edit(mutable.value.draft) }
    fun select(id: String) {
        val value = mutable.value
        val variant = value.source?.variants?.firstOrNull { it.id == id } ?: return
        if (!value.enqueuePending) mutable.value = value.copy(selected = id, accepted = variant.request.url in acceptedSources)
    }

    fun download(confirmed: Boolean = false) {
        val value = mutable.value
        if (value.phase != AnalysisPhase.READY || value.enqueuePending) return
        val variant = value.variant ?: return
        if (variant.request.url in acceptedSources && !confirmed) {
            mutable.value = value.copy(repeatConfirmation = true)
            return
        }
        mutable.value = value.copy(enqueuePending = true, repeatConfirmation = false, enqueueError = null)
        pendingSource = variant.request.url
        val operation = try { enqueue(variant.request) } catch (_: Exception) { null }
        pendingOperation = operation
        if (operation == null) mutable.value = mutable.value.copy(enqueuePending = false, enqueueError = TransferError.UNKNOWN)
    }

    fun dismissRepeat() { mutable.value = mutable.value.copy(repeatConfirmation = false) }
    fun acknowledge(operation: String, accepted: Boolean, error: TransferError?) {
        if (operation != pendingOperation) return
        if (accepted) pendingSource?.let(acceptedSources::add)
        pendingOperation = null
        pendingSource = null
        mutable.value = mutable.value.copy(enqueuePending = false,
            accepted = mutable.value.variant?.request?.url in acceptedSources, enqueueError = error)
    }
}
