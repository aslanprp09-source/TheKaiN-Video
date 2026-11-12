package com.thekain.video.domain

import java.net.URI
import java.util.Locale
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class TransferState {
    QUEUED, DOWNLOADING, PAUSING, PAUSED, VERIFYING, PUBLISHING, RETRY_WAIT,
    COMPLETED, FAILED, CANCEL_REQUESTED, CANCELED
}

enum class TransferError {
    INVALID_URL, REMOTE_FORBIDDEN, REMOTE_MISSING, RATE_LIMITED, REMOTE_SERVER,
    NETWORK_LOST, TIMEOUT, NO_DISK_SPACE, PERMISSION_DENIED, VERIFY_FAILED,
    RESUME_UNSUPPORTED, INTERRUPTED, BACKGROUND_RESTRICTED, DRM_PROTECTED, UNKNOWN
}

class TransferFailure(val code: TransferError, val retryable: Boolean = false,
                      val retryAfterMillis: Long = 0) : Exception(code.name)

data class DirectRequest(val url: String, val name: String, val mime: String) {
    override fun toString() = "DirectRequest(name=$name)" // Never print signed source URLs.
}

object DirectLinks {
    private val types = mapOf("mp4" to "video/mp4", "webm" to "video/webm", "mov" to "video/quicktime",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4")

    fun parse(input: String): DirectRequest {
        val url = input.trim()
        try {
            require(input.none { it.code < 32 || it.code == 127 })
            require(url.none { it.isWhitespace() || it == '\\' })
            val uri = URI(url)
            require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
            require(uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0)
            val filename = uri.path.orEmpty().substringAfterLast('/')
            val extension = filename.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = types[extension] ?: throw IllegalArgumentException()
            return DirectRequest(url, safeName(filename), mime)
        } catch (_: Exception) {
            throw TransferFailure(TransferError.INVALID_URL)
        }
    }

    fun safeName(value: String): String {
        val cleaned = value.replace(Regex("[\\x00-\\x1f\\x7f<>:\"/\\\\|?*]"), "_").trim(' ', '.')
        val extension = cleaned.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (extension in types) {
            // Reserve space for the supported suffix before truncating the stem.
            val stem = cleaned.substringBeforeLast('.').take(96 - extension.length - 1)
                .trimEnd(' ', '.').ifBlank { "media" }
            return "$stem.$extension"
        }
        return cleaned.take(96).ifBlank { "media.mp4" }
    }

    fun publishedName(name: String, id: String): String {
        val safe = safeName(name)
        val ext = safe.substringAfterLast('.', "mp4")
        return safe.substringBeforeLast('.', safe).take(72) + "-" + id + "." + ext
    }
}

object TransferRules {
    private val terminal = setOf(TransferState.COMPLETED, TransferState.CANCELED)
    fun allowed(from: TransferState, to: TransferState): Boolean {
        if (from in terminal) return false
        if (from == to) return true
        return when (from) {
            TransferState.QUEUED -> to in setOf(TransferState.DOWNLOADING, TransferState.VERIFYING, TransferState.PUBLISHING, TransferState.CANCELED, TransferState.FAILED)
            TransferState.DOWNLOADING -> to in setOf(TransferState.VERIFYING, TransferState.RETRY_WAIT, TransferState.PAUSING, TransferState.CANCEL_REQUESTED, TransferState.FAILED)
            TransferState.PAUSING -> to in setOf(TransferState.PAUSED, TransferState.CANCEL_REQUESTED, TransferState.FAILED)
            TransferState.PAUSED -> to in setOf(TransferState.QUEUED, TransferState.CANCELED)
            TransferState.VERIFYING -> to in setOf(TransferState.PUBLISHING, TransferState.FAILED, TransferState.CANCEL_REQUESTED)
            TransferState.PUBLISHING -> to in setOf(TransferState.COMPLETED, TransferState.FAILED)
            TransferState.RETRY_WAIT -> to in setOf(TransferState.QUEUED, TransferState.DOWNLOADING, TransferState.CANCELED, TransferState.FAILED)
            TransferState.FAILED -> to == TransferState.QUEUED
            TransferState.CANCEL_REQUESTED -> to == TransferState.CANCELED
            else -> false
        }
    }

    fun percent(state: TransferState, bytes: Long, total: Long?): Int? = when {
        state == TransferState.COMPLETED -> 100
        total == null || total <= 0 -> null
        else -> (bytes.toDouble() / total * 100).toInt().coerceIn(0, 99)
    }

    fun httpError(status: Int): TransferFailure = when (status) {
        401, 403 -> TransferFailure(TransferError.REMOTE_FORBIDDEN)
        404, 410 -> TransferFailure(TransferError.REMOTE_MISSING)
        412, 416 -> TransferFailure(TransferError.RESUME_UNSUPPORTED)
        429 -> TransferFailure(TransferError.RATE_LIMITED, true)
        in 500..599 -> TransferFailure(TransferError.REMOTE_SERVER, true)
        else -> TransferFailure(TransferError.UNKNOWN)
    }

    fun validResume(status: Int, range: String?, etag: String?, expectedTag: String, offset: Long, total: Long): Boolean =
        status == 206 && etag == expectedTag && expectedTag.startsWith('"') && expectedTag.endsWith('"') &&
            range == "bytes $offset-${total - 1}/$total"

    fun retryAfterMillis(value: String?, now: Long): Long {
        val text = value?.trim() ?: return 0
        text.toLongOrNull()?.let { seconds ->
            if (seconds <= 0) return 0
            return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
        }
        return try { (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceAtLeast(0) }
        catch (_: Exception) { 0 }
    }
}
