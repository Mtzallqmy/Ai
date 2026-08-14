package com.mtzallqmy.aiagent.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ToolExecutionEntity::class,
        WorkflowRunEntity::class,
        ScheduleEntity::class,
        ApprovalHistoryEntity::class,
        ArtifactEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class DurableExecutionDatabase : RoomDatabase() {
    abstract fun toolExecutionDao(): ToolExecutionDao
    abstract fun workflowRunDao(): WorkflowRunDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun approvalHistoryDao(): ApprovalHistoryDao
    abstract fun artifactDao(): ArtifactDao
}

object DurableExecutionDatabaseProvider {
    @Volatile
    private var instance: DurableExecutionDatabase? = null

    fun get(context: Context): DurableExecutionDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            DurableExecutionDatabase::class.java,
            "aegis_execution.db",
        ).build().also { instance = it }
    }
}
