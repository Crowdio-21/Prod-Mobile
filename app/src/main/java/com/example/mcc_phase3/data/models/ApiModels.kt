package com.example.mcc_phase3.data.models

data class WorkerStats(
    val tasksCompleted: Int,
    val tasksFailed: Int,
    val totalExecutionTime: Double,
    val startedAt: String
)

data class Worker(
    val id: String,
    val status: String,
    val lastSeen: String,
    val currentTaskId: String?,
    val totalTasksCompleted: Int,
    val totalTasksFailed: Int
)

data class Job(
    val id: String,
    val status: String,
    val totalTasks: Int,
    val completedTasks: Int,
    val createdAt: String
)

data class Task(
    val id: String,
    val jobId: String,
    val status: String,
    val arguments: String?,
    val result: String?,
    val error: String?,
    val workerId: String?,
    val createdAt: String
)

data class Stats(
    val totalJobs: Int,
    val totalTasks: Int,
    val totalWorkers: Int,
    val activeJobs: Int,
    val completedJobs: Int
)

data class Activity(
    val timestamp: String,
    val type: String,
    val action: String,
    val details: String
)

data class WebsocketStats(
    val connectedWorkers: Int,
    val availableWorkers: Int,
    val connectedClients: Int,
    val activeJobs: Int
)
