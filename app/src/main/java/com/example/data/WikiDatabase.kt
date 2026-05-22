package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "wiki_pages")
data class WikiPage(
    @PrimaryKey val title: String,
    val content: String, // markdown content supporting [[Other Page]] syntax
    val tags: String,    // comma-separated tags
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "raw_sources")
data class RawSource(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val audioPath: String? = null,
    // Comma-separated list of local file paths. Multiple images can be attached to a single note.
    // Kept as a single string column so we don't need a join table; helpers below split/join.
    val imagePaths: String? = null
) {
    fun imageList(): List<String> = imagePaths?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
    companion object {
        fun joinPaths(paths: List<String>): String? =
            paths.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
}

@Entity(tableName = "activity_logs")
data class ActivityLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String, // e.g. "ingest", "query", "lint"
    val summary: String,
    val detail: String
)

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val noteId: Int? = null,
    val title: String,
    val category: String, // "Reminder", "Task", "Event"
    val dateText: String, // "Tomorrow", etc.
    val timestamp: Long = System.currentTimeMillis(),
    val description: String,
    val isTriggered: Boolean = false,
    val isCompleted: Boolean = false,
    val attachedImagePath: String? = null
)

@Dao
interface WikiDao {
    @Query("SELECT * FROM wiki_pages ORDER BY title ASC")
    fun getAllPagesFlow(): Flow<List<WikiPage>>

    @Query("SELECT * FROM wiki_pages ORDER BY title ASC")
    suspend fun getAllPages(): List<WikiPage>

    @Query("SELECT * FROM wiki_pages WHERE title = :title LIMIT 1")
    suspend fun getPageByTitle(title: String): WikiPage?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: WikiPage)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<WikiPage>)

    @Query("DELETE FROM wiki_pages WHERE title = :title")
    suspend fun deletePageByTitle(title: String)

    @Query("DELETE FROM wiki_pages")
    suspend fun clearAllPages()

    // Raw sources queries
    @Query("SELECT * FROM raw_sources ORDER BY timestamp DESC")
    fun getAllSourcesFlow(): Flow<List<RawSource>>

    @Query("SELECT * FROM raw_sources ORDER BY timestamp DESC")
    suspend fun getAllSources(): List<RawSource>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSource(source: RawSource): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSources(sources: List<RawSource>)

    @Query("DELETE FROM raw_sources WHERE id = :id")
    suspend fun deleteSourceById(id: Int)

    @Query("DELETE FROM raw_sources")
    suspend fun clearAllSources()

    // Log queries
    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    fun getAllLogsFlow(): Flow<List<ActivityLog>>

    @Query("SELECT * FROM activity_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<ActivityLog>

    @Query("DELETE FROM activity_logs")
    suspend fun clearAllLogs()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: ActivityLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLogs(logs: List<ActivityLog>)

    // Reminders queries
    @Query("SELECT * FROM reminders ORDER BY timestamp DESC")
    fun getAllRemindersFlow(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders WHERE isCompleted = 0 ORDER BY timestamp DESC")
    fun getActiveRemindersFlow(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders ORDER BY timestamp DESC")
    suspend fun getAllReminders(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE noteId = :noteId")
    suspend fun getRemindersForNote(noteId: Int): List<Reminder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: Reminder): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminders(reminders: List<Reminder>)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminderById(id: Int)

    @Query("UPDATE reminders SET isCompleted = :completed WHERE id = :id")
    suspend fun updateReminderStatus(id: Int, completed: Boolean)

    @Query("DELETE FROM reminders")
    suspend fun clearAllReminders()
}

@Database(
    entities = [WikiPage::class, RawSource::class, ActivityLog::class, Reminder::class],
    version = 5,
    exportSchema = false
)
abstract class WikiDatabase : RoomDatabase() {
    abstract val wikiDao: WikiDao

    companion object {
        @Volatile
        private var instance: WikiDatabase? = null

        fun getDatabase(context: Context): WikiDatabase {
            return instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    WikiDatabase::class.java,
                    "wiki_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
            }
        }
    }
}
