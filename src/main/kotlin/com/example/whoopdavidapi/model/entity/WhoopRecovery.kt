package com.example.whoopdavidapi.model.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "whoop_recoveries")
class WhoopRecovery(
    @Id
    @Column(name = "cycle_id")
    var cycleId: Long = 0,

    @Column(name = "sleep_id")
    var sleepId: String? = null,

    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @Column(name = "score_state", nullable = false)
    var scoreState: String = "PENDING_SCORE",

    // Score fields (flattened)
    @Column(name = "user_calibrating")
    var userCalibrating: Boolean? = null,

    @Column(name = "recovery_score")
    var recoveryScore: Float? = null,

    @Column(name = "resting_heart_rate")
    var restingHeartRate: Float? = null,

    @Column(name = "hrv_rmssd_milli")
    var hrvRmssdMilli: Float? = null,

    @Column(name = "spo2_percentage")
    var spo2Percentage: Float? = null,

    @Column(name = "skin_temp_celsius")
    var skinTempCelsius: Float? = null
)
