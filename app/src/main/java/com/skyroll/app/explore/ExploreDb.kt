package com.skyroll.app.explore

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One scanned device photo/video. [version] lets us rescan when the scanner improves. */
@Entity(tableName = "scanned")
data class Scanned(
    @PrimaryKey val mediaId: Long,
    val version: Int,
    /** Text read from the image (lowercase), for search. */
    val text: String?,
    val lat: Double?,
    val lng: Double?,
    /** "City, State" — null until looked up (needs internet once). */
    val place: String?,
)

@Entity(tableName = "labels", primaryKeys = ["mediaId", "label"], indices = [Index("label")])
data class Label(val mediaId: Long, val label: String, val confidence: Float)

@Entity(tableName = "faces", indices = [Index("mediaId"), Index("personId")])
data class Face(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val personId: Long,
    /** 128 floats, L2-normalised. */
    val embedding: ByteArray,
    /** Face box, as fractions of the image (0..1). */
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Same as [Face] without the embedding — cheap to load for the UI. */
data class FaceLite(
    val id: Long,
    val mediaId: Long,
    val personId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

@Entity(tableName = "persons")
data class Person(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?,
    /** Running mean of the member embeddings (normalised). */
    val centroid: ByteArray,
    @ColumnInfo(defaultValue = "0") val faceCount: Int,
)

@Dao
interface ExploreDao {
    @Query("SELECT mediaId FROM scanned WHERE version >= :version")
    suspend fun scannedIds(version: Int): List<Long>

    @Query("SELECT * FROM scanned")
    fun observeScanned(): Flow<List<Scanned>>

    @Query("SELECT * FROM scanned WHERE lat IS NOT NULL AND place IS NULL")
    suspend fun missingPlaces(): List<Scanned>

    @Upsert suspend fun upsertScanned(s: Scanned)

    @Query("UPDATE scanned SET place = :place WHERE mediaId = :mediaId")
    suspend fun setPlace(mediaId: Long, place: String)

    @Query("DELETE FROM labels WHERE mediaId = :mediaId") suspend fun clearLabels(mediaId: Long)
    @Insert suspend fun insertLabels(labels: List<Label>)

    @Query("SELECT * FROM labels")
    fun observeLabels(): Flow<List<Label>>

    @Query("SELECT * FROM faces WHERE mediaId = :mediaId") suspend fun facesOf(mediaId: Long): List<Face>
    @Query("DELETE FROM faces WHERE mediaId = :mediaId") suspend fun clearFaces(mediaId: Long)
    @Insert suspend fun insertFace(face: Face): Long

    @Query("SELECT id, mediaId, personId, `left`, top, `right`, bottom FROM faces")
    fun observeFaces(): Flow<List<FaceLite>>

    @Query("SELECT * FROM persons") suspend fun persons(): List<Person>
    @Query("SELECT * FROM persons") fun observePersons(): Flow<List<Person>>
    @Insert suspend fun insertPerson(p: Person): Long
    @Upsert suspend fun upsertPerson(p: Person)

    @Query("UPDATE persons SET name = :name WHERE id IN (:ids)")
    suspend fun rename(ids: List<Long>, name: String?)

    /** Forget media that no longer exists on the device. */
    @Query("DELETE FROM scanned WHERE mediaId NOT IN (:keep)") suspend fun pruneScanned(keep: List<Long>)
    @Query("DELETE FROM labels WHERE mediaId NOT IN (:keep)") suspend fun pruneLabels(keep: List<Long>)
    @Query("DELETE FROM faces WHERE mediaId NOT IN (:keep)") suspend fun pruneFaces(keep: List<Long>)
}

@Database(entities = [Scanned::class, Label::class, Face::class, Person::class], version = 1, exportSchema = false)
abstract class ExploreDb : RoomDatabase() {
    abstract fun dao(): ExploreDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, ExploreDb::class.java, "explore.db")
                // Everything here can be rebuilt by rescanning, so a schema change may simply wipe it.
                .fallbackToDestructiveMigration()
                .build()
    }
}
