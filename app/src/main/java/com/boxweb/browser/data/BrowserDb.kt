package com.boxweb.browser.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** One visited page. History is kept per profile so profiles do not reveal each other's browsing. */
@Entity(tableName = "history", indices = [Index("profileId"), Index("visitedAt")])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: String,
    val url: String,
    val title: String,
    val visitedAt: Long
)

/** A saved page. Bookmarks are shared by all profiles. */
@Entity(tableName = "bookmarks", indices = [Index(value = ["url"], unique = true)])
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long
)

/** A page saved to read later. The reading list is shared by all profiles, like bookmarks. */
@Entity(tableName = "reading_list", indices = [Index(value = ["url"], unique = true)])
data class ReadingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val url: String,
    val createdAt: Long
)

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY visitedAt DESC LIMIT 1")
    suspend fun latest(profileId: String): HistoryEntry?

    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY visitedAt DESC LIMIT :limit")
    suspend fun recent(profileId: String, limit: Int): List<HistoryEntry>

    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Update
    suspend fun update(entry: HistoryEntry)

    @Query("SELECT * FROM history WHERE profileId = :profileId ORDER BY visitedAt DESC")
    fun observe(profileId: String): Flow<List<HistoryEntry>>

    @Query("DELETE FROM history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM history WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)

    @Query("DELETE FROM history")
    suspend fun clearAll()

    /** Keeps only the newest [keep] rows. */
    @Query("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY visitedAt DESC LIMIT -1 OFFSET :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observe(): Flow<List<Bookmark>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(bookmark: Bookmark): Long

    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    suspend fun all(): List<Bookmark>

    @Query("SELECT COUNT(*) FROM bookmarks WHERE url = :url")
    suspend fun count(url: String): Int

    @Query("DELETE FROM bookmarks WHERE url = :url")
    suspend fun deleteByUrl(url: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_list ORDER BY createdAt DESC")
    fun observe(): Flow<List<ReadingItem>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ReadingItem): Long

    @Query("DELETE FROM reading_list WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(
    entities = [HistoryEntry::class, Bookmark::class, ReadingItem::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun history(): HistoryDao
    abstract fun bookmarks(): BookmarkDao
    abstract fun reading(): ReadingDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** Version 2 added the reading list. History and bookmarks are kept as they are. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `reading_list` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`title` TEXT NOT NULL, " +
                        "`url` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_reading_list_url` ON `reading_list` (`url`)"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "browser.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
