package com.thekain.video.domain

import com.thekain.video.data.DownloadTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RemovalOutcome { RECORD_REMOVED, FILE_AND_RECORD_REMOVED, ALREADY_REMOVED, NOT_COMPLETED, FAILED }

/** Coordinates confirmed deletion; a failed file/cache operation never removes its recovery row. */
class CompletedDownloadRemover(
    private val find: suspend (String) -> DownloadTask?,
    private val removeRecord: suspend (String) -> Boolean,
    private val deleteOutput: suspend (DownloadTask) -> Unit,
    private val clearStaging: (DownloadTask) -> Unit,
    private val cancelNotification: (DownloadTask) -> Unit
) {
    private val mutex = Mutex()

    suspend fun remove(id: String, withFile: Boolean): RemovalOutcome = mutex.withLock {
        try {
            val task = find(id) ?: return@withLock RemovalOutcome.ALREADY_REMOVED
            if (task.state != TransferState.COMPLETED) return@withLock RemovalOutcome.NOT_COMPLETED
            if (withFile) deleteOutput(task)
            clearStaging(task)
            if (!removeRecord(id) && find(id) != null) return@withLock RemovalOutcome.FAILED
            bestEffort { cancelNotification(task) } // The record is already gone; do not report a false failure.
            if (withFile) RemovalOutcome.FILE_AND_RECORD_REMOVED else RemovalOutcome.RECORD_REMOVED
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemovalOutcome.FAILED // No raw URI, filename or provider exception reaches UI/logs.
        }
    }

    /** Serializes terminal notifications with removal so a deleted row cannot recreate its notification. */
    suspend fun notifyIfPresent(id: String, notify: (DownloadTask) -> Unit) = mutex.withLock {
        find(id)?.let { task -> bestEffort { notify(task) } }
    }

    private fun bestEffort(action: () -> Unit) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Notification failure does not undo verified storage/database work. */ }
    }
}
