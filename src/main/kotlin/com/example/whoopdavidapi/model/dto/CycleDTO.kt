package com.example.whoopdavidapi.model.dto

import java.time.Instant

data class CycleDTO(
    val id: Long,
    val userId: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val start: Instant,
    val end: Instant?,
    val timezoneOffset: String?,
    val scoreState: String,
    val strain: Float?,
    val kilojoule: Float?,
    val averageHeartRate: Int?,
    val maxHeartRate: Int?
)
