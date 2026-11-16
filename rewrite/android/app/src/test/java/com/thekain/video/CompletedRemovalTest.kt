package com.thekain.video

import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import com.thekain.video.storage.CompletedStaging
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CompletedRemovalTest {
    @get:Rule val folder = TemporaryFolder()
    private fun task(state: TransferState = TransferState.COMPLETED) = DownloadTask(
        UUID.randomUUID().toString(), "https://owned.invalid/fixture.mp4", "fixture.mp4", "video/mp4", state)

    @Test fun listOnlyPreservesPublishedOutputAndRemovesPrivatePayloadBeforeRow() = runTest {
        var row: DownloadTask? = task()
        val id = row!!.id
        val calls = mutableListOf<String>()
        val remover = CompletedDownloadRemover({ row }, { calls += "row"; row = null; true },
            { error("List-only must not call storage provider") }, { calls += "staging" }, { calls += "notification" })
        assertEquals(RemovalOutcome.RECORD_REMOVED, remover.remove(id, false))
        assertEquals(listOf("staging", "row", "notification"), calls)
        assertNull(row)
    }

    @Test fun fileFailureRetainsRowAndNeverReportsRemoved() = runTest {
        for (failure in listOf(IOException("owned I/O failure"), SecurityException("owned denied"), IllegalArgumentException())) {
            var row: DownloadTask? = task()
            val remover = CompletedDownloadRemover({ row }, { row = null; true }, { throw failure },
                { error("Must stop after failure") }, { error("Must stop after failure") })
            assertEquals(RemovalOutcome.FAILED, remover.remove(row!!.id, true))
            assertNotNull(row)
        }
    }

    @Test fun partialFileDeletionWithStagingOrDatabaseFailureKeepsRetryableRow() = runTest {
        for (at in listOf("staging", "row")) {
            var row: DownloadTask? = task()
            var fileDeleted = false
            val remover = CompletedDownloadRemover({ row }, { if (at == "row") throw IOException(); row = null; true },
                { fileDeleted = true }, { if (at == "staging") throw IOException() }, {})
            assertEquals(RemovalOutcome.FAILED, remover.remove(row!!.id, true))
            assertTrue(fileDeleted)
            assertNotNull(row)
        }
    }

    @Test fun missingRecordDoesNotClaimItsDeviceFileWasDeleted() = runTest {
        val remover = CompletedDownloadRemover({ null }, { error("Unexpected") }, { error("Unexpected") },
            { error("Unexpected") }, { error("Unexpected") })
        assertEquals(RemovalOutcome.ALREADY_REMOVED, remover.remove(UUID.randomUUID().toString(), true))
    }

    @Test fun everyNonCompletedStateIsProtectedFromAllDeletionModes() = runTest {
        for (state in TransferState.entries.filter { it != TransferState.COMPLETED }) {
            val row = task(state)
            val remover = CompletedDownloadRemover({ row }, { error("Unexpected") }, { error("Unexpected") },
                { error("Unexpected") }, { error("Unexpected") })
            for (withFile in listOf(false, true)) assertEquals(RemovalOutcome.NOT_COMPLETED, remover.remove(row.id, withFile))
        }
    }

    @Test fun notificationFailureCannotTurnSuccessfulDeletionIntoFailure() = runTest {
        var row: DownloadTask? = task()
        val remover = CompletedDownloadRemover({ row }, { row = null; true }, {}, {}, { throw SecurityException() })
        assertEquals(RemovalOutcome.FILE_AND_RECORD_REMOVED, remover.remove(row!!.id, true))
        assertNull(row)
    }

    @Test fun duplicateRequestsSerializeAndDeletedRecordCannotPostTerminalNotification() = runTest {
        var row: DownloadTask? = task()
        val id = row!!.id
        val gate = CompletableDeferred<Unit>()
        var deleted = 0
        var notified = 0
        val remover = CompletedDownloadRemover({ row }, { row = null; true }, { deleted++; gate.await() }, {}, {})
        val first = async { remover.remove(id, true) }
        runCurrent()
        val second = async { remover.remove(id, true) }
        val notification = launch { remover.notifyIfPresent(id) { notified++ } }
        runCurrent()
        assertEquals(1, deleted)
        gate.complete(Unit)
        assertEquals(RemovalOutcome.FILE_AND_RECORD_REMOVED, first.await())
        assertEquals(RemovalOutcome.ALREADY_REMOVED, second.await())
        notification.join()
        assertEquals(0, notified)
    }

    @Test fun cancellationPropagatesAndRetainsRecord() = runTest {
        val row = task()
        val remover = CompletedDownloadRemover({ row }, { error("Unexpected") },
            { throw CancellationException("owned cancellation") }, {}, {})
        try { remover.remove(row.id, true); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
    }

    @Test fun failedGuardedDatabaseDeleteDoesNotClaimSuccess() = runTest {
        val row = task()
        val remover = CompletedDownloadRemover({ row }, { false }, {}, {}, { error("Unexpected") })
        assertEquals(RemovalOutcome.FAILED, remover.remove(row.id, false))
    }

    @Test fun privateCleanupRemovesOnlyCompletedUuidAndAllOfItsAttempts() {
        val root = folder.newFolder("transfers")
        val completed = task()
        val other = task(TransferState.DOWNLOADING)
        listOf(1, 2).forEach { File(root, "${completed.id}/$it/payload.part").apply { requireNotNull(parentFile).mkdirs(); writeText("owned") } }
        val active = File(root, "${other.id}/1/payload.part").apply { requireNotNull(parentFile).mkdirs(); writeText("active") }
        val cleaner = CompletedStaging(root)
        cleaner.delete(completed)
        cleaner.delete(completed) // Missing staging after interruption is safe to retry.
        assertFalse(File(root, completed.id).exists())
        assertEquals("active", active.readText())
        try { cleaner.delete(other); fail("Active staging must remain") } catch (_: IllegalArgumentException) { }
        assertTrue(active.exists())
    }

    @Test fun stagingCleanupRejectsTraversalLinksAndUnknownFilesWithoutFollowingThem() {
        val root = folder.newFolder("safe-root")
        val outside = folder.newFolder("outside")
        val precious = File(outside, "payload.part").apply { writeText("keep") }
        val cleaner = CompletedStaging(root)
        val completed = task()
        for (id in listOf("../outside", "/outside", "invalid", "00000000-0-0-0-0")) {
            try { cleaner.delete(completed.copy(id = id)); fail("Unsafe UUID") } catch (_: IllegalArgumentException) { }
        }
        val directory = File(root, completed.id).apply { mkdirs() }
        Files.createSymbolicLink(File(directory, "1").toPath(), outside.toPath())
        try { cleaner.delete(completed); fail("Do not follow a symlink") } catch (_: IllegalArgumentException) { }
        assertEquals("keep", precious.readText())
        Files.delete(File(directory, "1").toPath())
        val attempt = File(directory, "1").apply { mkdirs() }
        val unexpected = File(attempt, "unrecognized.txt").apply { writeText("keep") }
        try { cleaner.delete(completed); fail("Unknown private content must remain") } catch (_: IllegalArgumentException) { }
        assertTrue(unexpected.exists())
        unexpected.delete()
        Files.createSymbolicLink(File(attempt, "payload.part").toPath(), precious.toPath())
        try { cleaner.delete(completed); fail("Linked payload must remain") } catch (_: IllegalArgumentException) { }
        assertEquals("keep", precious.readText())
    }
}
