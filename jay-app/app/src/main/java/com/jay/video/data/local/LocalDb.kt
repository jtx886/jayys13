package com.jay.video.data.local

import android.content.Context
import androidx.room.ColumnInfo
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

/* ---------- 实体 ---------- */

@Entity(tableName = "favorites")
data class Favorite(
    @PrimaryKey(autoGenerate = true) val fid: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: Int,
    @ColumnInfo(name = "media_type") val mediaType: String,
    val title: String,
    val poster: String,
    val score: Double,
    val year: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "history", primaryKeys = ["media_id", "media_type"])
data class History(
    @ColumnInfo(name = "media_id") val mediaId: Int,
    @ColumnInfo(name = "media_type") val mediaType: String,
    val title: String,
    val poster: String,
    val season: Int = 1,
    val episode: Int = 1,
    @ColumnInfo(name = "episode_label") val episodeLabel: String = "",
    @ColumnInfo(name = "position_ms") val positionMs: Long = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis(),
)

/* ---------- DAO ---------- */

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY fid DESC")
    fun all(): Flow<List<Favorite>>

    @Query("SELECT COUNT(*) FROM favorites WHERE media_id = :id AND media_type = :type")
    suspend fun count(id: Int, type: String): Int

    @Insert
    suspend fun insert(f: Favorite)

    @Query("DELETE FROM favorites WHERE media_id = :id AND media_type = :type")
    suspend fun delete(id: Int, type: String)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY updated_at DESC")
    fun all(): Flow<List<History>>

    @Query("SELECT * FROM history WHERE media_id = :id AND media_type = :type LIMIT 1")
    suspend fun get(id: Int, type: String): History?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: History)

    @Query("DELETE FROM history WHERE media_id = :id AND media_type = :type")
    suspend fun delete(id: Int, type: String)

    @Query("DELETE FROM history")
    suspend fun clear()
}

/* ---------- 数据库 ---------- */

@Database(entities = [Favorite::class, History::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun favoriteDao(): FavoriteDao
    abstract fun historyDao(): HistoryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "jay_video.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
