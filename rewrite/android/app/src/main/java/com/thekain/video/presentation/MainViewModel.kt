package com.thekain.video.presentation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.thekain.video.TheKaiNApp
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val graph = (app as TheKaiNApp).graph
    val preferences = UiPreferences(app)
    val home = HomeSession(viewModelScope, graph.sourceAnalyzer, graph::submit, saved["draft"] ?: "")
    val tasks = graph.repository.changes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val page = saved.getStateFlow("page", Page.HOME.name)
    private val messages = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val accepted = messages.asSharedFlow()
    init {
        viewModelScope.launch {
            graph.submission.collect { value ->
                if (!value.busy && value.operation.isNotEmpty()) {
                    val wasPending = home.state.value.enqueuePending
                    home.acknowledge(value.operation, value.accepted != null, value.error)
                    if (wasPending && !home.state.value.enqueuePending && value.accepted != null) messages.tryEmit(true)
                }
            }
        }
    }
    fun edit(value: String) { home.edit(value); saved["draft"] = home.state.value.draft }
    fun navigate(page: Page) {
        if (page == Page.SETTINGS && saved.get<String>("page") != Page.SETTINGS.name)
            saved["settings_parent"] = saved.get<String>("page") ?: Page.HOME.name
        saved["page"] = page.name
    }
    fun back() { saved["page"] = if (page.value == Page.SETTINGS.name) saved.get<String>("settings_parent") ?: Page.HOME.name else Page.HOME.name }
    fun action(id: String, kind: String) = graph.action(id, kind)
}
