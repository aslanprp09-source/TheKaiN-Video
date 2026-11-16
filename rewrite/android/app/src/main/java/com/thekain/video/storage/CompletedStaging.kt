package com.thekain.video.storage

import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.TransferState
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Remove only known private payloads belonging to an immutable completed UUID, never follow links. */
class CompletedStaging(private val root: File) {
    fun delete(task: DownloadTask) {
        require(task.state == TransferState.COMPLETED)
        require(UUID.fromString(task.id).toString() == task.id)
        require(!Files.isSymbolicLink(root.toPath()))
        val directory = File(root, task.id)
        if (!Files.exists(directory.toPath(), NOFOLLOW_LINKS)) return
        require(!Files.isSymbolicLink(directory.toPath()) && directory.isDirectory &&
            directory.canonicalFile.parentFile == root.canonicalFile)
        // Validate the whole bounded two-level structure before deleting any private payload.
        val attempts = requireNotNull(directory.listFiles()).toList()
        require(attempts.size <= 10000)
        val payloads = attempts.map { attempt ->
            require(Regex("[1-9][0-9]*").matches(attempt.name) &&
                !Files.isSymbolicLink(attempt.toPath()) && attempt.isDirectory &&
                attempt.canonicalFile.parentFile == directory.canonicalFile)
            requireNotNull(attempt.listFiles()).toList().also { children ->
                require(children.size <= 1)
                children.forEach { file ->
                    require(file.name == "payload.part" && !Files.isSymbolicLink(file.toPath()) &&
                        Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) &&
                        file.canonicalFile.parentFile == attempt.canonicalFile)
                }
            }
        }.flatten()
        payloads.forEach { Files.deleteIfExists(it.toPath()) }
        attempts.forEach { Files.deleteIfExists(it.toPath()) }
        Files.deleteIfExists(directory.toPath())
    }
}
