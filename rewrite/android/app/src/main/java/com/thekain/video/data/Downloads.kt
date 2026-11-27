package com.thekain.video.data

import androidx.room.*
import com.thekain.video.domain.*
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Entity(tableName = "downloads")
data class DownloadTask(
    @PrimaryKey val id: String,
    val url: String,
    val name: String,
    val mime: String,
    val state: TransferState = TransferState.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val bytes: Long = 0,
    val total: Long? = null,
    val etag: String? = null,
    val resumable: Boolean = false,
    val attempt: Int = 1,
    val failures: Int = 0,
    val eligibleAt: Long = 0,
    val error: TransferError? = null,
    val outputUri: String? = null,
    val verifiedBytes: Long? = null,
    val digest: String? = null,
    @ColumnInfo(defaultValue = "'DIRECT'") val backend: TransferBackend = TransferBackend.DIRECT
) {
    override fun toString() = "DownloadTask(id=$id, state=$state, bytes=$bytes)"
    val percent: Int? get() = TransferRules.percent(state, bytes, total)
}

class Converters {
    @TypeConverter fun backend(value: String): TransferBackend = TransferBackend.valueOf(value)
    @TypeConverter fun backend(value: TransferBackend): String = value.name
    @TypeConverter fun state(value: String): TransferState = TransferState.valueOf(value)
    @TypeConverter fun state(value: TransferState): String = value.name
    @TypeConverter fun error(value: String?): TransferError? = value?.let(TransferError::valueOf)
    @TypeConverter fun error(value: TransferError?): String? = value?.name
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC") fun observe(): Flow<List<DownloadTask>>
    @Query("SELECT * FROM downloads ORDER BY createdAt") suspend fun all(): List<DownloadTask>
    @Query("SELECT * FROM downloads WHERE id=:id") suspend fun get(id: String): DownloadTask?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(task: DownloadTask)
    @Update suspend fun update(task: DownloadTask)
    @Query("DELETE FROM downloads WHERE id=:id AND state='COMPLETED'") suspend fun removeCompleted(id: String): Int
}

@Database(entities = [DownloadTask::class], version = 2, exportSchema = true)
@TypeConverters(Converters::class)
abstract class DownloadsDb : RoomDatabase() {
    abstract fun downloads(): DownloadDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN backend TEXT NOT NULL DEFAULT 'DIRECT'")
            }
        }
    }
}

class DownloadRepository(val db: DownloadsDb) {
    val changes = db.downloads().observe()
    suspend fun all() = db.downloads().all()
    suspend fun find(id: String): DownloadTask? = db.downloads().get(id)
    suspend fun get(id: String): DownloadTask = find(id) ?: error("Unknown job")
    /** Only immutable completed records may be removed. Active workers keep their rows. */
    suspend fun removeCompleted(id: String): Boolean = db.downloads().removeCompleted(id) == 1

    suspend fun create(request: DirectRequest): DownloadTask {
        val task = DownloadTask(UUID.randomUUID().toString(), request.url, request.name, request.mime, backend = request.backend)
        db.downloads().insert(task)
        return task
    }

    suspend fun change(id: String, transform: (DownloadTask) -> DownloadTask): DownloadTask = db.withTransaction {
        val old = get(id)
        require(old.state !in setOf(TransferState.COMPLETED, TransferState.CANCELED)) { "Terminal job" }
        val next = transform(old).copy(updatedAt = System.currentTimeMillis())
        require(TransferRules.allowed(old.state, next.state)) { "Invalid job transition" }
        require(next.state != TransferState.COMPLETED ||
            (next.outputUri != null && (next.verifiedBytes ?: 0) > 0 && !next.digest.isNullOrEmpty())) { "Missing verification evidence" }
        db.downloads().update(next)
        next
    }

    suspend fun requestCancel(id: String): Boolean = db.withTransaction {
        val task = find(id) ?: return@withTransaction false
        if (task.state in setOf(TransferState.COMPLETED, TransferState.CANCELED, TransferState.FAILED, TransferState.PUBLISHING)) return@withTransaction false
        change(id) { it.copy(state = if (it.state in setOf(TransferState.QUEUED, TransferState.PAUSED, TransferState.RETRY_WAIT)) TransferState.CANCELED else TransferState.CANCEL_REQUESTED) }
        true
    }

    suspend fun requestPause(id: String): Boolean = db.withTransaction {
        val task = find(id) ?: return@withTransaction false
        if (task.state != TransferState.DOWNLOADING || !task.resumable || task.bytes <= 0) return@withTransaction false
        change(id) { it.copy(state = TransferState.PAUSING) }
        true
    }

    suspend fun resume(id: String) = change(id) {
        require(it.state == TransferState.PAUSED && it.resumable && it.etag != null)
        it.copy(state = TransferState.QUEUED, error = null)
    }

    suspend fun retry(id: String) = change(id) {
        require(it.state == TransferState.FAILED)
        // A saved publication intent is recovered instead of creating another output.
        if (it.outputUri != null && it.digest != null) it.copy(state = TransferState.QUEUED, error = null)
        else it.copy(state = TransferState.QUEUED, attempt = it.attempt + 1, bytes = 0, total = null,
            resumable = false, etag = null, failures = 0, eligibleAt = 0, error = null)
    }
}
