package com.example.whoopdavidapi.model.dto

import java.time.Instant

data class WorkoutDTO(
    val id: String,
    val userId: Long,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val start: Instant,
    val end: Instant?,
    val timezoneOffset: String?,
    val sportName: String?,
    val sportId: Int?,
    val scoreState: String,
    val strain: Float?,
    val averageHeartRate: Int?,
    val maxHeartRate: Int?,
    val kilojoule: Float?,
    val percentRecorded: Float?,
    val distanceMeter: Float?,
    val altitudeGainMeter: Float?,
    val altitudeChangeMeter: Float?,
    val zoneZeroMilli: Long?,
    val zoneOneMilli: Long?,
    val zoneTwoMilli: Long?,
    val zoneThreeMilli: Long?,
    val zoneFourMilli: Long?,
    val zoneFiveMilli: Long?
)
