package com.example.whoopdavidapi.model.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "whoop_workouts")
class WhoopWorkout(
    @Id
    @Column(name = "id")
    var id: String = "",

    @Column(name = "user_id", nullable = false)
    var userId: Long = 0,

    @Column(name = "created_at")
    var createdAt: Instant? = null,

    @Column(name = "updated_at")
    var updatedAt: Instant? = null,

    @Column(name = "start_time", nullable = false)
    var start: Instant = Instant.now(),

    @Column(name = "end_time")
    var end: Instant? = null,

    @Column(name = "timezone_offset")
    var timezoneOffset: String? = null,

    @Column(name = "sport_name")
    var sportName: String? = null,

    @Column(name = "sport_id")
    var sportId: Int? = null,

    @Column(name = "score_state", nullable = false)
    var scoreState: String = "PENDING_SCORE",

    // Score fields (flattened)
    @Column(name = "strain")
    var strain: Float? = null,

    @Column(name = "average_heart_rate")
    var averageHeartRate: Int? = null,

    @Column(name = "max_heart_rate")
    var maxHeartRate: Int? = null,

    @Column(name = "kilojoule")
    var kilojoule: Float? = null,

    @Column(name = "percent_recorded")
    var percentRecorded: Float? = null,

    @Column(name = "distance_meter")
    var distanceMeter: Float? = null,

    @Column(name = "altitude_gain_meter")
    var altitudeGainMeter: Float? = null,

    @Column(name = "altitude_change_meter")
    var altitudeChangeMeter: Float? = null,

    // Zone durations (flattened)
    @Column(name = "zone_zero_milli")
    var zoneZeroMilli: Long? = null,

    @Column(name = "zone_one_milli")
    var zoneOneMilli: Long? = null,

    @Column(name = "zone_two_milli")
    var zoneTwoMilli: Long? = null,

    @Column(name = "zone_three_milli")
    var zoneThreeMilli: Long? = null,

    @Column(name = "zone_four_milli")
    var zoneFourMilli: Long? = null,

    @Column(name = "zone_five_milli")
    var zoneFiveMilli: Long? = null
)
