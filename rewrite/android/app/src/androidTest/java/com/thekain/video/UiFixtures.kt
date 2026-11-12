package com.thekain.video

import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import com.thekain.video.presentation.*
import com.thekain.video.source.*

/** Renderer fixtures only, never registered in production or presented as transfer/device evidence. */
object UiFixtures {
    val source = SourceMedia("Очень длинное название собственного видео — прогулка у воды, заметки и спокойные кадры для проверки интерфейса", "owned.example",
        listOf(MediaVariant("mp4", DirectLinks.parse("https://owned.example/owned.mp4"), 16384),
            MediaVariant("webm", DirectLinks.parse("https://owned.example/owned.webm"))))
    val ready = HomeState("https://owned.example/owned-page", AnalysisPhase.READY, source, "mp4")
    fun task(id: String, state: TransferState) = DownloadTask(id, "https://owned.example/owned.mp4",
        "Собственное видео — очень длинное название для проверки переноса строк.mp4", "video/mp4", state = state,
        bytes = if (state == TransferState.DOWNLOADING) 768 * 1024 else 4 * 1024 * 1024, total = 4L * 1024 * 1024,
        error = if (state == TransferState.FAILED) TransferError.NO_DISK_SPACE else null,
        outputUri = if (state == TransferState.COMPLETED) "content://owned-fixture/output" else null,
        verifiedBytes = if (state == TransferState.COMPLETED) 4L * 1024 * 1024 else null)
    fun cases(settings: UiSettings) = linkedMapOf(
        "home-empty" to ScreenState(settings = settings),
        "home-ready" to ScreenState(home = ready, settings = settings),
        "downloads-running" to ScreenState(tasks = listOf(task("running", TransferState.DOWNLOADING)), page = Page.DOWNLOADS, settings = settings),
        "downloads-failed" to ScreenState(tasks = listOf(task("failed", TransferState.FAILED)), page = Page.DOWNLOADS, settings = settings),
        "downloads-completed" to ScreenState(tasks = listOf(task("complete", TransferState.COMPLETED)), page = Page.DOWNLOADS, settings = settings),
        "library" to ScreenState(tasks = listOf(task("complete", TransferState.COMPLETED)), page = Page.LIBRARY, settings = settings),
        "settings" to ScreenState(page = Page.SETTINGS, settings = settings))
}
