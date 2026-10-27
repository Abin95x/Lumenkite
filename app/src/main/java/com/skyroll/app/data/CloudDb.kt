package com.skyroll.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A file stored as a message in the Telegram backup channel. */
@Entity(tableName = "cloud_items", indices = [Index("key")])
data class CloudItem(
    @PrimaryKey val messageId: Long,
    val chatId: Long,
    val key: String,
    val name: String,
    val mime: String,
    val size: Long,
    val dateTaken: Long,
    val relativePath: String?,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    /** TDLib file ids. Valid for as long as this TDLib session's database exists. */
    val fileId: Int,
    val thumbFileId: Int,
)

@Dao
interface CloudDao {
    @Query("SELECT * FROM cloud_items ORDER BY dateTaken DESC")
    fun observeAll(): Flow<List<CloudItem>>

    @Query("SELECT `key` FROM cloud_items")
    suspend fun keys(): List<String>

    @Query("SELECT messageId FROM cloud_items")
    suspend fun messageIds(): List<Long>

    @Query("SELECT * FROM cloud_items WHERE messageId IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<CloudItem>

    @Upsert
    suspend fun upsert(items: List<CloudItem>)

    @Query("DELETE FROM cloud_items WHERE messageId IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM cloud_items")
    suspend fun clear()
}

@Database(entities = [CloudItem::class], version = 1, exportSchema = false)
abstract class CloudDb : RoomDatabase() {
    abstract fun dao(): CloudDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, CloudDb::class.java, "cloud.db").build()
    }
}
