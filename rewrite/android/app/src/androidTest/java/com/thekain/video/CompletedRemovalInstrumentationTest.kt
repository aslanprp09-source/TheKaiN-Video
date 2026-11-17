package com.thekain.video

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.thekain.video.data.*
import com.thekain.video.domain.*
import com.thekain.video.storage.*
import com.thekain.video.transfer.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** Portable owned-fixture storage tests, selected on actual API 26/29 as well as normal API 35 CI. */
@RunWith(AndroidJUnit4::class)
class CompletedRemovalInstrumentationTest {
    private lateinit var context: Context
    private lateinit var db: DownloadsDb
    private lateinit var repo: DownloadRepository
    private lateinit var root: File
    private lateinit var body: ByteArray
    private lateinit var publisher: MediaPublisher
    private lateinit var verifier: MediaVerifier
    private val outputs = mutableListOf<Uri>()
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, DownloadsDb::class.java).build()
        repo = DownloadRepository(db)
        root = File(context.cacheDir, "removal-" + UUID.randomUUID()).apply { mkdirs() }
        body = InstrumentationRegistry.getInstrumentation().context.assets.open("owned-sample.mp4").use { it.readBytes() }
        verifier = MediaVerifier(context)
        publisher = MediaPublisher(context, verifier)
    }
    @After fun cleanup() {
        outputs.forEach { context.contentResolver.delete(it, null, null) }
        db.close()
        root.deleteRecursively()
    }
    private suspend fun completed(): DownloadTask {
        val task = repo.create(DirectLinks.parse("https://owned.invalid/fixture.mp4"))
        val transfer = object : TransferAdapter {
            override suspend fun fetch(task: DownloadTask, stage: File,
                identity: suspend (Long?, String?, Boolean) -> Unit,
                progress: suspend (Long, Long?) -> Unit): File {
                identity(body.size.toLong(), null, false)
                stage.parentFile!!.mkdirs(); stage.writeBytes(body)
                progress(body.size.toLong(), body.size.toLong())
                return stage
            }
        }
        assertEquals(EngineResult.COMPLETED, DownloadEngine(repo, root, transfer, verifier, publisher).run(task.id))
        return repo.get(task.id).also { outputs += Uri.parse(it.outputUri) }
    }
    private fun remover(delete: suspend (DownloadTask) -> Unit = publisher::deleteCompletedOutput) =
        CompletedDownloadRemover(repo::find, repo::removeCompleted, delete, CompletedStaging(root)::delete, {})
    private fun exists(uri: String): Boolean = try {
        requireNotNull(context.contentResolver.openInputStream(Uri.parse(uri))).use { it.read() >= 0 }
    } catch (_: FileNotFoundException) { false }

    @Test fun listOnlyKeepsPublishedBytesAndRemovesRowAndPrivateCopies() = runBlocking<Unit> {
        val done = completed()
        assertEquals(RemovalOutcome.RECORD_REMOVED, remover().remove(done.id, false))
        assertNull(repo.find(done.id))
        assertFalse(File(root, done.id).exists())
        val bytes = context.contentResolver.openInputStream(Uri.parse(done.outputUri))!!.use { it.readBytes() }
        assertArrayEquals(body, bytes)
    }
    @Test fun fileAndRecordRemovesOnlyItsOwnOutputAndPrivateCopies() = runBlocking<Unit> {
        val done = completed()
        val other = completed()
        assertEquals(RemovalOutcome.FILE_AND_RECORD_REMOVED, remover().remove(done.id, true))
        assertFalse(exists(done.outputUri!!))
        assertNull(repo.find(done.id))
        assertFalse(File(root, done.id).exists())
        assertTrue(exists(other.outputUri!!))
        assertNotNull(repo.find(other.id))
        assertTrue(File(root, "${other.id}/1/payload.part").exists())
    }
    @Test fun otherTasksUriIsRejectedAndBothFilesAndRecordRemain() = runBlocking<Unit> {
        val done = completed()
        val other = completed()
        assertEquals(RemovalOutcome.FAILED, remover { publisher.deleteCompletedOutput(it.copy(outputUri = other.outputUri)) }
            .remove(done.id, true))
        assertNotNull(repo.find(done.id))
        assertTrue(exists(done.outputUri!!))
        assertTrue(exists(other.outputUri!!))
        assertTrue(File(root, done.id).exists())
    }
    @Test fun alreadyMissingFileIsIdempotentAndMissingRecordIsReportedSeparately() = runBlocking<Unit> {
        val done = completed()
        publisher.deleteCompletedOutput(done)
        val removal = remover()
        assertEquals(RemovalOutcome.FILE_AND_RECORD_REMOVED, removal.remove(done.id, true))
        assertEquals(RemovalOutcome.ALREADY_REMOVED, removal.remove(done.id, true))
        assertFalse(repo.requestCancel(done.id)) // A delayed notification action must not crash the receiver.
        assertFalse(repo.requestPause(done.id))
    }
    @Test fun activeRecordCannotBeDeletedAndNoBroadStoragePermissionIsAdded() = runBlocking<Unit> {
        val active = repo.create(DirectLinks.parse("https://owned.invalid/active.mp4"))
        assertEquals(RemovalOutcome.NOT_COMPLETED, remover().remove(active.id, true))
        assertNotNull(repo.find(active.id))
        val permissions = context.packageManager.getPackageInfo(context.packageName, 4096).requestedPermissions.orEmpty()
        assertFalse(permissions.any { it in setOf("android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO") })
    }
    @Test fun unsafePendingOutputOrNonRegularLegacyObjectCannotBeDeleted() = runBlocking<Unit> {
        val task = repo.create(DirectLinks.parse("https://owned.invalid/unsafe.mp4"))
        val uri = publisher.reserve(task)
        outputs += Uri.parse(uri)
        if (Build.VERSION.SDK_INT >= 29) {
            try { publisher.deleteCompletedOutput(task.copy(state = TransferState.COMPLETED, outputUri = uri)); fail("Pending output") }
            catch (_: IllegalArgumentException) { }
            context.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
            }
        } else {
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), DirectLinks.publishedName(task.name, task.id))
            assertTrue(directory.mkdir())
            try {
                try { publisher.deleteCompletedOutput(task.copy(state = TransferState.COMPLETED, outputUri = uri)); fail("Directory is not media") }
                catch (_: IllegalArgumentException) { }
                assertTrue(directory.isDirectory)
            } finally { directory.delete() }
        }
    }
    @Test fun mutationBetweenOwnershipCheckAndDeleteCannotDeleteAnUnmatchedOutput() = runBlocking<Unit> {
        val task = completed()
        if (Build.VERSION.SDK_INT >= 29) {
            val appContext = context
            var guarded = false
            val provider = object : ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "video/mp4"
                override fun insert(uri: Uri, values: ContentValues?): Uri? = null
                override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                    val columns = requireNotNull(projection)
                    return MatrixCursor(columns).apply {
                        if (columns.size == 1) addRow(arrayOf(42L)) // Row still exists after ownership mutation.
                        else addRow(arrayOf(DirectLinks.publishedName(task.name, task.id), "Download/TheKaiN Video/", appContext.packageName, 0))
                    }
                }
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
                    guarded = selection?.contains("owner_package_name=?") == true &&
                        selection.contains("is_pending=0") && selectionArgs?.toList() ==
                        listOf(DirectLinks.publishedName(task.name, task.id), "Download/TheKaiN Video/", appContext.packageName)
                    return 0 // Concurrent mutation does not match the bound delete predicate.
                }
            }
            provider.attachInfo(context, android.content.pm.ProviderInfo().apply { authority = "media"; exported = false })
            val wrapped = object : ContextWrapper(context) {
                override fun getContentResolver() = ContentResolver.wrap(provider)
            }
            try { MediaPublisher(wrapped, verifier).deleteCompletedOutput(task.copy(outputUri = "content://media/external/downloads/42")); fail("Mutation must fail") }
            catch (_: IOException) { }
            assertTrue(guarded)
        } else {
            // Actual API 26 FileProvider: no arbitrary file:// path may enter deletion.
            try { publisher.deleteCompletedOutput(task.copy(outputUri = "file:///outside.mp4")); fail("Unsafe legacy URI") }
            catch (_: IllegalArgumentException) { }
        }
        assertTrue(exists(task.outputUri!!))
        assertNotNull(repo.find(task.id))
    }
}
