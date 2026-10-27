package io.github.jackwener.dsh.core.data

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
import androidx.room.Upsert
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import javax.inject.Singleton

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val ruleId: Long? = null,
    val running: Boolean = false,
)

/** Raw dsh `session.event` payloads, replayed to rebuild a conversation. */
@Entity(tableName = "events", primaryKeys = ["sessionId", "seq"])
data class EventEntity(val sessionId: String, val seq: Long, val type: String, val json: String)

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val specJson: String,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val lastFiredAt: Long? = null,
)

@Entity(tableName = "rule_runs", indices = [Index("ruleId")])
data class RuleRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val firedAt: Long,
    val sessionId: String?,
    val outcome: String,
)

@Entity(tableName = "usage")
data class UsageEntity(@PrimaryKey val day: String, val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE ruleId IS NULL ORDER BY updatedAt DESC")
    fun observeAttended(): Flow<List<SessionEntity>>
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?
    @Query("SELECT * FROM sessions WHERE running = 1")
    suspend fun running(): List<SessionEntity>
    @Upsert suspend fun upsert(s: SessionEntity)
    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun setTitle(id: String, title: String)
    @Query("UPDATE sessions SET running = :running, updatedAt = :now WHERE id = :id")
    suspend fun setRunning(id: String, running: Boolean, now: Long = System.currentTimeMillis())
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(e: EventEntity)
    @Query("SELECT * FROM events WHERE sessionId = :id ORDER BY seq")
    suspend fun events(id: String): List<EventEntity>
    @Query("DELETE FROM events WHERE sessionId = :id")
    suspend fun deleteEvents(id: String)
}

@Dao
interface RuleDao {
    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun observe(): Flow<List<RuleEntity>>
    @Query("SELECT * FROM rules")
    suspend fun all(): List<RuleEntity>
    @Query("SELECT * FROM rules WHERE id = :id")
    suspend fun get(id: Long): RuleEntity?
    @Insert suspend fun insert(r: RuleEntity): Long
    @Upsert suspend fun upsert(r: RuleEntity)
    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun delete(id: Long)
    @Insert suspend fun insertRun(r: RuleRunEntity)
    @Query("SELECT * FROM rule_runs WHERE ruleId = :ruleId ORDER BY firedAt DESC LIMIT 20")
    fun runs(ruleId: Long): Flow<List<RuleRunEntity>>
    @Query("SELECT COUNT(*) FROM rule_runs WHERE ruleId = :ruleId AND firedAt > :since")
    suspend fun runsSince(ruleId: Long, since: Long): Int
}

@Dao
interface UsageDao {
    @Query("SELECT * FROM usage ORDER BY day DESC LIMIT 30")
    fun observe(): Flow<List<UsageEntity>>
    @Query("SELECT * FROM usage WHERE day = :day")
    suspend fun get(day: String): UsageEntity?
    @Upsert suspend fun upsert(u: UsageEntity)
}

@Database(entities = [SessionEntity::class, EventEntity::class, RuleEntity::class, RuleRunEntity::class, UsageEntity::class], version = 1)
abstract class DshDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun rules(): RuleDao
    abstract fun usage(): UsageDao
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton
    fun db(@ApplicationContext c: Context): DshDatabase = Room.databaseBuilder(c, DshDatabase::class.java, "dsh.db").build()
    @Provides fun sessions(db: DshDatabase) = db.sessions()
    @Provides fun rules(db: DshDatabase) = db.rules()
    @Provides fun usage(db: DshDatabase) = db.usage()
}
