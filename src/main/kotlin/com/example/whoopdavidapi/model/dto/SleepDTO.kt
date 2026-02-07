package com.example.whoopdavidapi.model.dto

import java.time.Instant

data class SleepDTO(
    val id: String,
    val cycleId: Long?,
    val userId: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val start: Instant,
    val end: Instant?,
    val timezoneOffset: String?,
    val nap: Boolean,
    val scoreState: String,
    // Stage summary
    val totalInBedTimeMilli: Int?,
    val totalAwakeTimeMilli: Int?,
    val totalNoDataTimeMilli: Int?,
    val totalLightSleepTimeMilli: Int?,
    val totalSlowWaveSleepTimeMilli: Int?,
    val totalRemSleepTimeMilli: Int?,
    val sleepCycleCount: Int?,
    val disturbanceCount: Int?,
    // Sleep needed
    val baselineMilli: Long?,
    val needFromSleepDebtMilli: Long?,
    val needFromRecentStrainMilli: Long?,
    val needFromRecentNapMilli: Long?,
    // Score
    val respiratoryRate: Float?,
    val sleepPerformancePercentage: Float?,
    val sleepConsistencyPercentage: Float?,
    val sleepEfficiencyPercentage: Float?
)
