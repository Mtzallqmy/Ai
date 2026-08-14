package com.mtzallqmy.aiagent.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tool_executions", indices = [Index("runId"), Index("toolId")])
data class ToolExecutionEntity(
    @PrimaryKey val executionId: String,
    val runId: String,
    val toolId: String,
    val startedAt: Long,
    val completedAt: Long?,
    val status: String,
    val errorCategory: String?,
    val durationMs: Long?,
    val artifactIdsJson: String,
)

@Entity(tableName = "workflow_runs", indices = [Index("workflowId"), Index("status")])
data class WorkflowRunEntity(
    @PrimaryKey val runId: String,
    val workflowId: String,
    val workflowVersion: Int,
    val startedAt: Long,
    val completedAt: Long?,
    val status: String,
    val checkpointJson: String?,
    val error: String?,
)

@Entity(tableName = "schedules", indices = [Index("workflowId"), Index("enabled")])
data class ScheduleEntity(
    @PrimaryKey val scheduleId: String,
    val workflowId: String,
    val workflowVersion: Int,
    val triggerType: String,
    val triggerJson: String,
    val enabled: Boolean,
    val nextRunAt: Long?,
    val lastRunAt: Long?,
    val lastRunStatus: String?,
)

@Entity(tableName = "approval_history", indices = [Index("runId"), Index("toolId"), Index("decidedAt")])
data class ApprovalHistoryEntity(
    @PrimaryKey val approvalId: String,
    val runId: String,
    val toolId: String,
    val action: String,
    val target: String,
    val risk: String,
    val decision: String,
    val agentScope: String,
    val decidedAt: Long,
)

@Entity(tableName = "artifacts", indices = [Index("runId"), Index("createdAt")])
data class ArtifactEntity(
    @PrimaryKey val artifactId: String,
    val runId: String?,
    val type: String,
    val displayName: String,
    val uri: String,
    val sha256: String?,
    val sizeBytes: Long?,
    val createdAt: Long,
)

@Dao
interface ToolExecutionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: ToolExecutionEntity)

    @Query("SELECT * FROM tool_executions WHERE runId = :runId ORDER BY startedAt ASC")
    suspend fun forRun(runId: String): List<ToolExecutionEntity>
}

@Dao
interface WorkflowRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: WorkflowRunEntity)

    @Query("SELECT * FROM workflow_runs WHERE workflowId = :workflowId ORDER BY startedAt DESC")
    suspend fun forWorkflow(workflowId: String): List<WorkflowRunEntity>
}

@Dao
interface ScheduleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: ScheduleEntity)

    @Query("SELECT * FROM schedules ORDER BY nextRunAt ASC")
    fun list(): Flow<List<ScheduleEntity>>

    @Query("DELETE FROM schedules WHERE scheduleId = :scheduleId")
    suspend fun delete(scheduleId: String)
}

@Dao
interface ApprovalHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: ApprovalHistoryEntity)

    @Query("SELECT * FROM approval_history WHERE runId = :runId ORDER BY decidedAt ASC")
    suspend fun forRun(runId: String): List<ApprovalHistoryEntity>
}

@Dao
interface ArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(value: ArtifactEntity)

    @Query("SELECT * FROM artifacts WHERE runId = :runId ORDER BY createdAt ASC")
    suspend fun forRun(runId: String): List<ArtifactEntity>
}
