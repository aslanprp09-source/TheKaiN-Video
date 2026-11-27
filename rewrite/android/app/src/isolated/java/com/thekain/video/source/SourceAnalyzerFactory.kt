package com.thekain.video.source

import android.content.Context
import com.thekain.video.BuildConfig
import com.thekain.video.transfer.Connections
import com.thekain.video.domain.TransferBackend

object SourceAnalyzerFactory {
    val ownedPage = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/${BuildConfig.INTERNAL_RUNTIME_SOURCE}/rewrite/android/app/src/androidTest/assets/owned-isolated-page.html"
    val ownedDownloadPage = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/${BuildConfig.INTERNAL_RUNTIME_SOURCE}/rewrite/android/app/src/androidTest/assets/owned-download-page.html"
    val ownedFormatsPage = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/${BuildConfig.INTERNAL_RUNTIME_SOURCE}/rewrite/android/app/src/androidTest/assets/owned-formats-page.html"
    const val ownedVideo = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/11fa103624275958f089d9566c7722fc64133094/rewrite/android/app/src/androidTest/assets/owned-sample.mp4"
    const val ownedVideo320 = "https://raw.githubusercontent.com/aslanprp09-source/TheKaiN-Video/5e3b70b75a3d70780e49e24ddbf64a10356f2c09/rewrite/android/app/src/androidTest/assets/owned-sample-320.mp4"
    fun permitsOwnedVideo(url: String) = url == ownedVideo || url == ownedVideo320
    fun create(context: Context, connections: () -> Connections): SourceAnalyzer {
        val isolated by lazy { IsolatedSourceAnalyzer(context, com.thekain.runtime.lab.RemoteHttpsTransport.Policy.publicOrigins(setOf("https://raw.githubusercontent.com"))) }
        return SourceAnalyzer { input ->
            // Exact owned fixtures only. No broad site switch or fallback around a broker refusal.
            when (input.trim()) {
                ownedPage -> isolated.analyze(input)
                ownedDownloadPage, ownedFormatsPage -> isolated.analyze(input).let { media ->
                    // Extractor metadata is a declaration, never a grant. Only the exact immutable
                    // owned fixtures are granted, after verified worker retirement.
                    media.copy(variants = media.variants.map { variant ->
                        val granted = variant.request.url == ownedVideo || input.trim() == ownedFormatsPage && variant.request.url == ownedVideo320
                        if (!granted) variant else variant.copy(downloadAllowed = true,
                            request = variant.request.copy(backend = TransferBackend.ISOLATED_OWNED))
                    })
                }
                else -> HttpsSourceAnalyzer(connections()).analyze(input)
            }
        }
    }
}
