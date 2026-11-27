package com.thekain.video.source

import android.content.Context
import com.thekain.video.BuildConfig
import com.thekain.video.transfer.Connections

object SourceAnalyzerFactory {
    val ownedPage = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/${BuildConfig.INTERNAL_RUNTIME_SOURCE}/rewrite/android/app/src/androidTest/assets/owned-isolated-page.html"
    fun create(context: Context, connections: () -> Connections): SourceAnalyzer {
        val isolated by lazy { IsolatedSourceAnalyzer(context, com.thekain.runtime.lab.RemoteHttpsTransport.Policy.publicOrigins(setOf("https://raw.githubusercontent.com"))) }
        return SourceAnalyzer { input ->
            // One owned immutable fixture only. No public feature switch, broad site/YouTube enablement, or fallback around a broker refusal.
            if (input.trim() == ownedPage) isolated.analyze(input) else HttpsSourceAnalyzer(connections()).analyze(input)
        }
    }
}
