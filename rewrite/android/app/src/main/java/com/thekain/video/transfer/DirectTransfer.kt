package com.thekain.video.transfer

import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

fun interface Connections { fun open(url: URL): HttpsURLConnection }
val systemConnections = Connections { it.openConnection() as HttpsURLConnection }

interface TransferAdapter {
    suspend fun fetch(task: DownloadTask, stage: File,
        identity: suspend (Long?, String?, Boolean) -> Unit,
        progress: suspend (Long, Long?) -> Unit): File
}

/** No cookies, custom trust managers or cleartext fallback in the production factory. */
class DirectTransfer(
    private val connections: Connections = systemConnections,
    private val freeBytes: (File) -> Long = { it.usableSpace }
) : TransferAdapter {
    private fun open(source: String, headers: Map<String, String>): HttpsURLConnection {
        var address = source
        repeat(6) {
            val uri = try { URI(address) } catch (_: Exception) { throw TransferFailure(TransferError.INVALID_URL) }
            if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null)
                throw TransferFailure(TransferError.INVALID_URL)
            val connection = connections.open(uri.toURL()).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 5_000
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            val status = try { connection.responseCode } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                address = try { uri.resolve(location ?: "").toString() } catch (_: Exception) {
                    throw TransferFailure(TransferError.REMOTE_FORBIDDEN)
                }
            } else {
                if (status !in 200..299) {
                    val error = TransferRules.httpError(status)
                    val delay = TransferRules.retryAfterMillis(connection.getHeaderField("Retry-After"), System.currentTimeMillis())
                    connection.disconnect()
                    // Long server cooldowns need a deliberate later retry, never an early automatic request.
                    throw TransferFailure(error.code, error.retryable && delay <= 86_400_000, delay.coerceAtMost(86_400_000))
                }
                return connection
            }
        }
        throw TransferFailure(TransferError.REMOTE_FORBIDDEN)
    }

    override suspend fun fetch(task: DownloadTask, stage: File,
        identity: suspend (Long?, String?, Boolean) -> Unit,
        progress: suspend (Long, Long?) -> Unit): File {
        stage.parentFile!!.mkdirs()
        var response: HttpsURLConnection? = null
        var bytes = if (stage.exists()) stage.length() else 0
        try {
            val total: Long?
            if (bytes > 0) {
                if (!task.resumable || task.etag == null || task.total == null)
                    throw TransferFailure(TransferError.INTERRUPTED)
                if (bytes == task.total) {
                    progress(bytes, task.total)
                    return stage // Still unverified; never a completion shortcut.
                }
                response = open(task.url, mapOf("Range" to "bytes=$bytes-", "If-Range" to task.etag))
                if (!TransferRules.validResume(response.responseCode, response.getHeaderField("Content-Range"),
                        response.getHeaderField("ETag"), task.etag, bytes, task.total))
                    throw TransferFailure(TransferError.RESUME_UNSUPPORTED)
                total = task.total
                identity(total, task.etag, true)
            } else {
                response = open(task.url, mapOf("Range" to "bytes=0-0"))
                val tag = response.getHeaderField("ETag")
                val match = Regex("bytes 0-0/(\\d+)").matchEntire(response.getHeaderField("Content-Range").orEmpty())
                val known = match?.groupValues?.get(1)?.toLongOrNull()
                val resumable = response.responseCode == 206 && known != null && known > 0 &&
                    tag != null && tag.startsWith('"') && tag.endsWith('"')
                if (resumable) {
                    response.disconnect()
                    response = open(task.url, mapOf("If-Match" to tag!!))
                    if (response.responseCode != 200 || response.getHeaderField("ETag") != tag)
                        throw TransferFailure(TransferError.RESUME_UNSUPPORTED)
                    total = known
                    identity(total, tag, true)
                } else {
                    if (response.responseCode == 206) {
                        response.disconnect()
                        response = open(task.url, emptyMap())
                    }
                    total = response.contentLengthLong.takeIf { it >= 0 }
                    identity(total, null, false)
                }
            }
            val reserve = 8L * 1024 * 1024
            if (freeBytes(stage.parentFile!!) < (total?.minus(bytes) ?: 65536).coerceAtLeast(65536) + reserve)
                throw TransferFailure(TransferError.NO_DISK_SPACE)
            progress(bytes, total)
            response!!.inputStream.use { input ->
                FileOutputStream(stage, bytes > 0).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (freeBytes(stage.parentFile!!) < count + reserve)
                            throw TransferFailure(TransferError.NO_DISK_SPACE)
                        output.write(buffer, 0, count)
                        bytes += count
                        progress(bytes, total)
                    }
                    output.fd.sync()
                }
            }
            currentCoroutineContext().ensureActive()
            if (total != null && bytes != total) throw TransferFailure(TransferError.NETWORK_LOST, true)
            return stage
        } finally {
            response?.disconnect()
        }
    }
}
