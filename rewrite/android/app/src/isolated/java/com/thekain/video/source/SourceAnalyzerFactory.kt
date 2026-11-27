package com.thekain.video.source

import android.content.Context
import com.thekain.video.BuildConfig
import com.thekain.video.transfer.Connections
import com.thekain.video.domain.TransferBackend

object SourceAnalyzerFactory {
    val ownedPage = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/${BuildConfig.INTERNAL_RUNTIME_SOURCE}/rewrite/android/app/src/androidTest/assets/owned-isolated-page.html"
    val ownedDownloadPage = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/${BuildConfig.INTERNAL_RUNTIME_SOURCE}/rewrite/android/app/src/androidTest/assets/owned-download-page.html"
    const val ownedVideo = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/11fa103624275958f089d9566c7722fc64133094/rewrite/android/app/src/androidTest/assets/owned-sample.mp4"
    fun create(context: Context, connections: () -> Connections): SourceAnalyzer {
        val isolated by lazy { IsolatedSourceAnalyzer(context, com.thekain.runtime.lab.RemoteHttpsTransport.Policy.publicOrigins(setOf("https://raw.githubusercontent.com"))) }
        return SourceAnalyzer { input ->
            // One owned immutable fixture only. No public feature switch, broad site/YouTube enablement, or fallback around a broker refusal.
            when (input.trim()) {
                ownedPage -> isolated.analyze(input)
                ownedDownloadPage -> isolated.analyze(input).let { media ->
                    // Extractor metadata is a declaration, never a grant. The app grants this one
                    // immutable owned progressive fixture only, after verified worker retirement.
                    media.copy(variants = media.variants.map { variant ->
                        if (variant.request.url != ownedVideo) variant else variant.copy(downloadAllowed = true,
                            request = variant.request.copy(backend = TransferBackend.ISOLATED_OWNED))
                    })
                }
                else -> HttpsSourceAnalyzer(connections()).analyze(input)
            }
        }
    }
}
