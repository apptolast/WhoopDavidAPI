package com.example.whoopdavidapi.model.dto

import java.time.Instant

data class RecoveryDTO(
    val cycleId: Long,
    val sleepId: String?,
    val userId: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val scoreState: String,
    val userCalibrating: Boolean?,
    val recoveryScore: Float?,
    val restingHeartRate: Float?,
    val hrvRmssdMilli: Float?,
    val spo2Percentage: Float?,
    val skinTempCelsius: Float?
)
