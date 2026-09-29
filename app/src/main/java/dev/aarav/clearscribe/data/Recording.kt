package dev.aarav.clearscribe.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * One recorded, cleaned, transcribed note.
 *
 * [title] starts as a generic timestamp string (see GenericTitleProvider) and is
 * only ever overwritten automatically while [titleIsUserEdited] is false.
 */
@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val titleIsUserEdited: Boolean = false,
    val transcript: String,
    val summary: String? = null,
    val audioPath: String,
    val startedAt: Instant,
    val durationMs: Long,
    /** Clear's reported true-peak dBFS for the enhanced audio, for debugging/QA. */
    val measuredTruePeakDbfs: Double? = null,
)

class Converters {
    @TypeConverter
    fun fromEpochMilli(value: Long?): Instant? = value?.let { Instant.ofEpochMilli(it) }

    @TypeConverter
    fun toEpochMilli(instant: Instant?): Long? = instant?.toEpochMilli()
}

@Dao
interface RecordingDao {
    @Insert
    suspend fun insert(recording: Recording): Long

    @Query("SELECT * FROM recordings ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<Recording>>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): Recording?

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [Recording::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class ClearScribeDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
}
