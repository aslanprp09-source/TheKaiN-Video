package com.thekain.video.source

import com.thekain.video.domain.DirectLinks
import com.thekain.video.domain.DirectRequest
import java.net.URI

enum class AnalysisError { INVALID_URL, UNSUPPORTED_SOURCE, NO_FORMATS, TOO_LARGE, FORBIDDEN, MISSING, RATE_LIMITED, TIMEOUT, NETWORK, UNKNOWN }
class AnalysisFailure(val code: AnalysisError) : Exception(code.name)

data class MediaVariant(val id: String, val request: DirectRequest, val bytes: Long? = null) {
    val audioOnly get() = request.mime.startsWith("audio/")
    val extension get() = request.name.substringAfterLast('.').uppercase(java.util.Locale.ROOT)
    override fun toString() = "MediaVariant(id=$id, mime=${request.mime}, bytes=$bytes)"
}

data class SourceMedia(val title: String, val host: String, val variants: List<MediaVariant>, val poster: ByteArray? = null) {
    override fun toString() = "SourceMedia(host=$host, variants=${variants.size})"
}

fun interface SourceAnalyzer { suspend fun analyze(input: String): SourceMedia }

object SourceUrls {
    fun parse(input: String): URI {
        try {
            require(input.none { it.code < 32 || it.code == 127 })
            val text = input.trim()
            require(text.none { it.isWhitespace() || it == '\\' })
            val uri = URI(text)
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
            require(uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0)
            return uri
        } catch (_: Exception) { throw AnalysisFailure(AnalysisError.INVALID_URL) }
    }

    fun direct(uri: URI): DirectRequest? = try { DirectLinks.parse(uri.toString()) } catch (_: Exception) { null }
}
