package com.thekain.video.storage

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

interface OutputPublisher {
    suspend fun reserve(task: DownloadTask): String
    suspend fun publish(task: DownloadTask, file: File, uri: String, media: VerifiedMedia)
    suspend fun discardPending(task: DownloadTask)
}

class MediaPublisher(private val context: Context, private val verifier: MediaVerifier,
                     private val api: Int = Build.VERSION.SDK_INT) : OutputPublisher {
    private val resolver get() = context.contentResolver
    private val relative = Environment.DIRECTORY_DOWNLOADS + "/TheKaiN Video/"

    override suspend fun reserve(task: DownloadTask): String {
        task.outputUri?.let { return it }
        val name = DirectLinks.publishedName(task.name, task.id)
        if (api >= 29 && Build.VERSION.SDK_INT >= 29) {
            // Recover an orphan reservation from a crash before its URI was saved.
            resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?",
                arrayOf(name, relative, context.packageName), null)?.use { cursor ->
                if (cursor.moveToFirst()) return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0).toString()).toString()
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, task.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.toString()
                ?: throw TransferFailure(TransferError.PERMISSION_DENIED)
        }
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw TransferFailure(TransferError.PERMISSION_DENIED)
        directory.mkdirs()
        val file = File(directory, name)
        return FileProvider.getUriForFile(context, context.packageName + ".files", file).toString()
    }

    override suspend fun publish(task: DownloadTask, file: File, uri: String, media: VerifiedMedia) {
        val target = Uri.parse(uri)
        var correct = false
        try { verifier.verifyUri(target, task.mime, media); correct = true } catch (_: TransferFailure) { }
        catch (_: java.io.FileNotFoundException) { }
        if (!correct) {
            if (api < 29 || Build.VERSION.SDK_INT < 29) {
                val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw TransferFailure(TransferError.PERMISSION_DENIED)
                val targetFile = File(directory, DirectLinks.publishedName(task.name, task.id))
                if (targetFile.exists()) throw TransferFailure(TransferError.VERIFY_FAILED)
                val temporary = File(directory, ".${task.id}.publishing")
                FileOutputStream(temporary).use { output ->
                    file.inputStream().use { input ->
                        val block = ByteArray(65536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(block)
                            if (count < 0) break
                            output.write(block, 0, count)
                        }
                    }
                    output.fd.sync()
                }
                if (verifier.verify(temporary, task.mime, media.bytes) != media) throw TransferFailure(TransferError.VERIFY_FAILED)
                Files.move(temporary.toPath(), targetFile.toPath()) // No REPLACE_EXISTING; one owner per UUID.
                verifier.verifyUri(target, task.mime, media)
                return
            }
            if (api >= 29 && Build.VERSION.SDK_INT >= 29 && !pending(target)) throw TransferFailure(TransferError.VERIFY_FAILED)
            val descriptor = resolver.openFileDescriptor(target, "rwt")
                ?: throw TransferFailure(TransferError.PERMISSION_DENIED)
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
                    file.inputStream().use { input ->
                        val bytes = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(bytes)
                            if (count < 0) break
                            output.write(bytes, 0, count)
                        }
                    }
                    output.fd.sync()
            }
            verifier.verifyUri(target, task.mime, media)
        }
        if (api >= 29 && Build.VERSION.SDK_INT >= 29) {
            if (resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1 || pending(target))
                throw TransferFailure(TransferError.VERIFY_FAILED)
        }
    }

    private fun pending(uri: Uri): Boolean = resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)?.use {
        it.moveToFirst() && it.getInt(0) == 1
    } ?: false

    override suspend fun discardPending(task: DownloadTask) {
        if (api >= 29 && Build.VERSION.SDK_INT >= 29) task.outputUri?.let { value ->
            val uri = Uri.parse(value)
            if (pending(uri)) resolver.delete(uri, null, null)
        }
    }
}
