package com.example.mcc_phase3.data.api

import com.example.mcc_phase3.data.models.*
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @GET("/api/stats")
    suspend fun getStats(): Stats

    @GET("/api/jobs")
    suspend fun getJobs(
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 100
    ): List<Job>

    @GET("/api/jobs/{jobId}")
    suspend fun getJob(@Path("jobId") jobId: String): Job

    @GET("/api/workers")
    suspend fun getWorkers(): List<Worker>

    @GET("/api/websocket-stats")
    suspend fun getWebsocketStats(): WebsocketStats

    // Use existing jobs endpoint to get recent activity
    @GET("/api/jobs")
    suspend fun getRecentJobs(
        @Query("skip") skip: Int = 0,
        @Query("limit") limit: Int = 10
    ): List<Job>}