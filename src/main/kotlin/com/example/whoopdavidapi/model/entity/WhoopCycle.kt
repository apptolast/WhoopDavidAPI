package com.example.whoopdavidapi.model.entity

import jakarta.persistence.*
import java.time.Instant

@Entity
@Table(name = "whoop_cycles")
class WhoopCycle(
    @Id
    @Column(name = "id")
    var id: Long = 0,

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

    @Column(name = "score_state", nullable = false)
    var scoreState: String = "PENDING_SCORE",

    // Score fields (flattened)
    @Column(name = "strain")
    var strain: Float? = null,

    @Column(name = "kilojoule")
    var kilojoule: Float? = null,

    @Column(name = "average_heart_rate")
    var averageHeartRate: Int? = null,

    @Column(name = "max_heart_rate")
    var maxHeartRate: Int? = null
)
