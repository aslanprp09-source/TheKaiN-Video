package com.thekain.video.source
import android.content.Context
import com.thekain.video.transfer.Connections
object SourceAnalyzerFactory {
    fun create(context: Context, connections: () -> Connections): SourceAnalyzer = SourceAnalyzer { HttpsSourceAnalyzer(connections()).analyze(it) }
}
