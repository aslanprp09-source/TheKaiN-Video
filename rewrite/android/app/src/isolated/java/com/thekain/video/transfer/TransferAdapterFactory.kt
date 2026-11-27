package com.thekain.video.transfer
import android.content.Context
import com.thekain.video.domain.*
import com.thekain.video.source.SourceAnalyzerFactory

object TransferAdapterFactory {
    fun create(context: Context, connections: () -> Connections) = object : TransferAdapter {
        override suspend fun fetch(task: com.thekain.video.data.DownloadTask, stage: java.io.File,
            identity: suspend (Long?, String?, Boolean) -> Unit, progress: suspend (Long, Long?) -> Unit): java.io.File {
            if (task.backend == TransferBackend.DIRECT) return DirectTransfer(connections()).fetch(task, stage, identity, progress)
            if (!SourceAnalyzerFactory.permitsOwnedVideo(task.url) || task.mime != "video/mp4")
                throw TransferFailure(TransferError.SOURCE_POLICY)
            return IsolatedProgressiveTransfer(context, com.thekain.runtime.lab.RemoteHttpsTransport.Policy.publicOrigins(
                setOf("https://raw.githubusercontent.com"))).fetch(task, stage, identity, progress)
        }
    }
}
