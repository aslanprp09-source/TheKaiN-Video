package com.thekain.video.transfer
import android.content.Context
import com.thekain.video.domain.*

object TransferAdapterFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, connections: () -> Connections) = object : TransferAdapter {
        override suspend fun fetch(task: com.thekain.video.data.DownloadTask, stage: java.io.File,
            identity: suspend (Long?, String?, Boolean) -> Unit, progress: suspend (Long, Long?) -> Unit): java.io.File {
            // Upgrading back to a standard APK never downgrades an isolated job to direct transport.
            if (task.backend != TransferBackend.DIRECT) throw TransferFailure(TransferError.SOURCE_POLICY)
            return DirectTransfer(connections()).fetch(task, stage, identity, progress)
        }
    }
}
