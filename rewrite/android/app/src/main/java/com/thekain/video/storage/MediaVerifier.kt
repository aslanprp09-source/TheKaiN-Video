package com.thekain.video.storage

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.thekain.video.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

data class VerifiedMedia(val bytes: Long, val sha256: String)

interface OutputVerifier {
    suspend fun verify(file: File, mime: String, expected: Long?): VerifiedMedia
}

class MediaVerifier(private val context: Context) : OutputVerifier {
    override suspend fun verify(file: File, mime: String, expected: Long?): VerifiedMedia {
        if (!file.isFile || file.length() <= 0 || expected != null && expected != file.length())
            throw TransferFailure(TransferError.VERIFY_FAILED)
        file.inputStream().use { input ->
            val header = ByteArray(12)
            val count = input.read(header)
            val valid = when (mime) {
                "video/mp4", "video/quicktime", "audio/mp4" -> count >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
                "video/webm" -> count >= 4 && header.take(4).map { it.toInt() and 255 } == listOf(0x1a, 0x45, 0xdf, 0xa3)
                "audio/mpeg" -> count >= 3 && (String(header, 0, 3, Charsets.US_ASCII) == "ID3" ||
                    header[0].toInt() and 255 == 255 && header[1].toInt() and 0xe0 == 0xe0)
                else -> false
            }
            if (!valid) throw TransferFailure(TransferError.VERIFY_FAILED)
        }
        scan(mime, { it.setDataSource(file.absolutePath) }, { it.setDataSource(file.absolutePath) })
        return file.inputStream().use { digest(it) }
    }

    suspend fun verifyUri(uri: Uri, mime: String, expected: VerifiedMedia) {
        val actual = context.contentResolver.openInputStream(uri)?.use { digest(it) }
            ?: throw TransferFailure(TransferError.VERIFY_FAILED)
        if (actual != expected) throw TransferFailure(TransferError.VERIFY_FAILED)
        scan(mime, { extractor ->
            context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                if (descriptor == null) throw TransferFailure(TransferError.VERIFY_FAILED)
                extractor.setDataSource(descriptor.fileDescriptor)
            }
        }, { it.setDataSource(context, uri) })
    }

    private suspend fun scan(mime: String, source: (MediaExtractor) -> Unit,
                             retrieverSource: (MediaMetadataRetriever) -> Unit) {
        val extractor = MediaExtractor()
        try {
            source(extractor)
            val tracks = (0 until extractor.trackCount).filter { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty().let {
                    it.startsWith("audio/") || it.startsWith("video/")
                }
            }
            val expected = if (mime.startsWith("video/")) "video/" else "audio/"
            if (tracks.none { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(expected) })
                throw TransferFailure(TransferError.VERIFY_FAILED)
            tracks.forEach { index ->
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/")) {
                    if (format.getInteger(MediaFormat.KEY_WIDTH) > 4096 || format.getInteger(MediaFormat.KEY_HEIGHT) > 4096)
                        throw TransferFailure(TransferError.VERIFY_FAILED)
                }
            }
            tracks.forEach(extractor::selectTrack)
            val samples = mutableSetOf<Int>()
            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)
                    throw TransferFailure(TransferError.DRM_PROTECTED)
                buffer.clear()
                if (extractor.readSampleData(buffer, 0) <= 0) throw TransferFailure(TransferError.VERIFY_FAILED)
                samples += track
                if (!extractor.advance()) break
            }
            if (!samples.containsAll(tracks)) throw TransferFailure(TransferError.VERIFY_FAILED)
            if (mime.startsWith("video/")) {
                val retriever = MediaMetadataRetriever()
                try {
                    retrieverSource(retriever)
                    val frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: throw TransferFailure(TransferError.VERIFY_FAILED)
                    frame.recycle()
                } finally { retriever.release() }
            }
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: TransferFailure) {
            throw error
        } catch (_: Exception) {
            throw TransferFailure(TransferError.VERIFY_FAILED)
        } finally { extractor.release() }
    }

    companion object {
        suspend fun digest(input: InputStream): VerifiedMedia {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                bytes += count
            }
            return VerifiedMedia(bytes, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
